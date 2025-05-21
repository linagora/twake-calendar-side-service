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

package com.linagora.calendar.storage.eventsearch;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.CalendarURL;

public record EventSearchQuery(String query,
                               Optional<List<CalendarURL>> calendars,
                               Optional<List<MailAddress>> organizers,
                               Optional<List<MailAddress>> attendees,
                               int limit,
                               int offset) {

    public static final int DEFAULT_LIMIT = 10;
    public static final int OFFSET_INITIAL = 0;

    public static Builder builder() {
        return new Builder();
    }

    public EventSearchQuery {
        Preconditions.checkNotNull(query, "query must not be null");
        Preconditions.checkNotNull(calendars, "calendars must not be null");
        Preconditions.checkNotNull(organizers, "organizers must not be null");
        Preconditions.checkNotNull(attendees, "attendees must not be null");
        Preconditions.checkArgument(limit > 0, "limit must be positive");
        Preconditions.checkArgument(offset >= 0, "offset must be non-negative");
    }

    public static class Builder {
        private String query;
        private Optional<List<CalendarURL>> calendars = Optional.empty();
        private Optional<List<MailAddress>> organizers = Optional.empty();
        private Optional<List<MailAddress>> attendees = Optional.empty();
        private int limit = DEFAULT_LIMIT;
        private int offset = OFFSET_INITIAL;

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder calendars(CalendarURL... calendars) {
            this.calendars = Optional.of(Arrays.asList(calendars));
            return this;
        }

        public Builder calendars(List<CalendarURL> calendars) {
            this.calendars = Optional.of(calendars);
            return this;
        }

        public Builder organizers(List<MailAddress> organizers) {
            this.organizers = Optional.of(organizers);
            return this;
        }

        public Builder organizers(MailAddress... organizers) {
            this.organizers = Optional.of(Arrays.asList(organizers));
            return this;
        }

        public Builder attendees(List<MailAddress> attendees) {
            this.attendees = Optional.of(attendees);
            return this;
        }

        public Builder attendees(MailAddress... attendees) {
            this.attendees = Optional.of(Arrays.asList(attendees));
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public EventSearchQuery build() {
            return new EventSearchQuery(query, calendars, organizers, attendees, limit, offset);
        }
    }
}
