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

import static com.linagora.calendar.webadmin.CalendarRoutes.AlarmScheduleRequestToTask.TASK_NAME;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.MoreObjects;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.dav.model.CalendarQuery;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.AlarmEventFactory;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class AlarmScheduleService {

    public record CalendarEvent(Calendar calendar, Username username, CalendarURL calendarURL, String eventPath) {
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

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmScheduleService.class);

    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;
    private final AlarmEventDAO alarmEventDAO;
    private final AlarmInstantFactory alarmInstantFactory;
    private final AlarmEventFactory alarmEventFactory;

    @Inject
    public AlarmScheduleService(OpenPaaSUserDAO userDAO,
                                CalDavClient calDavClient,
                                AlarmEventDAO alarmEventDAO,
                                AlarmInstantFactory alarmInstantFactory,
                                AlarmEventFactory alarmEventFactory) {
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
        this.alarmEventDAO = alarmEventDAO;
        this.alarmInstantFactory = alarmInstantFactory;
        this.alarmEventFactory = alarmEventFactory;
    }

    public Mono<Task.Result> schedule(Context context, int eventsPerSecond) {
        return userDAO.list()
            .flatMap(user -> collectEvents(context, user), DEFAULT_CONCURRENCY)
            .transform(ReactorUtils.<CalendarEvent, Task.Result>throttle()
                .elements(eventsPerSecond)
                .per(Duration.ofSeconds(1))
                .forOperation(calendarEvent -> schedule(context, calendarEvent)))
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

    private Mono<Task.Result> schedule(Context context, CalendarEvent calendarEvent) {
        return Mono.justOrEmpty(alarmInstantFactory.computeNextAlarmInstant(calendarEvent.calendar(), calendarEvent.username()))
            .flatMap(alarmInstant -> Flux.fromIterable(alarmEventFactory.buildAlarmEvent(calendarEvent.username(),
                    alarmInstant.recipients(),
                    calendarEvent.calendar(),
                    alarmInstant,
                    calendarEvent.eventPath()))
                .flatMap(alarmEvent -> insertAlarmEvent(calendarEvent.username(), alarmEvent))
                .then())
            .then(Mono.fromCallable(() -> {
                context.incrementProcessedEvent();
                return Task.Result.COMPLETED;
            })).onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar url {} and eventId {}",
                    TASK_NAME.asString(), calendarEvent.username().asString(),
                    calendarEvent.calendarURL().serialize(),
                    EventParseUtils.extractEventUid(calendarEvent.calendar()),
                    e);
                context.incrementFailedEvent();
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Void> insertAlarmEvent(Username username, AlarmEvent alarmEvent) {
        return alarmEventDAO.find(alarmEvent.eventUid(), Throwing.supplier(username::asMailAddress).get())
            .flatMap(existing -> Mono.empty())
            .switchIfEmpty(Mono.defer(() -> alarmEventDAO.create(alarmEvent))).then();
    }

    private Flux<CalendarEvent> collectEvents(Context context, OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id())
                .flatMap(calendarURL -> collectEvents(context, user, calendarURL))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {}", TASK_NAME.asString(), user.username().asString(), e);
                context.incrementFailedUser();
                return Mono.empty();
            });
    }

    private Flux<CalendarEvent> collectEvents(Context context, OpenPaaSUser user, CalendarURL calendarURL) {
        return calDavClient.calendarQueryReportXml(user.username(), calendarURL, CalendarQuery.ofFilters())
            .flatMapMany(response -> Flux.fromIterable(response.extractCalendarObjects())
                .subscribeOn(Schedulers.parallel()))
            .flatMap(calendarObject -> collectEvents(context, user, calendarURL, calendarObject), DEFAULT_CONCURRENCY)
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar url {}", TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), e);
                context.incrementFailedCalendar();
                return Flux.empty();
            });
    }

    private Mono<CalendarEvent> collectEvents(Context context, OpenPaaSUser user, CalendarURL calendarURL, CalendarReportXmlResponse.CalendarObject calendarObject) {
        return Mono.fromCallable(() -> CalendarUtil.parseIcs(calendarObject.calendarData()))
            .subscribeOn(Schedulers.parallel())
            .map(calendar -> new CalendarEvent(calendar, user.username(), calendarURL, calendarObject.href().toString()))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar url {} and eventPath {}",
                    TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), calendarObject.href().toString(), e);
                context.incrementFailedEvent();
                return Mono.empty();
            });
    }
}
