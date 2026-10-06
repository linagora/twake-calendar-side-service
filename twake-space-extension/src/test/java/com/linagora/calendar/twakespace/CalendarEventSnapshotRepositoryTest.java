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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;

class CalendarEventSnapshotRepositoryTest {
    private static final CalendarEventSnapshot SNAPSHOT = new CalendarEventSnapshot("uid-1", new TeamCalendarId("team-1"), "Sprint planning",
        Instant.parse("2026-10-10T09:00:00Z"), Optional.of(Instant.parse("2026-10-10T10:00:00Z")), false, Optional.of("Room 1"),
        Optional.of("alice@space.tld"), Map.of("alice@space.tld", "ACCEPTED", "bob@space.tld", "NEEDS-ACTION"));

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(CalendarEventSnapshotRepository.COLLECTION));

    private CalendarEventSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CalendarEventSnapshotRepository(mongo.getDb());
    }

    @Test
    void retrieveShouldReturnTheSavedSnapshot() {
        repository.save(SNAPSHOT).block();

        assertThat(repository.retrieve("uid-1").block()).isEqualTo(SNAPSHOT);
    }

    @Test
    void retrieveShouldKeepAbsentOptionalFields() {
        CalendarEventSnapshot allDay = new CalendarEventSnapshot("uid-2", new TeamCalendarId("team-1"), "Offsite",
            Instant.parse("2026-10-10T00:00:00Z"), Optional.empty(), true, Optional.empty(), Optional.empty(), Map.of());
        repository.save(allDay).block();

        assertThat(repository.retrieve("uid-2").block()).isEqualTo(allDay);
    }

    @Test
    void retrieveShouldBeEmptyForAnUnknownEvent() {
        assertThat(repository.retrieve("uid-1").blockOptional()).isEmpty();
    }

    @Test
    void saveShouldReplaceThePreviousSnapshot() {
        repository.save(SNAPSHOT).block();
        CalendarEventSnapshot accepted = SNAPSHOT.withPartStat("bob@space.tld", "ACCEPTED");

        repository.save(accepted).block();

        assertThat(repository.retrieve("uid-1").block()).isEqualTo(accepted);
    }
}
