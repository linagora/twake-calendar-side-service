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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.util.ReactorUtils;
import org.apache.james.vacation.api.AccountId;

import com.github.fge.lambdas.Throwing;
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
import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Uid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CalendarEventsReindexService {

    public static final DateTimeFormatter UTC_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmssX")
        .toFormatter();
    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmss")
        .toFormatter();
    public static final DateTimeFormatter DATE_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd")
        .toFormatter();

    private static final boolean GET_RESOURCE = true;
    private static final boolean GET_ATTENDEE = false;
    private static final int DEFAULT_USERS_PER_SECOND = 1;

    private final OpenPaaSUserDAO userDAO;
    private final CalendarSearchService calendarSearchService;
    private final CalDavClient calDavClient;

    public CalendarEventsReindexService(OpenPaaSUserDAO userDAO, CalendarSearchService calendarSearchService, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.calendarSearchService = calendarSearchService;
        this.calDavClient = calDavClient;
    }

    public Mono<Void> reindex() {
        return userDAO.list()
            .transform(ReactorUtils.<OpenPaaSUser, Void>throttle()
                .elements(DEFAULT_USERS_PER_SECOND)
                .per(Duration.ofSeconds(1))
                .forOperation(this::reindex))
            .then();
    }

    private Mono<Void> reindex(OpenPaaSUser user) {
        AccountId accountId = AccountId.fromUsername(user.username());
        return calendarSearchService.deleteAll(accountId)
            .then(calDavClient.findUserCalendars(user.username(), user.id())
                .flatMap(calendarURL -> calDavClient.export(calendarURL, user.username())
                    .flatMap(bytes -> Mono.fromCallable(() -> CalendarUtil.parseIcs(bytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                    .flatMap(calendar -> reindex(accountId, calendarURL, calendar)))
                .then());
    }

    private Mono<Void> reindex(AccountId accountId, CalendarURL calendarURL, Calendar calendar) {
        return Flux.fromIterable(calendar.getComponents(Component.VEVENT))
            .cast(VEvent.class)
            .groupBy(vEvent -> vEvent.getProperty(Property.UID).get().getValue())
            .flatMap(groupedFlux ->
                groupedFlux.map(vEvent -> toEventFields(vEvent, calendarURL))
                    .collectList()
                    .map(CalendarEvents::of)
                    .flatMap(calendarEvents -> calendarSearchService.index(accountId, calendarEvents)),
                DEFAULT_CONCURRENCY)
            .then();
    }

    private EventFields toEventFields(VEvent vEvent, CalendarURL calendarURL) {
        return EventFields.builder()
            .calendarURL(calendarURL)
            .uid(vEvent.getUid().map(Uid::getValue).orElse(null))
            .summary(vEvent.getProperty(Property.SUMMARY).map(Content::getValue).orElse(null))
            .location(vEvent.getProperty(Property.LOCATION).map(Content::getValue).orElse(null))
            .description(vEvent.getProperty(Property.DESCRIPTION).map(Content::getValue).orElse(null))
            .clazz(vEvent.getProperty(Property.CLASS).map(Content::getValue).orElse(null))
            .start(vEvent.getProperty(Property.DTSTART).map(this::parseTime).orElse(null))
            .end(vEvent.getProperty(Property.DTEND).map(this::parseTime).orElse(null))
            .dtStamp(vEvent.getProperty(Property.DTSTAMP).map(this::parseTime).orElse(null))
            .allDay(vEvent.getProperty(Property.DTSTART).map(Content::getValue).map(value -> !value.contains("T")).orElse(false))
            .isRecurrentMaster(isRecurrentMaster(vEvent))
            .organizer(getOrganizer(vEvent))
            .attendees(getAttendees(vEvent))
            .resources(getResources(vEvent))
            .build();
    }

    private Boolean isRecurrentMaster(VEvent vEvent) {
        if (vEvent.getProperty(Property.RECURRENCE_ID).isPresent()) {
            return false;
        }
        if (vEvent.getProperty(Property.RRULE).isPresent()) {
            return true;
        }
        return null;
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
        if (value.contains("T")) {
            return property.getParameter(Parameter.TZID)
                .map(tzId -> TimeZone.getTimeZone(tzId.getValue()).toZoneId())
                .map(zoneId -> LocalDateTime.parse(value, DATE_TIME_FORMATTER).atZone(zoneId).toInstant())
                .orElseGet(() -> Instant.from(UTC_DATE_TIME_FORMATTER.parse(value)));
        } else {
            return LocalDate.from(DATE_FORMATTER.parse(value)).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
    }

    private EventFields.Person toPerson(Property property) throws AddressException {
        String cn = property.getParameter(Parameter.CN).map(Parameter::getValue).orElse("");
        String email = property.getValue().replaceFirst("^mailto:", "");
        return new EventFields.Person(cn, new MailAddress(email));
    }
}
