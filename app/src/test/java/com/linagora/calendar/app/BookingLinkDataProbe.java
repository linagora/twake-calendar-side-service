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

package com.linagora.calendar.app;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;

import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

public class BookingLinkDataProbe implements GuiceProbe {
    private final BookingLinkDAO bookingLinkDAO;

    @Inject
    public BookingLinkDataProbe(BookingLinkDAO bookingLinkDAO) {
        this.bookingLinkDAO = bookingLinkDAO;
    }

    public BookingLink findBookingLink(Username username, BookingLinkPublicId publicId) {
        return bookingLinkDAO.findByPublicId(username, publicId).block();
    }

    public List<BookingLink> listBookingLinks(Username username) {
        return bookingLinkDAO.findByUsername(username).collectList().block();
    }
}
