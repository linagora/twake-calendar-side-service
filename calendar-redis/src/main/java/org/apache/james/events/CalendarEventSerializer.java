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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.ImportEvent;
import com.linagora.calendar.storage.ImportEvent.ImportStatus;
import com.linagora.calendar.storage.model.ImportId;

public class CalendarEventSerializer implements EventSerializer {

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CalendarChangeDTO.class),
        @JsonSubTypes.Type(value = ImportEventDTO.class),
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

    @Override
    public String toJson(Event event) {
        try {
            EventDTO eventDTO = toDTO(event);
            return objectMapper.writeValueAsString(eventDTO);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toJson(Collection<Event> event) {
        if (event.size() != 1) {
            throw new IllegalArgumentException("Not supported for multiple events, please serialize separately");
        }
        return toJson(event.iterator().next());
    }

    @Override
    public Event asEvent(String serialized) {
        try {
            EventDTO eventDTO = objectMapper.readValue(serialized, EventDTO.class);
            return fromDTO(eventDTO);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Event> asEvents(String serialized) {
        return ImmutableList.of(asEvent(serialized));
    }

    private EventDTO toDTO(Event event) {
        return switch (event) {
            case CalendarChangeEvent calendarChangeEvent -> CalendarChangeDTO.from(calendarChangeEvent);
            case ImportEvent importEvent -> ImportEventDTO.from(importEvent);
            default -> throw new IllegalArgumentException("Unsupported event type: " + event.getClass());
        };
    }

    private Event fromDTO(EventDTO eventDTO) {
        return switch (eventDTO) {
            case CalendarChangeDTO calendarChangeDTO -> calendarChangeDTO.asEvent();
            case ImportEventDTO importEventDTO -> importEventDTO.asEvent();
            default -> throw new IllegalArgumentException("Unsupported event DTO type: " + eventDTO.getClass());
        };
    }
}
