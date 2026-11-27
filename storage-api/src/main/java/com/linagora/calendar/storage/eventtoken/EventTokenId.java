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

import java.util.UUID;

import com.google.common.base.Preconditions;

public record EventTokenId(UUID value) {

    public static EventTokenId generate() {
        return new EventTokenId(UUID.randomUUID());
    }

    public static EventTokenId from(String value) {
        Preconditions.checkArgument(value != null, "'eventToken' must not be null");
        try {
            return new EventTokenId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'eventToken' must be a UUID", e);
        }
    }

    public EventTokenId {
        Preconditions.checkNotNull(value, "'eventToken' must not be null");
    }

    public String asString() {
        return value.toString();
    }
}
