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

import java.util.Arrays;

/**
 * Visibility of the event created from a booking link, mapped onto the iCalendar CLASS property.
 */
public enum EventVisibility {
    PUBLIC,
    PRIVATE;

    public static EventVisibility fromString(String value) {
        return Arrays.stream(values())
            .filter(visibility -> visibility.name().equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "'visibility' must be one of " + Arrays.toString(values()) + " but was '" + value + "'"));
    }

    public String value() {
        return name();
    }
}
