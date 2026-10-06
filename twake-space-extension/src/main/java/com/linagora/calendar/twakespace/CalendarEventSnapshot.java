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

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.model.TeamCalendarId;

import net.fortuna.ical4j.model.parameter.PartStat;

// What a card shows of an event of a team calendar, kept to tell what the next change of the event is.
public record CalendarEventSnapshot(String uid, TeamCalendarId teamCalendarId, String title, Instant start, Optional<Instant> end,
                                    boolean allDay, Optional<String> location, Optional<String> organizer, Map<String, String> attendees) {
    public record Rsvp(long accepted, long declined, long tentative, long pending) {
    }

    static final String ACCEPTED = "ACCEPTED";
    static final String DECLINED = "DECLINED";
    static final String TENTATIVE = "TENTATIVE";
    private static final String NEEDS_ACTION = "NEEDS-ACTION";

    public static CalendarEventSnapshot from(TeamCalendarId teamCalendarId, EventFields event) {
        return new CalendarEventSnapshot(event.uid().value(), teamCalendarId, Objects.requireNonNullElse(event.summary(), ""),
            event.start(), Optional.ofNullable(event.end()), Boolean.TRUE.equals(event.allDay()), Optional.ofNullable(event.location()),
            Optional.ofNullable(event.organizer()).map(organizer -> organizer.email().asString()),
            event.attendees().stream().collect(Collectors.toMap(attendee -> attendee.email().asString(),
                attendee -> attendee.partStat().map(PartStat::getValue).orElse(NEEDS_ACTION),
                (first, second) -> first)));
    }

    public CalendarEventSnapshot {
        attendees = attendees.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(entry -> normalize(entry.getKey()), Map.Entry::getValue, (first, second) -> first));
        organizer = organizer.map(CalendarEventSnapshot::normalize);
    }

    public CalendarEventSnapshot withPartStat(String attendee, String partStat) {
        Map<String, String> answered = new HashMap<>(attendees);
        answered.put(normalize(attendee), partStat);
        return new CalendarEventSnapshot(uid, teamCalendarId, title, start, end, allDay, location, organizer, answered);
    }

    public Optional<String> partStat(String attendee) {
        return Optional.ofNullable(attendees.get(normalize(attendee)));
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
        return new CalendarEventSnapshot(uid, teamCalendarId, title, start, end, allDay, location, organizer, partStats);
    }

    private static String normalize(String email) {
        return email.toLowerCase(Locale.US);
    }
}
