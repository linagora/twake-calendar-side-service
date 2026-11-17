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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.dto.CalendarMultiGetEntry;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse.CalendarObject;
import com.linagora.calendar.dav.model.CalendarQuery;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTask.RunningOptions;

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
    // Limit hrefs per calendar-multiget REPORT to keep ICS XML responses small during reindex.
    private static final int MULTIGET_BATCH_SIZE = 50;
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

    public Mono<Task.Result> reindex(Context context, RunningOptions runningOptions) {
        return Flux.concat(
                userDAO.list()
                    .collectList()
                    .flatMapMany(Flux::fromIterable)
                    .concatMap(user -> deleteIndexAndCollectEvents(context, user, runningOptions)),
                resourceDAO.findAll()
                    .collectList()
                    .flatMapMany(Flux::fromIterable)
                    .concatMap(resource -> deleteIndexAndCollectEvents(context, resource)))
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

    private Flux<CalendarEvents> deleteIndexAndCollectEvents(Context context, OpenPaaSUser user, RunningOptions runningOptions) {
        return calendarSearchService.deleteAll(user.id())
            .doOnSuccess(ignored -> LOGGER.info("{} task deleted all events of user {}", TASK_NAME.asString(), user.username()))
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
        return calDavClient.calendarQueryReportXml(user.username(), calendarURL, CalendarQuery.hrefs())
            .switchIfEmpty(Mono.error(() -> new DavClientException("Empty href REPORT response for " + calendarURL.serialize())))
            .flatMapMany(response -> collectEvents(context, calendarURL, response,
                hrefs -> calDavClient.calendarMultiGet(user.username(), calendarURL, hrefs)))
            .onErrorResume(e -> {
                LOGGER.error("{} failed to read calendar {} for user {}", TASK_NAME.asString(), calendarURL.serialize(), user.username().asString(), e);
                context.incrementFailedCalendar();
                return Mono.empty();
            });
    }

    private Flux<CalendarEvents> deleteIndexAndCollectEvents(Context context, Resource resource) {
        return calendarSearchService.deleteAll(resource.id().asOpenPaaSId())
            .doOnSuccess(ignored -> LOGGER.info("{} task deleted all events of resource {}", TASK_NAME.asString(), resource.id().value()))
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
        return calDavClient.calendarQueryReportXml(resource.domain(), calendarURL, CalendarQuery.hrefs())
            .switchIfEmpty(Mono.error(() -> new DavClientException("Empty href REPORT response for " + calendarURL.serialize())))
            .flatMapMany(response -> collectEvents(context, calendarURL, response,
                hrefs -> calDavClient.calendarMultiGet(resource.domain(), calendarURL, hrefs)));
    }

    private Flux<CalendarEvents> collectEvents(Context context, CalendarURL calendarURL, CalendarReportXmlResponse response,
                                               Function<List<URI>, Mono<List<CalendarMultiGetEntry>>> fetchBatch) {
        return Mono.fromCallable(response::extractCalendarObjectHrefs)
            .subscribeOn(Schedulers.boundedElastic())
            .map(hrefs -> Lists.partition(hrefs, MULTIGET_BATCH_SIZE))
            .flatMapMany(Flux::fromIterable)
            .concatMap(hrefs -> Mono.defer(() -> fetchBatch.apply(hrefs))
                .flatMapMany(Flux::fromIterable)
                .concatMap(entry -> {
                    // The event may have been deleted since the href listing.
                    if (entry.isNotFound()) {
                        return Mono.empty();
                    }
                    if (!entry.isSuccessful()) {
                        LOGGER.error("{} failed to fetch ICS resource {} in calendar {}: status={}", TASK_NAME.asString(), entry.href(), calendarURL.serialize(), entry.status());
                        context.incrementFailedEvent();
                        return Mono.empty();
                    }
                    return extractCalendarEvents(context, calendarURL, new CalendarObject(entry.href(), entry.calendarData()));
                })
                .onErrorResume(e -> {
                    LOGGER.error("{} failed to fetch ICS batch {} in calendar {}", TASK_NAME.asString(), hrefs, calendarURL.serialize(), e);
                    context.failedEventCount.addAndGet(hrefs.size());
                    return Flux.empty();
                }));
    }

    private static Mono<CalendarEvents> extractCalendarEvents(Context context, CalendarURL calendarURL, CalendarObject calendarObject) {
        return Mono.fromCallable(() -> CalendarUtil.parseIcs(calendarObject.calendarData()))
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
            });
    }
}
