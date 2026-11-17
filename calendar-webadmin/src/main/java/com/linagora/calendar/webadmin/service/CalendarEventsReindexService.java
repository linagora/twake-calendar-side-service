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
import static com.linagora.calendar.webadmin.task.CalendarEventsReindexTask.RunningOptions;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.dav.model.CalendarQuery;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.model.Resource;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CalendarEventsReindexService {

    public static class Context {
        public record Snapshot(long processedEventCount, long failedEventCount) {
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
            return new Snapshot(processedEventCount.get(), failedEventCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventsReindexService.class);

    private final OpenPaaSUserDAO userDAO;
    private final ResourceDAO resourceDAO;
    private final CalendarSearchService calendarSearchService;
    private final CalDavClient calDavClient;
    private final CalendarEventsReindexHandler userHandler;
    private final CalendarEventsReindexHandler resourceHandler;

    @Inject
    public CalendarEventsReindexService(OpenPaaSUserDAO userDAO, ResourceDAO resourceDAO, CalendarSearchService calendarSearchService, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.resourceDAO = resourceDAO;
        this.calendarSearchService = calendarSearchService;
        this.calDavClient = calDavClient;
        this.userHandler = new UserCalendarEventsReindexHandler();
        this.resourceHandler = new ResourceCalendarEventsReindexHandler();
    }

    public Mono<Task.Result> reindex(Context context, RunningOptions runningOptions) {
        return Flux.concat(userHandler.prepareEvents(context, runningOptions), resourceHandler.prepareEvents(context, runningOptions))
            .transform(ReactorUtils.<CalendarEvents, Task.Result>throttle()
                .elements(runningOptions.eventsPerSecond())
                .per(Duration.ofSeconds(1))
                .forOperation(calendarEvents -> reindex(context, calendarEvents)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .map(result -> context.hasFailures() ? Task.Result.PARTIAL : result)
            .onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TASK_NAME.asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            })
            .doOnNext(result -> LOGGER.info("{} task result: {}. Detail: {}, failedUserCount={}, failedCalendarCount={}, failedResourceCount={}",
                TASK_NAME.asString(), result, context.snapshot(), context.failedUserCount.get(), context.failedCalendarCount.get(), context.failedResourceCount.get()));
    }

    private Mono<Task.Result> reindex(Context context, CalendarEvents calendarEvents) {
        return calendarSearchService.reindex(calendarEvents)
            .doOnSuccess(ignored -> context.incrementProcessedEvent())
            .thenReturn(Task.Result.COMPLETED)
            .onErrorResume(e -> {
                LOGGER.error("{} failed to index event {} in calendar {}", TASK_NAME.asString(), calendarEvents.eventUid().value(), calendarEvents.calendarURL().serialize(), e);
                context.incrementFailedEvent();
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private static Flux<CalendarEvents> extractCalendarEvents(Context context, CalendarURL calendarURL, CalendarReportXmlResponse response) {
        return Flux.fromIterable(response.extractCalendarObjects())
            .flatMap(calendarObject -> Mono.fromCallable(() -> CalendarUtil.parseIcs(calendarObject.calendarData()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(calendar -> calendar.<VEvent>getComponents(Component.VEVENT).stream()
                    .map(vEvent -> EventFields.fromVEvent(vEvent, calendarURL, calendarObject.icsResourceFullName()))
                    .toList())
                .filter(events -> !events.isEmpty())
                .map(CalendarEvents::of)
                .onErrorResume(e -> {
                    LOGGER.error("{} failed to parse ICS resource {} in calendar {}", TASK_NAME.asString(), calendarObject.icsResourceFullName(), calendarURL.serialize(), e);
                    context.incrementFailedEvent();
                    return Mono.empty();
                }));
    }

    public interface CalendarEventsReindexHandler {
        Flux<CalendarEvents> prepareEvents(Context context, RunningOptions runningOptions);
    }

    private final class UserCalendarEventsReindexHandler implements CalendarEventsReindexHandler {
        private static final Map<String, String> PERSONAL_CALENDAR_QUERY = Map.of("personal", "true");

        @Override
        public Flux<CalendarEvents> prepareEvents(Context context, RunningOptions runningOptions) {
            return userDAO.list()
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(user -> deleteIndexAndCollectEvents(context, user, runningOptions));
        }

        private Flux<CalendarEvents> deleteIndexAndCollectEvents(Context context, OpenPaaSUser user, RunningOptions runningOptions) {
            return calendarSearchService.deleteAll(user.id())
                .thenMany(Flux.defer(() -> collectEvents(context, user, runningOptions)))
                .onErrorResume(e -> {
                    LOGGER.error("{} failed to prepare events for user {}", TASK_NAME.asString(), user.username().asString(), e);
                    context.incrementFailedUser();
                    return Mono.empty();
                });
        }

        private Flux<CalendarEvents> collectEvents(Context context, OpenPaaSUser user, RunningOptions runningOptions) {
            return calDavClient.findUserCalendars(user.username(), user.id(), PERSONAL_CALENDAR_QUERY)
                .flatMapMany(response -> Flux.fromIterable(response.calendars().keySet()))
                .flatMap(calendarURL -> collectEvents(context, user, calendarURL), runningOptions.calendarsConcurrency());
        }

        private Flux<CalendarEvents> collectEvents(Context context, OpenPaaSUser user, CalendarURL calendarURL) {
            return calDavClient.calendarQueryReportXml(user.username(), calendarURL, CalendarQuery.ofFilters())
                .flatMapMany(response -> extractCalendarEvents(context, calendarURL, response))
                .onErrorResume(e -> {
                    LOGGER.error("{} failed to read calendar {} for user {}", TASK_NAME.asString(), calendarURL.serialize(), user.username().asString(), e);
                    context.incrementFailedCalendar();
                    return Mono.empty();
                });
        }
    }

    private final class ResourceCalendarEventsReindexHandler implements CalendarEventsReindexHandler {
        @Override
        public Flux<CalendarEvents> prepareEvents(Context context, RunningOptions runningOptions) {
            return resourceDAO.findAll()
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(resource -> deleteIndexAndCollectEvents(context, resource));
        }

        private Flux<CalendarEvents> deleteIndexAndCollectEvents(Context context, Resource resource) {
            return calendarSearchService.deleteAll(resource.id().asOpenPaaSId())
                .thenMany(Flux.defer(() -> collectEvents(context, resource)))
                .onErrorResume(e -> {
                    String resourceId = resource.id().value();
                    LOGGER.error("{} failed to prepare events for resource {} (calendar {}/{})", TASK_NAME.asString(), resourceId, resourceId, resourceId, e);
                    context.incrementFailedResource();
                    return Mono.empty();
                });
        }

        private Flux<CalendarEvents> collectEvents(Context context, Resource resource) {
            if (resource.deleted()) {
                return Flux.empty();
            }
            CalendarURL calendarURL = CalendarURL.from(resource.id().asOpenPaaSId());
            return calDavClient.calendarQueryReportXml(resource.domain(), calendarURL, CalendarQuery.ofFilters())
                .flatMapMany(response -> extractCalendarEvents(context, calendarURL, response));
        }
    }
}
