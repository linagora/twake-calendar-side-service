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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.storage.model.TeamCalendarId;

public record ActivityEvent(String id, String type, Instant time, String organization, Optional<String> subject,
                            Optional<String> actor, JsonNode data) {
    static final String PROVISIONED = "com.twake.calendar.space.provisioned.v1";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "twake://calendar";
    private static final String CALENDAR_KIND = "calendar";
    private static final String EVENT_TYPE_PREFIX = "com.twake.calendar.event.";
    private static final String VERSION_SUFFIX = ".v1";
    private static final String EVENT_OBJECT = "event";
    private static final String ATTENDEE_REASON = "attendee";
    private static final int MAX_PREVIEW_LENGTH = 280;
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

    // The card shows the state of the latest event of an object, so every event carries the whole state.
    public static ActivityEvent calendarEvent(String action, String organization, String actor, CalendarEventSnapshot event,
                                              ObjectNode extraState, List<String> recipients, String idSeed, Instant time) {
        String type = EVENT_TYPE_PREFIX + action + VERSION_SUFFIX;
        ObjectNode data = OBJECT_MAPPER.createObjectNode();
        data.putObject("object")
            .put("type", EVENT_OBJECT)
            .put("id", event.uid())
            .put("title", event.title())
            .putObject("container")
                .put("kind", CALENDAR_KIND)
                .put("id", event.teamCalendarId().value());
        event.location().ifPresent(location -> data.put("preview", StringUtils.left(location, MAX_PREVIEW_LENGTH)));
        ObjectNode state = data.putObject("state")
            .put("start", time(event.start(), event.allDay()));
        event.end().ifPresent(end -> state.put("end", time(end, event.allDay())));
        state.put("allDay", event.allDay());
        event.location().ifPresent(location -> state.put("location", location));
        CalendarEventSnapshot.Rsvp rsvp = event.rsvp();
        state.putObject("rsvp")
            .put("accepted", rsvp.accepted())
            .put("declined", rsvp.declined())
            .put("tentative", rsvp.tentative())
            .put("pending", rsvp.pending());
        state.setAll(extraState);
        if (!recipients.isEmpty()) {
            ArrayNode recipientsNode = data.putArray("recipients");
            recipients.forEach(email -> recipientsNode.addObject()
                .put("email", email)
                .put("reason", ATTENDEE_REASON));
        }
        return new ActivityEvent(nameBasedId(type, idSeed), type, time, organization, Optional.of(EVENT_OBJECT + "/" + event.uid()),
            Optional.of(actor), data);
    }

    // All day events are dates: their start and end carry no time zone.
    static String time(Instant instant, boolean allDay) {
        if (allDay) {
            return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
        }
        return instant.toString();
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
