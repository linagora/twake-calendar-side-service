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

package com.linagora.calendar.storage.eventtoken;

import java.util.Arrays;

public enum EventTokenRight {
    VIEW,
    ADD_ATTENDEE;

    public static EventTokenRight fromString(String value) {
        return Arrays.stream(values())
            .filter(right -> right.name().equals(value))
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("Invalid right '%s', expected one of %s"
                .formatted(value, Arrays.toString(values()))));
    }
}
