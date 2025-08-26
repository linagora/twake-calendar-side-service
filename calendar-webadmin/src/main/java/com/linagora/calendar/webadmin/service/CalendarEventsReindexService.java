/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.webadmin.CalendarRoutes.CalendarEventsReindexRequestToTask.TASK_NAME;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.apache.james.vacation.api.AccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CalendarEventsReindexService {

    public record IndexItem(OpenPaaSUser user, CalendarURL calendarURL, CalendarEvents calendarEvents){
    }

    public static class Context {
        public record Snapshot(long processedEventCount, long failedEventCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("processedEventCount", processedEventCount)
                    .add("failedEventCount", failedEventCount)
                    .toString();
            }
        }

        private final AtomicLong processedEventCount;
        private final AtomicLong failedEventCount;
        private final AtomicLong failedUserCount;
        private final AtomicLong failedCalendarCount;

        public Context() {
            processedEventCount = new AtomicLong();
            failedEventCount = new AtomicLong();
            failedUserCount = new AtomicLong();
            failedCalendarCount = new AtomicLong();
        }

        void incrementProcessedEvent() {
            processedEventCount.incrementAndGet();
        }

        void incrementFailedEvent() {
            failedEventCount.incrementAndGet();
        }

        void incrementFailedUser() {
            failedUserCount.incrementAndGet();
        }

        void incrementFailedCalendar() {
            failedCalendarCount.incrementAndGet();
        }

        public Snapshot snapshot() {
            return new Snapshot(
                processedEventCount.get(),
                failedEventCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventsReindexService.class);

    private final OpenPaaSUserDAO userDAO;
    private final CalendarSearchService calendarSearchService;
    private final CalDavClient calDavClient;

    @Inject
    public CalendarEventsReindexService(OpenPaaSUserDAO userDAO, CalendarSearchService calendarSearchService, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.calendarSearchService = calendarSearchService;
        this.calDavClient = calDavClient;
    }

    public Mono<Task.Result> reindex(Context context, int eventsPerSecond) {
        return userDAO.list()
            .flatMap(user -> collectEvents(context, user), DEFAULT_CONCURRENCY)
            .transform(ReactorUtils.<IndexItem, Task.Result>throttle()
                .elements(eventsPerSecond)
                .per(Duration.ofSeconds(1))
                .forOperation(indexItem -> reindex(context, indexItem)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .map(result -> {
                if (context.failedUserCount.get() > 0 || context.failedCalendarCount.get() > 0 || context.failedEventCount.get() > 0) {
                    LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME.asString(), Task.Result.PARTIAL, context.snapshot());
                    return Task.Result.PARTIAL;
                } else {
                    LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME.asString(), result.toString(), context.snapshot());
                    return result;
                }
            }).onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TASK_NAME.asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> reindex(Context context, IndexItem indexItem) {
        return calendarSearchService.index(AccountId.fromUsername(indexItem.user().username()), indexItem.calendarEvents())
            .then(Mono.fromCallable(() -> {
                context.incrementProcessedEvent();
                return Task.Result.COMPLETED;
            })).onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar {} and eventId {}",
                    TASK_NAME.asString(), indexItem.user().username().asString(), indexItem.calendarURL().serialize(), indexItem.calendarEvents().eventUid().value(), e);
                context.incrementFailedEvent();
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Flux<IndexItem> collectEvents(Context context, OpenPaaSUser user) {
        return calendarSearchService.deleteAll(AccountId.fromUsername(user.username()))
            .then(Mono.fromRunnable(() -> LOGGER.info("{} task deleted all events of user {}", TASK_NAME.asString(), user.username())))
            .thenMany(calDavClient.findUserCalendars(user.username(), user.id())
                .flatMap(calendarURL -> collectEvents(context, user, calendarURL)))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {}", TASK_NAME.asString(), user.username().asString(), e);
                context.incrementFailedUser();
                return Mono.empty();
            });
    }

    private Flux<IndexItem> collectEvents(Context context, OpenPaaSUser user, CalendarURL calendarURL) {
        return calDavClient.export(calendarURL, user.username())
            .flatMap(bytes -> Mono.fromCallable(() -> CalendarUtil.parseIcs(bytes))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMapMany(calendar -> collectEvents(context, user, calendarURL, calendar))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar url {}", TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), e);
                context.incrementFailedCalendar();
                return Mono.empty();
            });
    }

    private Flux<IndexItem> collectEvents(Context context, OpenPaaSUser user, CalendarURL calendarURL, Calendar calendar) {
        return Mono.fromCallable(() -> EventParseUtils.groupByUid(calendar))
            .flatMapMany(map -> Flux.fromIterable(map.entrySet()))
            .flatMap(entry -> {
                String eventId = entry.getKey();
                List<VEvent> vEvents = entry.getValue();

                return Flux.fromIterable(vEvents)
                    .map(vEvent -> EventFields.fromVEvent(vEvent, calendarURL))
                    .collectList()
                    .filter(list -> !list.isEmpty())
                    .map(CalendarEvents::of)
                    .map(calendarEvents -> new IndexItem(user, calendarURL, calendarEvents))
                    .onErrorResume(e -> {
                        LOGGER.error("Error while doing task {} for user {} and calendar {} and eventId {}",
                            TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), eventId, e);
                        context.incrementFailedEvent();
                        return Mono.empty();
                    });
            });
    }
}
