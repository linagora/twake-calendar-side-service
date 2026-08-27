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


package com.linagora.calendar.storage.unsent;

import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.BeforeEach;

class MemoryUnsentMailRepositoryTest implements UnsentMailRepositoryContract {
    private MemoryUnsentMailRepository testee;
    private UpdatableTickingClock clock;

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(NOW);
        testee = new MemoryUnsentMailRepository(clock);
    }

    @Override
    public UnsentMailRepository testee() {
        return testee;
    }

    @Override
    public UpdatableTickingClock clock() {
        return clock;
    }
}
