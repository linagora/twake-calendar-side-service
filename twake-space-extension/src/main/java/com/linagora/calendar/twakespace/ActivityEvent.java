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

package com.linagora.calendar.twakespace;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.storage.model.TeamCalendarId;

public record ActivityEvent(String id, String type, Instant time, String organization, Optional<String> subject,
                            Optional<String> actor, JsonNode data) {
    static final String PROVISIONED = "com.twake.calendar.space.provisioned.v1";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "twake://calendar";
    private static final String CALENDAR_KIND = "calendar";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Named after the space and its calendar, so that a redelivered created publishes the same event.
    public static ActivityEvent provisioned(String organization, String spaceId, TeamCalendarId teamCalendarId, Instant time) {
        ObjectNode data = OBJECT_MAPPER.createObjectNode().put("space_id", spaceId);
        data.putObject("resource")
            .put("kind", CALENDAR_KIND)
            .put("id", teamCalendarId.value());
        return new ActivityEvent(nameBasedId(PROVISIONED, spaceId, teamCalendarId.value()), PROVISIONED, time, organization,
            Optional.empty(), Optional.empty(), data);
    }

    private static String nameBasedId(String... names) {
        return UUID.nameUUIDFromBytes(String.join(":", names).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public byte[] serialize() {
        ObjectNode event = OBJECT_MAPPER.createObjectNode()
            .put("specversion", SPEC_VERSION)
            .put("id", id)
            .put("source", SOURCE)
            .put("type", type)
            .put("time", time.toString())
            .put("twakeorg", organization);
        subject.ifPresent(value -> event.put("subject", value));
        actor.ifPresent(value -> event.put("twakeactor", value));
        event.set("data", data);
        try {
            return OBJECT_MAPPER.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize " + type, e);
        }
    }
}
