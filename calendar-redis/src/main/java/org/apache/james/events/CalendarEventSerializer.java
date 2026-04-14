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

package org.apache.james.events;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

import org.apache.james.core.Username;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarListChangedEvent;
import com.linagora.calendar.storage.CalendarListChangedEvent.ChangeType;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.EventBusAlarmEvent;
import com.linagora.calendar.storage.ImportEvent;
import com.linagora.calendar.storage.ImportEvent.ImportStatus;
import com.linagora.calendar.storage.model.ImportId;

public class CalendarEventSerializer implements EventSerializer {

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CalendarChangeDTO.class),
        @JsonSubTypes.Type(value = CalendarListChangedDTO.class),
        @JsonSubTypes.Type(value = ImportEventDTO.class),
        @JsonSubTypes.Type(value = AlarmEventDTO.class)
    })
    interface EventDTO {
    }

    record CalendarChangeDTO(String eventId, String username, String calendarUrl) implements EventDTO {
        public static CalendarChangeDTO from(CalendarChangeEvent event) {
            return new CalendarChangeDTO(event.getEventId().getId().toString(),
                event.getUsername().asString(),
                event.calendarURL().serialize());
        }

        public CalendarChangeEvent asEvent() {
            return new CalendarChangeEvent(Event.EventId.of(this.eventId()),
                CalendarURL.deserialize(this.calendarUrl()));
        }
    }

    record CalendarListChangedDTO(String eventId, String username, String calendarUrl, String changeType) implements EventDTO {
        public static CalendarListChangedDTO from(CalendarListChangedEvent event) {
            return new CalendarListChangedDTO(event.getEventId().getId().toString(),
                event.getUsername().asString(),
                event.calendarURL().serialize(),
                event.changeType().name());
        }

        public CalendarListChangedEvent asEvent() {
            return new CalendarListChangedEvent(Event.EventId.of(this.eventId()),
                Username.of(this.username()),
                CalendarURL.deserialize(this.calendarUrl()),
                ChangeType.valueOf(this.changeType));
        }
    }

    record ImportEventDTO(String eventId,
                          String importId,
                          String importURI,
                          String importType,
                          String status,
                          Optional<Integer> succeedCount,
                          Optional<Integer> failedCount) implements EventDTO {

        public static ImportEventDTO from(ImportEvent event) {
            return new ImportEventDTO(event.getEventId().getId().toString(),
                event.importId().value(),
                event.importURI().toASCIIString(),
                event.importType(),
                event.status().name().toLowerCase(Locale.US),
                event.succeedCount(),
                event.failedCount());
        }

        public ImportEvent asEvent() {
            Event.EventId id = Event.EventId.of(this.eventId());
            ImportId importId = new ImportId(this.importId());
            URI uri = URI.create(this.importURI());
            ImportStatus importStatus = ImportStatus.from(this.status());
            return new ImportEvent(id, importId, uri, this.importType(), importStatus, this.succeedCount(), this.failedCount());
        }
    }

    private final ObjectMapper objectMapper;

    public CalendarEventSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new Jdk8Module());
    }

    record AlarmEventDTO(String eventId, String username, String eventSummary, String eventURL, String eventStartTime) implements EventDTO {
        public static AlarmEventDTO from(EventBusAlarmEvent event) {
            return new AlarmEventDTO(
                event.getEventId().getId().toString(),
                event.getUsername().asString(),
                event.eventSummary(),
                event.eventURL(),
                event.eventStartTime().toString());
        }

        public EventBusAlarmEvent asEvent() {
            return new EventBusAlarmEvent(
                Event.EventId.of(this.eventId()),
                Username.of(this.username()),
                this.eventSummary(),
                this.eventURL(),
                Instant.parse(this.eventStartTime()));
        }
    }

    @Override
    public SerializationResult toJson(Event event) {
        try {
            Optional<String> maybeEventDTO = toDTO(event)
                .map(Throwing.function(objectMapper::writeValueAsString).sneakyThrow());
            return SerializationResult.of(maybeEventDTO, "Unexpected value: " + event);
        } catch (Exception e) {
            return new SerializationResult.Failure(e.getMessage());
        }
    }

    @Override
    public SerializationResult toJson(Collection<Event> event) {
        if (event.size() != 1) {
            return new SerializationResult.Failure("Not supported for multiple events, please serialize separately");
        }
        return toJson(event.iterator().next());
    }

    @Override
    public DeserializationResult asEvent(String serialized) {
        try {
            EventDTO eventDTO = objectMapper.readValue(serialized, EventDTO.class);
            return DeserializationResult.of(fromDTO(eventDTO), "Unexpected value: " + eventDTO);
        } catch (JsonProcessingException e) {
            return new DeserializationResult.Failure(e.getMessage());
        }
    }

    @Override
    public DeserializationResult asEvents(String serialized) {
        return asEvent(serialized);
    }

    private Optional<EventDTO> toDTO(Event event) {
        return Optional.ofNullable(switch (event) {
            case CalendarChangeEvent calendarChangeEvent -> CalendarChangeDTO.from(calendarChangeEvent);
            case CalendarListChangedEvent calendarListChangedEvent -> CalendarListChangedDTO.from(calendarListChangedEvent);
            case ImportEvent importEvent -> ImportEventDTO.from(importEvent);
            case EventBusAlarmEvent alarmEvent -> AlarmEventDTO.from(alarmEvent);
            default -> null;
        });
    }

    private Optional<Event> fromDTO(EventDTO eventDTO) {
        return Optional.ofNullable(switch (eventDTO) {
            case CalendarChangeDTO calendarChangeDTO -> calendarChangeDTO.asEvent();
            case CalendarListChangedDTO calendarListChangedDTO -> calendarListChangedDTO.asEvent();
            case ImportEventDTO importEventDTO -> importEventDTO.asEvent();
            case AlarmEventDTO alarmEventDTO -> alarmEventDTO.asEvent();
            default -> null;
        });
    }
}
