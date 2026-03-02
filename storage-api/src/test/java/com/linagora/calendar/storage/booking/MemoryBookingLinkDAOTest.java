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

package com.linagora.calendar.storage.booking;

import java.time.Instant;

import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.BeforeEach;

public class MemoryBookingLinkDAOTest implements BookingLinkDAOContract {
    private MemoryBookingLinkDAO bookingLinkDAO;
    private UpdatableTickingClock clock;

    @BeforeEach
    void setup() {
        clock = new UpdatableTickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        bookingLinkDAO = new MemoryBookingLinkDAO(clock);
    }

    @Override
    public BookingLinkDAO testee() {
        return bookingLinkDAO;
    }

    @Override
    public UpdatableTickingClock clock() {
        return clock;
    }
}
