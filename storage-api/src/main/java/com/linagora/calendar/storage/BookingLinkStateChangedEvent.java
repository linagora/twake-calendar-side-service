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

import org.apache.james.core.Username;
import org.apache.james.events.Event;

/**
 * Signals that the booking links owned by {@code username} changed, without telling how.
 * Clients are expected to re-fetch the full booking link list upon reception, which makes
 * the notification tolerant to message drops and to out of order delivery.
 */
public record BookingLinkStateChangedEvent(Event.EventId eventId, Username username) implements Event {

    public static BookingLinkStateChangedEvent of(Username username) {
        return new BookingLinkStateChangedEvent(Event.EventId.random(), username);
    }

    @Override
    public Username getUsername() {
        return username;
    }

    @Override
    public boolean isNoop() {
        return false;
    }

    @Override
    public EventId getEventId() {
        return eventId;
    }
}
