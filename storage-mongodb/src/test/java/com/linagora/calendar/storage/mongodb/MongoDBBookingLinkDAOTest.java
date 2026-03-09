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

package com.linagora.calendar.storage.mongodb;

import java.time.Instant;
import java.util.List;

import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkDAOContract;

public class MongoDBBookingLinkDAOTest implements BookingLinkDAOContract {

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(
        List.of(MongoDBBookingLinkDAO.COLLECTION));

    private MongoDBBookingLinkDAO bookingLinkDAO;
    private UpdatableTickingClock clock;

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        bookingLinkDAO = new MongoDBBookingLinkDAO(mongo.getDb(), clock);
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
