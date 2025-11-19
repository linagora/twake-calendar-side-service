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

import java.util.Optional;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.CalendarURL;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarAlarmMessageDTO(@JsonProperty("eventPath") String eventPath,
                                      @JsonProperty("event") JsonNode calendarEvent,
                                      @JsonProperty("rawEvent") Optional<String> rawEvent,
                                      @JsonProperty("import") boolean isImport) {

    public static boolean hasVALARMComponent(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false)
                .anyMatch(child -> ("valarm".equalsIgnoreCase(child.path(0).asText()))
                    || hasVALARMComponent(child));
        }

        if (node.isObject()) {
            return StreamSupport.stream(node.spliterator(), false)
                .anyMatch(CalendarAlarmMessageDTO::hasVALARMComponent);
        }
        return false;
    }

    public CalendarURL extractCalendarURL() {
        return EventFieldConverter.extractCalendarURL(eventPath);
    }

    public boolean hasVALARMComponent() {
        return hasVALARMComponent(calendarEvent);
    }
}
