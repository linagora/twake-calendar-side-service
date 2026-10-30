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

package com.linagora.calendar.twakespace.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.Streams;
import com.linagora.calendar.amqp.EventFieldConverter;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.model.TeamCalendarId;

import net.fortuna.ical4j.model.parameter.PartStat;

// What a card shows of a version of an event of a team calendar.
public record CalendarEventSnapshot(String uid, TeamCalendarId teamCalendarId, String title, Instant start, Optional<Instant> end,
                                    boolean allDay, Optional<String> location, Optional<String> videoconference, Optional<String> organizer,
                                    Map<String, String> attendees) {
    public record Rsvp(long accepted, long declined, long tentative, long pending) {
    }

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    static final String TENTATIVE = "TENTATIVE";
    private static final String NEEDS_ACTION = "NEEDS-ACTION";
    private static final String MAILTO = "mailto:";

    public static CalendarEventSnapshot from(TeamCalendarId teamCalendarId, EventFields event) {
        return new CalendarEventSnapshot(event.uid().value(), teamCalendarId, Objects.requireNonNullElse(event.summary(), ""),
            event.start(), Optional.ofNullable(event.end()), Boolean.TRUE.equals(event.allDay()), Optional.ofNullable(event.location()),
            Optional.ofNullable(StringUtils.trimToNull(event.videoconferenceUrl())),
            Optional.ofNullable(event.organizer()).map(organizer -> organizer.email().asString()),
            event.attendees().stream().collect(Collectors.toMap(attendee -> attendee.email().asString(),
                attendee -> attendee.partStat().map(PartStat::getValue).orElse(NEEDS_ACTION),
                (first, second) -> first)));
    }

    // Sabre sends the event as jCal, of which EventFieldConverter reads no attendee answer.
    public static Optional<CalendarEventSnapshot> fromJCal(CalendarURL calendar, String resourceName, JsonNode jCal) {
        TeamCalendarId teamCalendarId = TeamCalendarId.from(calendar.calendarId());
        List<JsonNode> events = Streams.stream(jCal.path(2).elements())
            .filter(component -> "vevent".equalsIgnoreCase(component.path(0).asText()))
            .toList();
        return events.stream()
            .filter(event -> properties(event, "recurrence-id").findAny().isEmpty())
            .findFirst()
            .or(() -> events.stream().findFirst())
            .map(event -> {
                ArrayNode calendarOfEvent = JsonNodeFactory.instance.arrayNode().add("vcalendar");
                calendarOfEvent.addArray();
                calendarOfEvent.addArray().add(event);
                EventFields fields = EventFieldConverter.from(EventFieldConverter.extractVEventProperties(calendarOfEvent).getFirst())
                    .calendarURL(calendar)
                    .resourceName(resourceName)
                    .build();
                Map<String, String> answers = properties(event, "attendee")
                    .filter(attendee -> !"RESOURCE".equalsIgnoreCase(attendee.path(1).path("cutype").asText()))
                    .collect(Collectors.toMap(attendee -> StringUtils.removeStartIgnoreCase(attendee.path(3).asText(), MAILTO),
                        attendee -> attendee.path(1).path("partstat").asText(NEEDS_ACTION),
                        (first, second) -> first));
                CalendarEventSnapshot snapshot = from(teamCalendarId, fields);
                return new CalendarEventSnapshot(snapshot.uid, teamCalendarId, snapshot.title, snapshot.start, snapshot.end, snapshot.allDay,
                    snapshot.location, snapshot.videoconference, snapshot.organizer, answers);
            });
    }

    public CalendarEventSnapshot {
        attendees = attendees.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(entry -> normalize(entry.getKey()), Map.Entry::getValue, (first, second) -> first));
        organizer = organizer.map(CalendarEventSnapshot::normalize);
    }

    public CalendarEventSnapshot withPartStat(String attendee, String partStat) {
        Map<String, String> answered = new HashMap<>(attendees);
        answered.put(normalize(attendee), partStat);
        return new CalendarEventSnapshot(uid, teamCalendarId, title, start, end, allDay, location, videoconference, organizer, answered);
    }

    public boolean rescheduledFrom(CalendarEventSnapshot previous) {
        return !start.equals(previous.start) || !end.equals(previous.end) || allDay != previous.allDay;
    }

    public Map<String, String> partStatChangesFrom(CalendarEventSnapshot previous) {
        return attendees.entrySet().stream()
            .filter(entry -> previous.attendees.containsKey(entry.getKey()))
            .filter(entry -> !entry.getValue().equals(previous.attendees.get(entry.getKey())))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public boolean onlyPartStatsChangedFrom(CalendarEventSnapshot previous) {
        return !partStatChangesFrom(previous).isEmpty()
            && withPartStatsOf(previous).equals(previous);
    }

    public Rsvp rsvp() {
        Map<String, Long> counts = attendees.entrySet().stream()
            .filter(entry -> organizer.map(email -> !email.equals(entry.getKey())).orElse(true))
            .collect(Collectors.groupingBy(entry -> switch (entry.getValue()) {
                case ACCEPTED, DECLINED, TENTATIVE -> entry.getValue();
                default -> NEEDS_ACTION;
            }, Collectors.counting()));
        return new Rsvp(counts.getOrDefault(ACCEPTED, 0L), counts.getOrDefault(DECLINED, 0L),
            counts.getOrDefault(TENTATIVE, 0L), counts.getOrDefault(NEEDS_ACTION, 0L));
    }

    private CalendarEventSnapshot withPartStatsOf(CalendarEventSnapshot other) {
        Map<String, String> partStats = new HashMap<>(attendees);
        other.attendees.forEach((attendee, partStat) -> partStats.computeIfPresent(attendee, (key, value) -> partStat));
        return new CalendarEventSnapshot(uid, teamCalendarId, title, start, end, allDay, location, videoconference, organizer, partStats);
    }

    private static Stream<JsonNode> properties(JsonNode event, String name) {
        return Streams.stream(event.path(1).elements())
            .filter(property -> name.equalsIgnoreCase(property.path(0).asText()));
    }

    private static String normalize(String email) {
        return email.toLowerCase(Locale.US);
    }
}
