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

import java.time.Instant;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.DavPathSegment;

/**
 * Grants a right on one calendar object to whoever holds the token, until the token expires.
 *
 * @param eventId the name of the calendar object resource in its calendar, without the ".ics" extension
 */
public record EventToken(EventTokenId id,
                         CalendarURL calendarURL,
                         String eventId,
                         EventTokenRight right,
                         Instant expiration) {

    public static class Builder {
        private EventTokenId id;
        private CalendarURL calendarURL;
        private String eventId;
        private EventTokenRight right;
        private Instant expiration;

        private Builder() {
        }

        public Builder id(EventTokenId id) {
            this.id = id;
            return this;
        }

        public Builder calendarURL(CalendarURL calendarURL) {
            this.calendarURL = calendarURL;
            return this;
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder right(EventTokenRight right) {
            this.right = right;
            return this;
        }

        public Builder expiration(Instant expiration) {
            this.expiration = expiration;
            return this;
        }

        public EventToken build() {
            return new EventToken(id, calendarURL, eventId, right, expiration);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public EventToken {
        Preconditions.checkArgument(id != null, "'id' must not be null");
        Preconditions.checkArgument(calendarURL != null, "'calendarURL' must not be null");
        DavPathSegment.validate(eventId, "eventId");
        Preconditions.checkArgument(right != null, "'right' must not be null");
        Preconditions.checkArgument(expiration != null, "'expiration' must not be null");
    }

    public boolean isExpiredAt(Instant now) {
        return !expiration.isAfter(now);
    }
}
