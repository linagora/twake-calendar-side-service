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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.linagora.calendar.dav.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarResourceMessageDTO(@JsonProperty("resourceId") String resourceId,
                                         @JsonProperty("eventId") @JsonDeserialize(using = EventIdDeserializer.class) String eventId,
                                         @JsonProperty("eventPath") String eventPath,
                                         @JsonProperty("ics") @JsonDeserialize(using = IcsDeserializer.class) Calendar ics) {

    public static class EventIdDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String eventId = p.getValueAsString();
            return eventId.replaceAll("\\.ics$", "");
        }
    }


    public static class IcsDeserializer extends JsonDeserializer<Calendar> {
        @Override
        public Calendar deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String icsString = p.getValueAsString();
            return CalendarUtil.parseIcs(icsString);
        }
    }
}
