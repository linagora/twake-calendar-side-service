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

package com.linagora.calendar.amqp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.EventUid;

public abstract class CalendarEventMessage {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    protected final String eventPath;
    protected final JsonNode calendarEvent;
    protected final boolean isImport;

    public CalendarEventMessage(String eventPath,
                                JsonNode calendarEvent,
                                boolean isImport) {
        this.eventPath = eventPath;
        this.calendarEvent = calendarEvent;
        this.isImport = isImport;
    }

    public CalendarURL extractCalendarURL() {
        return EventFieldConverter.extractCalendarURL(eventPath);
    }

    public CalendarEvents extractCalendarEvents() {
        return EventFieldConverter.from(this);
    }

    protected static <T extends CalendarEventMessage> T deserialize(byte[] json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            throw new CalendarEventDeserializeException("Failed to deserialize " + clazz.getSimpleName() + " message: "
                + StringUtils.left(new String(json, StandardCharsets.UTF_8), 255), e);
        }
    }

    public static class CreatedOrUpdated extends CalendarEventMessage {

        public static CreatedOrUpdated deserialize(byte[] json) {
            return deserialize(json, CreatedOrUpdated.class);
        }

        public CreatedOrUpdated(@JsonProperty("eventPath") String eventPath,
                                @JsonProperty("event") JsonNode calendarEvent,
                                @JsonProperty("import") boolean isImport) {
            super(eventPath, calendarEvent, isImport);
        }
    }

    public static class Deleted extends CalendarEventMessage {
        public static Deleted deserialize(byte[] json) {
            return deserialize(json, Deleted.class);
        }

        public Deleted(@JsonProperty("eventPath") String eventPath,
                       @JsonProperty("event") JsonNode calendarEvent,
                       @JsonProperty("import") boolean isImport) {
            super(eventPath, calendarEvent, isImport);
        }

        public List<EventUid> extractEventUid() {
            return EventFieldConverter.extractVEventProperties(calendarEvent)
                .stream().flatMap(List::stream)
                .filter(property -> property instanceof EventProperty.EventUidProperty)
                .map(property -> ((EventProperty.EventUidProperty) property).getEventUid())
                .toList();
        }
    }
}
