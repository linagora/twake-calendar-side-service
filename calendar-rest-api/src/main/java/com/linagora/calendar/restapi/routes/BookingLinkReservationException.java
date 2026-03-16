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

package com.linagora.calendar.restapi.routes;

import java.time.Instant;

import com.linagora.calendar.storage.booking.BookingLinkPublicId;

public class BookingLinkReservationException extends RuntimeException {
    public static BookingLinkReservationException notAvailable(BookingLinkPublicId bookingLinkPublicId, Instant slotStartUtc) {
        return new SlotNotAvailableException("Requested slot is not available for booking link with publicId "
            + bookingLinkPublicId.value() + ", slotStartUtc " + slotStartUtc.toString());
    }

    public static BookingLinkReservationException computationFailed(BookingLinkPublicId bookingLinkPublicId, Throwable cause) {
        return new ReservationOperationFailedException("Failed to compute slots for booking link with publicId " + bookingLinkPublicId.value(), cause);
    }

    public static BookingLinkReservationException createEventFailed(BookingLinkPublicId bookingLinkPublicId, String eventId, Throwable cause) {
        return new ReservationOperationFailedException("Failed to create reservation event for booking link with publicId " + bookingLinkPublicId.value() + ", eventId " + eventId, cause);
    }

    protected BookingLinkReservationException(String message) {
        super(message);
    }

    protected BookingLinkReservationException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class SlotNotAvailableException extends BookingLinkReservationException {
        private SlotNotAvailableException(String message) {
            super(message);
        }
    }

    public static class ReservationOperationFailedException extends BookingLinkReservationException {
        private ReservationOperationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
