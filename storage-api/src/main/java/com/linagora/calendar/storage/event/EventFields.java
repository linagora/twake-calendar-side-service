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
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.Property;
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
        EventParseUtils.getSummary(vEvent).ifPresent(builder::summary);
        EventParseUtils.getLocation(vEvent).ifPresent(builder::location);
        EventParseUtils.getDescription(vEvent).ifPresent(builder::description);
        vEvent.getProperty(Property.CLASS).ifPresent(prop -> builder.clazz(prop.getValue()));

        builder.start(EventParseUtils.getStartTime(vEvent).toInstant());
        builder.allDay(EventParseUtils.isAllDay(vEvent));

        EventParseUtils.getEndTime(vEvent).map(ChronoZonedDateTime::toInstant).ifPresent(builder::end);
        vEvent.getProperty(Property.DTSTAMP).flatMap(EventParseUtils::parseTimeAsInstant).ifPresent(builder::dtStamp);
        EventParseUtils.isRecurrentMaster(vEvent).ifPresent(builder::isRecurrentMaster);
        builder.organizer(EventParseUtils.getOrganizer(vEvent));
        builder.attendees(EventParseUtils.getAttendees(vEvent));
        builder.resources(EventParseUtils.getResources(vEvent));
        return builder.build();
    }


}
