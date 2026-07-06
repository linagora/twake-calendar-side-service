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

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DefaultAuditLogger implements AuditLogger {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> MESSAGE_TEMPLATES = Map.ofEntries(
        Map.entry("sabre:contact:created", "Contact created"),
        Map.entry("sabre:contact:deleted", "Contact deleted"),
        Map.entry("sabre:contact:updated", "Contact updated"),
        Map.entry("sabre:contact:update", "Contact updated"),
        Map.entry("calendar:subscription:created", "Calendar subscription created"),
        Map.entry("calendar:subscription:deleted", "Calendar subscription deleted"),
        Map.entry("calendar:subscription:updated", "Calendar subscription updated"),
        Map.entry("calendar:calendar:created", "Calendar created"),
        Map.entry("calendar:calendar:deleted", "Calendar deleted"),
        Map.entry("calendar:calendar:updated", "Calendar updated"),
        Map.entry("calendar:event:created", "Calendar event created"),
        Map.entry("calendar:event:updated", "Calendar event updated"),
        Map.entry("calendar:event:deleted", "Calendar event deleted"),
        Map.entry("calendar:event:request", "Calendar event (iTIP request)"),
        Map.entry("calendar:event:cancel", "Calendar event (iTIP cancel)"),
        Map.entry("calendar:event:reply", "Calendar event (iTIP reply)"),
        Map.entry("sabre:addressbook:created", "Address Book created"),
        Map.entry("sabre:addressbook:deleted", "Address Book deleted"),
        Map.entry("sabre:addressbook:updated", "Address Book updated"),
        Map.entry("sabre:addressbook:subscription:created", "Address Book subscription created"),
        Map.entry("sabre:addressbook:subscription:deleted", "Address Book subscription deleted"),
        Map.entry("sabre:addressbook:subscription:updated", "Address Book subscription updated"));

    @Override
    public String format(String body, String exchangeName) {
        String message = MESSAGE_TEMPLATES.getOrDefault(exchangeName, "Unknown event");
        Optional<String> uid = extractUid(body);
        Optional<String> path = extractPath(body);
        StringBuilder sb = new StringBuilder(message);
        uid.ifPresent(u -> sb.append(" (uid=").append(u).append(")"));
        path.ifPresent(p -> sb.append(" [").append(p).append("]"));
        return sb.toString();
    }

    private static Optional<String> extractUid(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.has("uid")) {
                return Optional.ofNullable(root.get("uid").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> extractPath(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.has("eventPath")) {
                return Optional.ofNullable(root.get("eventPath").asText(null));
            }
            if (root.has("calendarPath")) {
                return Optional.ofNullable(root.get("calendarPath").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}