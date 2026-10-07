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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.model.TeamCalendarId;

class CalendarEventSnapshotTest {
    private static final CalendarURL CALENDAR = new CalendarURL(new OpenPaaSId("team-1"), new OpenPaaSId("team-1"));
    private static final CalendarEventSnapshot SNAPSHOT = new CalendarEventSnapshot("uid-1", new TeamCalendarId("team-1"), "Sprint planning",
        Instant.parse("2026-10-10T09:00:00Z"), Optional.of(Instant.parse("2026-10-10T10:00:00Z")), false, Optional.of("Room 1"),
        Optional.of("alice@space.tld"), Map.of("alice@space.tld", "ACCEPTED", "bob@space.tld", "NEEDS-ACTION",
            "carol@space.tld", "DECLINED", "dave@space.tld", "TENTATIVE", "erin@space.tld", "DELEGATED"));

    @Test
    void rsvpShouldCountTheAttendeesOtherThanTheOrganizer() {
        assertThat(SNAPSHOT.rsvp()).isEqualTo(new CalendarEventSnapshot.Rsvp(0, 1, 1, 2));
    }

    @Test
    void rescheduledShouldBeFalseWhenOnlyTheTitleChanges() {
        CalendarEventSnapshot renamed = new CalendarEventSnapshot("uid-1", SNAPSHOT.teamCalendarId(), "Retro", SNAPSHOT.start(),
            SNAPSHOT.end(), false, SNAPSHOT.location(), SNAPSHOT.organizer(), SNAPSHOT.attendees());

        assertThat(renamed.rescheduledFrom(SNAPSHOT)).isFalse();
    }

    @Test
    void rescheduledShouldBeTrueWhenTheEndChanges() {
        CalendarEventSnapshot longer = new CalendarEventSnapshot("uid-1", SNAPSHOT.teamCalendarId(), SNAPSHOT.title(), SNAPSHOT.start(),
            Optional.of(Instant.parse("2026-10-10T11:00:00Z")), false, SNAPSHOT.location(), SNAPSHOT.organizer(), SNAPSHOT.attendees());

        assertThat(longer.rescheduledFrom(SNAPSHOT)).isTrue();
    }

    @Test
    void partStatChangesShouldListTheAttendeesWhoseAnswerChanged() {
        assertThat(bobAccepted(SNAPSHOT.title()).partStatChangesFrom(SNAPSHOT)).isEqualTo(Map.of("bob@space.tld", "ACCEPTED"));
    }

    @Test
    void onlyPartStatsChangedShouldBeFalseWhenTheTitleChangesToo() {
        assertThat(bobAccepted("Retro").onlyPartStatsChangedFrom(SNAPSHOT)).isFalse();
        assertThat(bobAccepted(SNAPSHOT.title()).onlyPartStatsChangedFrom(SNAPSHOT)).isTrue();
    }

    @Test
    void attendeeEmailsShouldBeComparedIgnoringCase() {
        CalendarEventSnapshot snapshot = new CalendarEventSnapshot("uid-1", SNAPSHOT.teamCalendarId(), SNAPSHOT.title(), SNAPSHOT.start(),
            SNAPSHOT.end(), false, SNAPSHOT.location(), Optional.of("Alice@Space.TLD"), Map.of("Bob@Space.TLD", "ACCEPTED"));

        assertThat(snapshot.attendees()).containsEntry("bob@space.tld", "ACCEPTED");
        assertThat(snapshot.organizer()).contains("alice@space.tld");
    }

    @Test
    void fromJCalShouldReadTheMasterEventWithTheAttendeeAnswers() throws Exception {
        JsonNode jCal = new ObjectMapper().readTree("""
            ["vcalendar", [], [
              ["vevent", [
                ["uid", {}, "text", "uid-1"],
                ["recurrence-id", {}, "date-time", "2026-10-17T09:00:00Z"],
                ["dtstart", {}, "date-time", "2026-10-17T09:00:00Z"],
                ["summary", {}, "text", "Moved occurrence"]
              ], []],
              ["vevent", [
                ["uid", {}, "text", "uid-1"],
                ["dtstart", {}, "date-time", "2026-10-10T09:00:00Z"],
                ["dtend", {}, "date-time", "2026-10-10T10:00:00Z"],
                ["summary", {}, "text", "Sprint planning"],
                ["location", {}, "text", "Room 1"],
                ["organizer", {"cn": "Alice"}, "cal-address", "mailto:alice@space.tld"],
                ["attendee", {"partstat": "ACCEPTED"}, "cal-address", "mailto:alice@space.tld"],
                ["attendee", {"partstat": "DECLINED"}, "cal-address", "mailto:Bob@space.tld"],
                ["attendee", {}, "cal-address", "mailto:carol@space.tld"],
                ["attendee", {"partstat": "ACCEPTED", "cutype": "RESOURCE"}, "cal-address", "mailto:room@space.tld"]
              ], []]
            ]]""");

        assertThat(CalendarEventSnapshot.fromJCal(CALENDAR, "uid-1.ics", jCal))
            .contains(new CalendarEventSnapshot("uid-1", SNAPSHOT.teamCalendarId(), "Sprint planning", SNAPSHOT.start(), SNAPSHOT.end(),
                false, SNAPSHOT.location(), SNAPSHOT.organizer(),
                Map.of("alice@space.tld", "ACCEPTED", "bob@space.tld", "DECLINED", "carol@space.tld", "NEEDS-ACTION")));
    }

    @Test
    void fromJCalShouldBeEmptyWithoutAnEvent() throws Exception {
        assertThat(CalendarEventSnapshot.fromJCal(CALENDAR, "uid-1.ics",
            new ObjectMapper().readTree("[\"vcalendar\", [], []]"))).isEmpty();
    }

    private static CalendarEventSnapshot bobAccepted(String title) {
        Map<String, String> attendees = new HashMap<>(SNAPSHOT.attendees());
        attendees.put("bob@space.tld", "ACCEPTED");
        return new CalendarEventSnapshot("uid-1", SNAPSHOT.teamCalendarId(), title, SNAPSHOT.start(), SNAPSHOT.end(), false,
            SNAPSHOT.location(), SNAPSHOT.organizer(), attendees);
    }
}
