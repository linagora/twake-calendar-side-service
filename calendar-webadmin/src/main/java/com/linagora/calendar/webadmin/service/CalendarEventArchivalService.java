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

import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse;
import com.linagora.calendar.dav.dto.CalendarReportXmlResponse.CalendarObject;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.model.EventArchivalCriteria;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CalendarEventArchivalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventArchivalService.class);

    private static final String ARCHIVAL_NAME = "Archival";
    private static final Map<String, String> PERSONAL_CALENDAR_QUERY = Map.of("personal", "true");
    private static final Supplier<NewCalendar> ARCHIVAL_CALENDAR_SUPPLIER =
        () -> new NewCalendar(UUID.randomUUID().toString(), ARCHIVAL_NAME, "#8E8E93",
            "Archived events. Events moved here are no longer active.");

    public record Context(AtomicInteger totalSuccess, AtomicInteger totalFailure) {

        public Context() {
            this(new AtomicInteger(), new AtomicInteger());
        }

        public record Snapshot(int success, int failure) {
        }

        void increaseSuccess() {
            totalSuccess.incrementAndGet();
        }

        void increaseFailure() {
            totalFailure.incrementAndGet();
        }

        public Snapshot snapshot() {
            return new Snapshot(totalSuccess.get(), totalFailure.get());
        }
    }

    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    public CalendarEventArchivalService(CalDavClient calDavClient, OpenPaaSUserDAO userDAO) {
        this.calDavClient = calDavClient;
        this.userDAO = userDAO;
    }

    public Mono<Task.Result> archive(EventArchivalCriteria criteria, Context context, int eventsPerSecond) {
        return userDAO.list()
            .flatMap(user -> archiveUser(user, criteria, context, eventsPerSecond), DEFAULT_CONCURRENCY)
            .reduce(Task.Result.COMPLETED, Task::combine)
            .onErrorResume(e -> {
                LOGGER.error("All user calendar archival failed", e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    public Mono<Task.Result> archiveUser(Username username, EventArchivalCriteria criteria, Context initialContext, int eventsPerSecond) {
        return userDAO.retrieve(username)
            .flatMap(user -> archiveUser(user, criteria, initialContext, eventsPerSecond))
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.info("User {} does not exist", username.asString());
                return Mono.just(Task.Result.PARTIAL);
            }));
    }

    public Mono<Task.Result> archiveUser(OpenPaaSUser user, EventArchivalCriteria criteria, Context initialContext, int eventsPerSecond) {
        LOGGER.debug("Starting event archival for user {} with criteria {}", user.username().asString(), criteria);

        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        return resolveArchivalCalendar(user)
            .flatMap(archivalCalendar -> {
                LOGGER.info("Archiving events from default calendar {} to archival calendar {} for user {}",
                    sourceCalendar.asUri(), archivalCalendar.asUri(), user.username().asString());
                return archiveFromCalendar(user.username(), sourceCalendar, archivalCalendar, criteria, initialContext, eventsPerSecond);
            })
            .onErrorResume(e -> {
                LOGGER.error("Calendar archival failed for user {}", user.username().asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<CalendarURL> resolveArchivalCalendar(OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id(), PERSONAL_CALENDAR_QUERY)
            .flatMap(calendarList -> Mono.justOrEmpty(calendarList.findCalendarByName(ARCHIVAL_NAME)))
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.info("No archival calendar found for user {}, creating a new one", user.username().asString());
                NewCalendar newArchivalCalendar = ARCHIVAL_CALENDAR_SUPPLIER.get();
                return calDavClient.createNewCalendar(user.username(), user.id(), newArchivalCalendar)
                    .thenReturn(new CalendarURL(user.id(), new OpenPaaSId(newArchivalCalendar.id())));
            }));
    }

    private Mono<Task.Result> archiveFromCalendar(Username username,
                                                  CalendarURL sourceCalendar,
                                                  CalendarURL archivalCalendar,
                                                  EventArchivalCriteria criteria,
                                                  Context context,
                                                  int eventsPerSecond) {
        return calDavClient.calendarQueryReportXml(username, sourceCalendar, criteria.toCalendarQuery(username))
            .flatMapMany(response -> Flux.fromIterable(response.extractCalendarObjects()))
            .filter(postCalendarQueryFilter(criteria, username))
            .transform(ReactorUtils.<CalendarObject, Task.Result>throttle()
                .elements(eventsPerSecond)
                .per(Duration.ofSeconds(1))
                .forOperation(calendarObject -> archiveEvent(username, sourceCalendar, archivalCalendar, context, calendarObject)))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }


    private Predicate<CalendarObject> postCalendarQueryFilter(EventArchivalCriteria criteria, Username username) {
        Predicate<CalendarObject> filter = calendarObject -> true;

        if (!criteria.isNotRecurring()) {
            filter = filter.and(recurringRejectionSemanticFilter(criteria, username));
        }

        return filter;
    }

    private Predicate<CalendarObject> recurringRejectionSemanticFilter(EventArchivalCriteria criteria, Username username) {
        return calendarObject -> {
            if (!criteria.rejectedOnly()) {
                return true;
            }

            Calendar calendar = CalendarUtil.parseIcs(calendarObject.calendarData());
            return CalendarParticipationUtils.allVEventsInPartStatForUser(calendar, username, PartStat.DECLINED);
        };
    }

    private Mono<Task.Result> archiveEvent(Username username,
                                           CalendarURL sourceCalendar,
                                           CalendarURL archivalCalendar,
                                           Context context,
                                           CalendarReportXmlResponse.CalendarObject calendarObject) {
        String eventPathId = calendarObject.eventPathId();
        return calDavClient.importCalendar(archivalCalendar, eventPathId, username, calendarObject.calendarDataAsBytes())
            .then(calDavClient.deleteCalendarEvent(username, sourceCalendar, eventPathId))
            .doOnSuccess(any -> context.increaseSuccess())
            .thenReturn(Task.Result.COMPLETED)
            .onErrorResume(e -> {
                LOGGER.warn("Failed to archive calendar object {} for user {} from calendar {}",
                    calendarObject.href(), username.asString(), sourceCalendar.asUri().toASCIIString(), e);
                context.increaseFailure();
                return Mono.just(Task.Result.PARTIAL);
            });
    }
}