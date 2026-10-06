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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.model.TeamCalendarId;

class CalendarEventSnapshotTest {
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
        CalendarEventSnapshot answered = SNAPSHOT.withPartStat("bob@space.tld", "ACCEPTED");

        assertThat(answered.partStatChangesFrom(SNAPSHOT)).isEqualTo(Map.of("bob@space.tld", "ACCEPTED"));
    }

    @Test
    void onlyPartStatsChangedShouldBeFalseWhenTheTitleChangesToo() {
        CalendarEventSnapshot renamed = new CalendarEventSnapshot("uid-1", SNAPSHOT.teamCalendarId(), "Retro", SNAPSHOT.start(),
            SNAPSHOT.end(), false, SNAPSHOT.location(), SNAPSHOT.organizer(), SNAPSHOT.withPartStat("bob@space.tld", "ACCEPTED").attendees());

        assertThat(renamed.onlyPartStatsChangedFrom(SNAPSHOT)).isFalse();
        assertThat(SNAPSHOT.withPartStat("bob@space.tld", "ACCEPTED").onlyPartStatsChangedFrom(SNAPSHOT)).isTrue();
    }

    @Test
    void attendeeEmailsShouldBeComparedIgnoringCase() {
        assertThat(SNAPSHOT.withPartStat("Bob@Space.TLD", "ACCEPTED").attendees()).containsEntry("bob@space.tld", "ACCEPTED");
    }
}
