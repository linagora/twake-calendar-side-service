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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.Username;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;

public record BookingLink(Username username,
                          BookingLinkPublicId publicId,
                          CalendarURL calendarUrl,
                          Duration duration,
                          boolean active,
                          Optional<AvailabilityRules> availabilityRules,
                          Instant createdAt,
                          Instant updatedAt) {

    public BookingLink {
        Preconditions.checkNotNull(username, "'username' must not be null");
        Preconditions.checkNotNull(publicId, "'publicId' must not be null");
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(duration, "'eventDuration' must not be null");
        Preconditions.checkArgument(!duration.isNegative() && !duration.isZero(), "'eventDuration' must be positive");
        Preconditions.checkNotNull(availabilityRules, "'availabilityRules' must not be null");
        Preconditions.checkNotNull(createdAt, "'createdAt' must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .username(username)
            .publicId(publicId)
            .calendarUrl(calendarUrl)
            .duration(duration)
            .active(active)
            .availabilityRules(availabilityRules)
            .createdAt(createdAt)
            .updatedAt(updatedAt);
    }

    public static class Builder {
        private Username username;
        private BookingLinkPublicId publicId;
        private CalendarURL calendarUrl;
        private Duration duration;
        private boolean active;
        private Optional<AvailabilityRules> availabilityRules = Optional.empty();
        private Instant createdAt;
        private Instant updatedAt;

        public Builder username(Username username) {
            this.username = username;
            return this;
        }

        public Builder publicId(BookingLinkPublicId publicId) {
            this.publicId = publicId;
            return this;
        }

        public Builder calendarUrl(CalendarURL calendarUrl) {
            this.calendarUrl = calendarUrl;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder availabilityRules(Optional<AvailabilityRules> availabilityRules) {
            this.availabilityRules = availabilityRules;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public BookingLink build() {
            return new BookingLink(username, publicId, calendarUrl, duration, active, availabilityRules, createdAt, updatedAt);
        }
    }

}
