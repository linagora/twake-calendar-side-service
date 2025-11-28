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

import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;

public class CalendarEventSerializer implements EventSerializer {

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = CalendarChangeDTO.class)
    })
    interface EventDTO {
    }

    record CalendarChangeDTO(String eventId, String username, String calendarUrl) implements EventDTO {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            case CalendarChangeEvent calendarChangeEvent ->
                new CalendarChangeDTO(
                    calendarChangeEvent.getEventId().getId().toString(),
                    calendarChangeEvent.getUsername().asString(),
                    calendarChangeEvent.calendarURL().serialize());
            default -> throw new IllegalArgumentException("Unsupported event type: " + event.getClass());
        };
    }

    private Event fromDTO(EventDTO eventDTO) {
        return switch (eventDTO) {
            case CalendarChangeDTO calendarChangeDTO -> new CalendarChangeEvent(
                Event.EventId.of(calendarChangeDTO.eventId),
                CalendarURL.deserialize(calendarChangeDTO.calendarUrl));
            default -> throw new IllegalArgumentException("Unsupported event DTO type: " + eventDTO.getClass());
        };
    }
}
