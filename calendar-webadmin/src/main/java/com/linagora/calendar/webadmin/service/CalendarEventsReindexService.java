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

import static com.linagora.calendar.webadmin.CalendarRoutes.TASK_NAME;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.apache.james.vacation.api.AccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventFields;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CalendarEventsReindexService {

    public static class Context {
        public record Snapshot(long processedUserCount, long processedEventCount, long failedEventCount, List<User> failedUsers) {
        }

        public record User(String username){}

        private final AtomicLong processedUserCount;
        private final AtomicLong processedEventCount;
        private final AtomicLong failedEventCount;
        private final ConcurrentLinkedDeque<User> failedUsers;

        public Context() {
            processedUserCount = new AtomicLong();
            processedEventCount = new AtomicLong();
            failedEventCount = new AtomicLong();
            failedUsers = new ConcurrentLinkedDeque<>();
        }

        void incrementProcessedUser() {
            processedUserCount.incrementAndGet();
        }

        void incrementProcessedEvent() {
            processedEventCount.incrementAndGet();
        }

        void incrementFailedEvent() {
            failedEventCount.incrementAndGet();
        }

        void addToFailedUsers(User user) {
            failedUsers.add(user);
        }

        public Snapshot snapshot() {
            return new Snapshot(
                processedUserCount.get(),
                processedEventCount.get(),
                failedEventCount.get(),
                ImmutableList.copyOf(failedUsers));
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .add("processedUserCount", processedUserCount.get())
                .add("processedEventCount", processedEventCount.get())
                .add("failedEventCount", failedEventCount.get())
                .add("failedUsers", failedUsers)
                .toString();
        }
    }

    public static final DateTimeFormatter UTC_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmssX")
        .toFormatter();
    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmss")
        .toFormatter();
    public static final DateTimeFormatter DATE_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd")
        .toFormatter();

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventsReindexService.class);

    private static final boolean GET_RESOURCE = true;
    private static final boolean GET_ATTENDEE = false;

    private final OpenPaaSUserDAO userDAO;
    private final CalendarSearchService calendarSearchService;
    private final CalDavClient calDavClient;

    @Inject
    public CalendarEventsReindexService(OpenPaaSUserDAO userDAO, CalendarSearchService calendarSearchService, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.calendarSearchService = calendarSearchService;
        this.calDavClient = calDavClient;
    }

    public Mono<Task.Result> reindex(Context context, int usersPerSecond) {
        return userDAO.list()
            .transform(ReactorUtils.<OpenPaaSUser, Task.Result>throttle()
                .elements(usersPerSecond)
                .per(Duration.ofSeconds(1))
                .forOperation(user -> reindex(context, user)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .doOnNext(result -> LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME.asString(), result.toString(), context.snapshot()))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {}", TASK_NAME.asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> reindex(Context context, OpenPaaSUser user) {
        return calendarSearchService.deleteAll(AccountId.fromUsername(user.username()))
            .then(Mono.fromRunnable(() -> LOGGER.info("{} task deleted all events of user {}", TASK_NAME.asString(), user.username())))
            .then(calDavClient.findUserCalendars(user.username(), user.id())
                .flatMap(calendarURL -> reindex(context, user, calendarURL))
                .reduce(Task.Result.COMPLETED, Task::combine))
            .doOnNext(result -> {
                LOGGER.info("{} task result for user {}: {}", TASK_NAME.asString(), user.username(), result.toString());
                if (result == Task.Result.COMPLETED) {
                    context.incrementProcessedUser();
                } else {
                    context.addToFailedUsers(new Context.User(user.username().asString()));
                }
            }).onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {}", TASK_NAME.asString(), user.username().asString(), e);
                context.addToFailedUsers(new Context.User(user.username().asString()));
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> reindex(Context context, OpenPaaSUser user, CalendarURL calendarURL) {
        return calDavClient.export(calendarURL, user.username())
            .flatMap(bytes -> Mono.fromCallable(() -> CalendarUtil.parseIcs(bytes))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(calendar -> reindex(context, user, calendarURL, calendar))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {} and calendar url {}", TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> reindex(Context context, OpenPaaSUser user, CalendarURL calendarURL, Calendar calendar) {
        AccountId accountId = AccountId.fromUsername(user.username());
        return Flux.fromIterable(calendar.getComponents(Component.VEVENT))
            .cast(VEvent.class)
            .groupBy(vEvent -> vEvent.getProperty(Property.UID).get().getValue())
            .flatMap(groupedFlux ->
                groupedFlux.map(vEvent -> toEventFields(vEvent, calendarURL))
                    .collectList()
                    .map(CalendarEvents::of)
                    .flatMap(calendarEvents -> calendarSearchService.index(accountId, calendarEvents))
                    .then(Mono.fromCallable(() -> {
                        context.incrementProcessedEvent();
                        return Task.Result.COMPLETED;
                    })).onErrorResume(e -> {
                        LOGGER.error("Error while doing task {} for user {} and calendar {} and eventId {}",
                            TASK_NAME.asString(), user.username().asString(), calendarURL.serialize(), groupedFlux.key(), e);
                        context.incrementFailedEvent();
                        return Mono.just(Task.Result.PARTIAL);
                    }),
                DEFAULT_CONCURRENCY)
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private EventFields toEventFields(VEvent vEvent, CalendarURL calendarURL) {
        EventFields.Builder builder = EventFields.builder()
            .calendarURL(calendarURL);

        vEvent.getUid().ifPresent(uid -> builder.uid(uid.getValue()));
        vEvent.getProperty(Property.SUMMARY).ifPresent(prop -> builder.summary(prop.getValue()));
        vEvent.getProperty(Property.LOCATION).ifPresent(prop -> builder.location(prop.getValue()));
        vEvent.getProperty(Property.DESCRIPTION).ifPresent(prop -> builder.description(prop.getValue()));
        vEvent.getProperty(Property.CLASS).ifPresent(prop -> builder.clazz(prop.getValue()));
        vEvent.getProperty(Property.DTSTART).ifPresent(prop -> {
            builder.start(parseTime(prop));
            builder.allDay(isDate(prop));
        });
        vEvent.getProperty(Property.DTEND).ifPresent(prop -> builder.end(parseTime(prop)));
        vEvent.getProperty(Property.DTSTAMP).ifPresent(prop -> builder.dtStamp(parseTime(prop)));
        isRecurrentMaster(vEvent).ifPresent(builder::isRecurrentMaster);
        builder.organizer(getOrganizer(vEvent));
        builder.attendees(getAttendees(vEvent));
        builder.resources(getResources(vEvent));

        return builder.build();
    }

    private Optional<Boolean> isRecurrentMaster(VEvent vEvent) {
        if (vEvent.getProperty(Property.RECURRENCE_ID).isPresent()) {
            return Optional.of(false);
        }
        if (vEvent.getProperty(Property.RRULE).isPresent()) {
            return Optional.of(true);
        }
        return Optional.empty();
    }

    private EventFields.Person getOrganizer(VEvent vEvent) {
        return vEvent.getProperty(Property.ORGANIZER)
            .map(Throwing.function(this::toPerson))
            .orElse(null);
    }

    private List<EventFields.Person> getAttendees(VEvent vEvent) {
        return getPeople(vEvent, GET_ATTENDEE);
    }

    private List<EventFields.Person> getResources(VEvent vEvent) {
        return getPeople(vEvent, GET_RESOURCE);
    }

    private List<EventFields.Person> getPeople(VEvent vEvent, boolean getResource) {
        return vEvent.getProperties(Property.ATTENDEE)
            .stream()
            .filter(attendee -> attendee.getParameter(Parameter.CUTYPE)
                .map(parameter -> getResource == "RESOURCE".equals(parameter.getValue()))
                .orElse(false))
            .map(Throwing.function(this::toPerson))
            .toList();
    }

    private Instant parseTime(Property property) {
        String value = property.getValue();
        if (isDate(property)) {
            return LocalDate.from(DATE_FORMATTER.parse(value)).atStartOfDay().toInstant(ZoneOffset.UTC);
        } else {
            return property.getParameter(Parameter.TZID)
                .map(tzId -> TimeZone.getTimeZone(tzId.getValue()).toZoneId())
                .map(zoneId -> LocalDateTime.parse(value, DATE_TIME_FORMATTER).atZone(zoneId).toInstant())
                .orElseGet(() -> Instant.from(UTC_DATE_TIME_FORMATTER.parse(value)));
        }
    }

    private boolean isDate(Property prop) {
        return prop.getParameter(Parameter.VALUE).map(parameter -> "DATE".equals(parameter.getValue())).orElse(false);
    }

    private EventFields.Person toPerson(Property property) throws AddressException {
        String cn = property.getParameter(Parameter.CN).map(Parameter::getValue).orElse("");
        String email = property.getValue().replaceFirst("^mailto:", "");
        return new EventFields.Person(cn, new MailAddress(email));
    }
}
