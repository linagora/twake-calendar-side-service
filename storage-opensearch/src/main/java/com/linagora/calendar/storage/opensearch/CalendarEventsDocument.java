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

package com.linagora.calendar.storage.opensearch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.vacation.api.AccountId;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.opensearch.CalendarEventIndexMappingFactory.CalendarFields;

public record CalendarEventsDocument(@JsonProperty(CalendarFields.ACCOUNT_ID) String accountId,
                                     @JsonProperty(CalendarFields.EVENT_UID) String eventUid,
                                     @JsonProperty(CalendarFields.SUMMARY) String summary,
                                     @JsonProperty(CalendarFields.LOCATION) String location,
                                     @JsonProperty(CalendarFields.DESCRIPTION) String description,
                                     @JsonProperty(CalendarFields.CLAZZ) String clazz,
                                     @JsonProperty(CalendarFields.START) Instant start,
                                     @JsonProperty(CalendarFields.END) Instant end,
                                     @JsonProperty(CalendarFields.DTSTAMP) Instant dtStamp,
                                     @JsonProperty(CalendarFields.ALL_DAY) Boolean allDay,
                                     @JsonProperty(CalendarFields.IS_RECURRENT_MASTER) Boolean isRecurrentMaster,
                                     @JsonProperty(CalendarFields.ORGANIZER) SimplePerson organizer,
                                     @JsonProperty(CalendarFields.ATTENDEES) List<SimplePerson> attendees,
                                     @JsonProperty(CalendarFields.RESOURCES) List<SimplePerson> resources,
                                     @JsonProperty(CalendarFields.VIDEOCONFERENCE_URL) String videoconferenceUrl,
                                     @JsonProperty(CalendarFields.CALENDAR_URL) String calendarURL,
                                     @JsonProperty(CalendarFields.SEQUENCE) Integer sequence) {

    public static class DeserializeException extends RuntimeException {
        public DeserializeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record SimplePerson(@JsonProperty(CalendarFields.CN) String name,
                               @JsonProperty(CalendarFields.EMAIL) String email) {
        public static SimplePerson from(EventFields.Person person) {
            if (person == null) {
                return null;
            }
            return new SimplePerson(person.cn(), person.email().asString());
        }

        public EventFields.Person toEventPerson() {
            try {
                return EventFields.Person.of(name, email);
            } catch (AddressException e) {
                throw new DeserializeException("Failed to deserialize SimplePerson, invalid email: " + email, e);
            }
        }
    }

    public static CalendarEventsDocument fromEventFields(AccountId accountId, EventFields eventFields) {
        return new CalendarEventsDocument(
            accountId.getIdentifier(),
            eventFields.uid().value(),
            eventFields.summary(),
            eventFields.location(),
            eventFields.description(),
            eventFields.clazz(),
            eventFields.start(),
            eventFields.end(),
            eventFields.dtStamp(),
            eventFields.allDay(),
            eventFields.isRecurrentMaster(),
            SimplePerson.from(eventFields.organizer()),
            eventFields.attendees().stream()
                .map(SimplePerson::from)
                .toList(),
            eventFields.resources().stream()
                .map(SimplePerson::from)
                .toList(),
            eventFields.videoconferenceUrl(),
            eventFields.calendarURL().serialize(),
            eventFields.sequence().orElse(null));
    }

    public EventFields toEventFields() {
        EventFields.Builder builder = EventFields.builder()
            .uid(eventUid)
            .summary(summary)
            .location(location)
            .description(description)
            .clazz(clazz)
            .start(start)
            .end(end)
            .dtStamp(dtStamp)
            .organizer(Optional.ofNullable(organizer).map(SimplePerson::toEventPerson).orElse(null))
            .attendees(Optional.ofNullable(attendees).orElse(List.of())
                .stream()
                .map(SimplePerson::toEventPerson)
                .toList())
            .resources(Optional.ofNullable(resources).orElse(List.of())
                .stream()
                .map(SimplePerson::toEventPerson)
                .toList())
            .videoconferenceUrl(videoconferenceUrl)
            .calendarURL(CalendarURL.deserialize(calendarURL));

        if (allDay != null) {
            builder.allDay(allDay);
        }
        if (isRecurrentMaster != null) {
            builder.isRecurrentMaster(isRecurrentMaster);
        }
        if (sequence != null) {
            builder.sequence(sequence);
        }

        return builder.build();
    }
}
