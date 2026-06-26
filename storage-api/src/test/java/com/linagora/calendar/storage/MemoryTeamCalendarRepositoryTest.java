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

package com.linagora.calendar.storage;

import java.time.Instant;
import java.util.UUID;

import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.BeforeEach;

import com.linagora.calendar.storage.model.TeamCalendarId;

public class MemoryTeamCalendarRepositoryTest implements TeamCalendarRepositoryContract {
    private MemoryTeamCalendarRepository repository;
    private UpdatableTickingClock clock;

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        repository = new MemoryTeamCalendarRepository(clock);
    }

    @Override
    public TeamCalendarRepository testee() {
        return repository;
    }

    @Override
    public UpdatableTickingClock clock() {
        return clock;
    }

    @Override
    public TeamCalendarId generateTeamCalendarId() {
        return new TeamCalendarId(UUID.randomUUID().toString());
    }
}
