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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.dav.model.CalendarQuery;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTask;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CalendarEventsReindexService {

    public record IndexItem(String owner, CalendarURL calendarURL, CalendarEvents calendarEvents) {
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
        private final AtomicLong failedResourceCount;

        public Context() {
            processedEventCount = new AtomicLong();
            failedEventCount = new AtomicLong();
            failedUserCount = new AtomicLong();
            failedCalendarCount = new AtomicLong();
            failedResourceCount = new AtomicLong();
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

        void incrementFailedResource() {
            failedResourceCount.incrementAndGet();
        }

        boolean hasFailures() {
            return failedUserCount.get() > 0
                || failedCalendarCount.get() > 0
                || failedResourceCount.get() > 0
                || failedEventCount.get() > 0;
        }

        public Snapshot snapshot() {
            return new Snapshot(
                processedEventCount.get(),
                failedEventCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventsReindexService.class);
    private static final Map<String, String> PERSONAL_CALENDAR_QUERY = Map.of("personal", "true");

    private final OpenPaaSUserDAO userDAO;
    private final ResourceDAO resourceDAO;
    private final CalendarSearchService calendarSearchService;
    private final CalDavClient calDavClient;

    @Inject
    public CalendarEventsReindexService(OpenPaaSUserDAO userDAO, ResourceDAO resourceDAO, CalendarSearchService calendarSearchService, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.resourceDAO = resourceDAO;
        this.calendarSearchService = calendarSearchService;
        this.calDavClient = calDavClient;
    }

    public Mono<Task.Result> reindex(Context context, CalendarEventsReindexTask.RunningOptions runningOptions) {
        return Flux.concat(
                userDAO.list()
                    .concatMap(user -> collectEvents(context, user, runningOptions.calendarsConcurrency())),
                resourceDAO.findAll()
                    .concatMap(resource -> collectEvents(context, resource)))
            .transform(ReactorUtils.<IndexItem, Task.Result>throttle()
                .elements(runningOptions.eventsPerSecond())
                .per(Duration.ofSeconds(1))
                .forOperation(indexItem -> reindex(context, indexItem)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .map(result -> {
                if (context.hasFailures()) {
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
        return calendarSearchService.reindex(indexItem.calendarEvents())
            .then(Mono.fromCallable(() -> {
                context.incrementProcessedEvent();
                return Task.Result.COMPLETED;
            })).onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for owner {} and calendar {} and eventId {}",
                    TASK_NAME.asString(), indexItem.owner(), indexItem.calendarURL().serialize(), indexItem.calendarEvents().eventUid().value(), e);
                context.incrementFailedEvent();
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Flux<IndexItem> collectEvents(Context context, OpenPaaSUser user, int calendarsConcurrency) {
        return calendarSearchService.deleteAll(user.id())
            .then(Mono.fromRunnable(() -> LOGGER.info("{} task deleted all events of user {}", TASK_NAME.asString(), user.username())))
            .thenMany(calDavClient.findUserCalendars(user.username(), user.id(), PERSONAL_CALENDAR_QUERY)
                .flatMapMany(response -> Flux.fromIterable(response.calendars().keySet()))
                .flatMap(calendarURL -> collectEvents(context, user, calendarURL), calendarsConcurrency))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {}", TASK_NAME.asString(), user.username().asString(), e);
                context.incrementFailedUser();
                return Mono.empty();
            });
    }

    private Flux<IndexItem> collectEvents(Context context, Resource resource) {
        OpenPaaSId resourceId = resource.id().asOpenPaaSId();
        return calendarSearchService.deleteAll(resourceId)
            .then(Mono.fromRunnable(() -> LOGGER.info("{} task deleted all events of resource {}", TASK_NAME.asString(), resource.id().value())))
            .thenMany(Mono.defer(() -> {
                if (resource.deleted()) {
                    return Mono.empty();
                }
                return Mono.just(CalendarURL.from(resourceId));
            }).flatMapMany(calendarURL -> collectEvents(context, resource, calendarURL)))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for resource {}", TASK_NAME.asString(), resource.id().value(), e);
                context.incrementFailedResource();
                return Mono.empty();
            });
    }

    private Flux<IndexItem> collectEvents(Context context, OpenPaaSUser user, CalendarURL calendarURL) {
        return calDavClient.calendarQueryReportXml(user.username(), calendarURL, CalendarQuery.ofFilters())
            .flatMapMany(response -> Flux.fromIterable(response.extractCalendarObjects())
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(calendarObject -> collectEvents(context, user.username().asString(), calendarURL, calendarObject))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar url {}", TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), e);
                context.incrementFailedCalendar();
                return Mono.empty();
            });
    }

    private Flux<IndexItem> collectEvents(Context context, Resource resource, CalendarURL calendarURL) {
        return calDavClient.calendarQueryReportXml(resource.domain(), calendarURL, CalendarQuery.ofFilters())
            .flatMapMany(response -> Flux.fromIterable(response.extractCalendarObjects())
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(calendarObject -> collectEvents(context, resource.id().value(), calendarURL, calendarObject))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for resource {} and calendar url {}", TASK_NAME.asString(), resource.id().value(), calendarURL.serialize(), e);
                context.incrementFailedResource();
                return Mono.empty();
            });
    }

    private Mono<IndexItem> collectEvents(Context context, String owner, CalendarURL calendarURL, CalendarReportXmlResponse.CalendarObject calendarObject) {
        String resourceName = calendarObject.eventPathId();
        return Mono.fromCallable(() -> CalendarUtil.parseIcs(calendarObject.calendarData()))
            .subscribeOn(Schedulers.boundedElastic())
            .map(calendar -> calendar.getComponents(Component.VEVENT).stream()
                .map(VEvent.class::cast)
                .map(vEvent -> EventFields.fromVEvent(vEvent, calendarURL, resourceName))
                .toList())
            .filter(events -> !events.isEmpty())
            .map(CalendarEvents::of)
            .map(calendarEvents -> new IndexItem(owner, calendarURL, calendarEvents))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for owner {} and calendar {} and ics resource name {}",
                    TASK_NAME.asString(), owner, calendarURL.serialize(), resourceName, e);
                context.incrementFailedEvent();
                return Mono.empty();
            });
    }
}
