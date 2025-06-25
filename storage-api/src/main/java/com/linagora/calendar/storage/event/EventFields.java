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

package com.linagora.calendar.storage.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public record EventFields(EventUid uid,
                          String summary,
                          String location,
                          String description,
                          String clazz,
                          Instant start,
                          Instant end,
                          Instant dtStamp,
                          Boolean allDay,
                          Boolean hasResources,
                          Boolean isRecurrentMaster,
                          Integer durationInDays,
                          Person organizer,
                          List<Person> attendees,
                          List<Person> resources,
                          CalendarURL calendarURL) {

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
    public static final Logger LOGGER = LoggerFactory.getLogger(EventFields.class);

    public record Person(String cn, MailAddress email, Optional<PartStat> partStat) {

        public static Person of(String cn, String email) throws AddressException {
            return new Person(cn, new MailAddress(email), Optional.empty());
        }

        public Person(String cn, MailAddress email) {
            this(cn, email, Optional.empty());
        }

        public Person {
            Preconditions.checkNotNull(email, "email must not be null");
        }
    }

    public EventFields {
        Preconditions.checkNotNull(uid, "uid must not be null");
        Preconditions.checkNotNull(calendarURL, "calendarURL must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EventUid uid;
        private String summary;
        private String location;
        private String description;
        private String clazz;
        private Instant start;
        private Instant end;
        private Instant dtStamp;
        private Boolean allDay = false;
        private Boolean isRecurrentMaster;
        private EventFields.Person organizer;
        private List<EventFields.Person> attendees = new ArrayList<>();
        private List<EventFields.Person> resources = new ArrayList<>();
        private CalendarURL calendarURL;

        public Builder uid(String uid) {
            return uid(new EventUid(uid));
        }

        public Builder uid(EventUid uid) {
            this.uid = uid;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder clazz(String clazz) {
            this.clazz = clazz;
            return this;
        }

        public Builder start(Instant start) {
            this.start = start;
            return this;
        }

        public Builder end(Instant end) {
            this.end = end;
            return this;
        }

        public Builder dtStamp(Instant dtStamp) {
            this.dtStamp = dtStamp;
            return this;
        }

        public Builder allDay(boolean allDay) {
            this.allDay = allDay;
            return this;
        }

        public Builder isRecurrentMaster(boolean isRecurrentMaster) {
            this.isRecurrentMaster = isRecurrentMaster;
            return this;
        }

        public Builder organizer(EventFields.Person organizer) {
            this.organizer = organizer;
            return this;
        }

        public Builder attendees(List<EventFields.Person> attendees) {
            this.attendees = attendees;
            return this;
        }

        public Builder addAttendee(EventFields.Person attendee) {
            this.attendees.add(attendee);
            return this;
        }

        public Builder resources(List<EventFields.Person> resources) {
            this.resources = resources;
            return this;
        }

        public Builder addResource(EventFields.Person resource) {
            this.resources.add(resource);
            return this;
        }

        public Builder calendarURL(CalendarURL calendarURL) {
            this.calendarURL = calendarURL;
            return this;
        }

        int calculateDurationInDays() {
            if (start == null || end == null || !end.isAfter(start)) {
                return 0;
            }
            return (int) ChronoUnit.DAYS.between(start, end);
        }

        boolean calculateHasResources() {
            return resources != null && !resources.isEmpty();
        }

        public EventFields build() {
            return new EventFields(
                uid,
                summary,
                location,
                description,
                clazz,
                start,
                end,
                dtStamp,
                allDay,
                calculateHasResources(),
                isRecurrentMaster,
                calculateDurationInDays(),
                organizer,
                attendees,
                resources,
                calendarURL);
        }
    }

    public static EventFields fromVEvent(VEvent vEvent, CalendarURL calendarURL) {
        EventFields.Builder builder = EventFields.builder()
            .calendarURL(calendarURL);

        vEvent.getUid().ifPresent(uid -> builder.uid(uid.getValue()));
        vEvent.getProperty(Property.SUMMARY).ifPresent(prop -> builder.summary(prop.getValue()));
        vEvent.getProperty(Property.LOCATION).ifPresent(prop -> builder.location(prop.getValue()));
        vEvent.getProperty(Property.DESCRIPTION).ifPresent(prop -> builder.description(prop.getValue()));
        vEvent.getProperty(Property.CLASS).ifPresent(prop -> builder.clazz(prop.getValue()));
        vEvent.getProperty(Property.DTSTART).ifPresent(prop -> {
            parseTime(prop).ifPresent(builder::start);
            builder.allDay(isDate(prop));
        });
        vEvent.getProperty(Property.DTEND).flatMap(EventFields::parseTime).ifPresent(builder::end);
        vEvent.getProperty(Property.DTSTAMP).flatMap(EventFields::parseTime).ifPresent(builder::dtStamp);
        isRecurrentMaster(vEvent).ifPresent(builder::isRecurrentMaster);
        builder.organizer(getOrganizer(vEvent));
        builder.attendees(getAttendees(vEvent));
        builder.resources(getResources(vEvent));

        return builder.build();
    }

    private static Optional<Boolean> isRecurrentMaster(VEvent vEvent) {
        if (vEvent.getProperty(Property.RECURRENCE_ID).isPresent()) {
            return Optional.of(false);
        }
        if (vEvent.getProperty(Property.RRULE).isPresent()) {
            return Optional.of(true);
        }
        return Optional.empty();
    }

    private static EventFields.Person getOrganizer(VEvent vEvent) {
        return vEvent.getProperty(Property.ORGANIZER)
            .flatMap(EventFields::toPerson)
            .orElse(null);
    }

    private static List<EventFields.Person> getAttendees(VEvent vEvent) {
        return getPeople(vEvent, GET_ATTENDEE);
    }

    private static List<EventFields.Person> getResources(VEvent vEvent) {
        return getPeople(vEvent, GET_RESOURCE);
    }

    private static List<EventFields.Person> getPeople(VEvent vEvent, boolean getResource) {
        return vEvent.getProperties(Property.ATTENDEE)
            .stream()
            .filter(attendee -> attendee.getParameter(Parameter.CUTYPE)
                .map(parameter -> getResource == "RESOURCE".equals(parameter.getValue()))
                .orElse(false))
            .map(EventFields::toPerson)
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<Instant> parseTime(Property property) {
        try {
            String value = property.getValue();
            if (isDate(property)) {
                return Optional.of(LocalDate.from(DATE_FORMATTER.parse(value)).atStartOfDay().toInstant(ZoneOffset.UTC));
            } else {
                return Optional.of(property.getParameter(Parameter.TZID)
                    .map(tzId -> TimeZone.getTimeZone(tzId.getValue()).toZoneId())
                    .map(zoneId -> LocalDateTime.parse(value, DATE_TIME_FORMATTER).atZone(zoneId).toInstant())
                    .orElseGet(() -> Instant.from(UTC_DATE_TIME_FORMATTER.parse(value))));
            }
        } catch (Exception e) {
            LOGGER.info("Failed to parse time: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isDate(Property prop) {
        return prop.getParameter(Parameter.VALUE).map(parameter -> "DATE".equals(parameter.getValue())).orElse(false);
    }

    private static Optional<EventFields.Person> toPerson(Property property)  {
        try {
            String cn = property.getParameter(Parameter.CN).map(Parameter::getValue).orElse("");
            String email = StringUtils.removeStartIgnoreCase(property.getValue(), "mailto:");
            Optional<PartStat> partStat = property.getParameter(Parameter.PARTSTAT)
                .map(value -> (PartStat) value);
            return Optional.of(new EventFields.Person(cn, new MailAddress(email), partStat));
        } catch (AddressException e) {
            LOGGER.info("Invalid person: {}", property.getValue());
            return Optional.empty();
        }
    }
}
