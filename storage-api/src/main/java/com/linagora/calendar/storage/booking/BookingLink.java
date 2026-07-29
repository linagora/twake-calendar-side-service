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
import java.util.List;
import java.util.Optional;

import org.apache.james.core.Username;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.model.ResourceId;

public record BookingLink(Username username,
                          BookingLinkPublicId publicId,
                          CalendarURL calendarUrl,
                          Duration duration,
                          boolean active,
                          boolean autoAccept,
                          Optional<AvailabilityRules> availabilityRules,
                          ExtraAttendees extraAttendees,
                          Optional<String> name,
                          Optional<String> description,
                          Optional<String> color,
                          Optional<String> location,
                          Optional<EventVisibility> visibility,
                          Optional<EventTransparency> transparency,
                          List<ResourceId> resources,
                          List<BookingLinkAlarm> alarm,
                          Instant createdAt,
                          Instant updatedAt) {

    public static final String DEFAULT_COLOR = "#6B4ECC";

    public BookingLink {
        Preconditions.checkNotNull(username, "'username' must not be null");
        Preconditions.checkNotNull(publicId, "'publicId' must not be null");
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(duration, "'eventDuration' must not be null");
        Preconditions.checkArgument(!duration.isNegative() && !duration.isZero(), "'eventDuration' must be positive");
        Preconditions.checkNotNull(availabilityRules, "'availabilityRules' must not be null");
        Preconditions.checkNotNull(extraAttendees, "'extraAttendees' must not be null");
        Preconditions.checkNotNull(name, "'name' must not be null");
        Preconditions.checkNotNull(description, "'description' must not be null");
        Preconditions.checkNotNull(color, "'color' must not be null");
        Preconditions.checkNotNull(location, "'location' must not be null");
        Preconditions.checkNotNull(visibility, "'visibility' must not be null");
        Preconditions.checkNotNull(transparency, "'transparency' must not be null");
        Preconditions.checkNotNull(resources, "'resources' must not be null");
        Preconditions.checkNotNull(alarm, "'alarm' must not be null");
        Preconditions.checkNotNull(createdAt, "'createdAt' must not be null");
    }

    public String colorOrDefault() {
        return color.orElse(DEFAULT_COLOR);
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
            .autoAccept(autoAccept)
            .availabilityRules(availabilityRules)
            .extraAttendees(extraAttendees)
            .name(name)
            .description(description)
            .color(color)
            .location(location)
            .visibility(visibility)
            .transparency(transparency)
            .resources(resources)
            .alarm(alarm)
            .createdAt(createdAt)
            .updatedAt(updatedAt);
    }

    public static class Builder {
        private Username username;
        private BookingLinkPublicId publicId;
        private CalendarURL calendarUrl;
        private Duration duration;
        private boolean active;
        private boolean autoAccept;
        private Optional<AvailabilityRules> availabilityRules = Optional.empty();
        private ExtraAttendees extraAttendees = ExtraAttendees.NONE;
        private Optional<String> name = Optional.empty();
        private Optional<String> description = Optional.empty();
        private Optional<String> color = Optional.empty();
        private Optional<String> location = Optional.empty();
        private Optional<EventVisibility> visibility = Optional.empty();
        private Optional<EventTransparency> transparency = Optional.empty();
        private List<ResourceId> resources = List.of();
        private List<BookingLinkAlarm> alarm = List.of();
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

        public Builder autoAccept(boolean autoAccept) {
            this.autoAccept = autoAccept;
            return this;
        }

        public Builder availabilityRules(Optional<AvailabilityRules> availabilityRules) {
            this.availabilityRules = availabilityRules;
            return this;
        }

        public Builder extraAttendees(ExtraAttendees extraAttendees) {
            this.extraAttendees = extraAttendees;
            return this;
        }

        public Builder name(Optional<String> name) {
            this.name = name;
            return this;
        }

        public Builder description(Optional<String> description) {
            this.description = description;
            return this;
        }

        public Builder color(Optional<String> color) {
            this.color = color;
            return this;
        }

        public Builder location(Optional<String> location) {
            this.location = location;
            return this;
        }

        public Builder visibility(Optional<EventVisibility> visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder transparency(Optional<EventTransparency> transparency) {
            this.transparency = transparency;
            return this;
        }

        public Builder resources(List<ResourceId> resources) {
            this.resources = resources;
            return this;
        }

        public Builder alarm(List<BookingLinkAlarm> alarm) {
            this.alarm = alarm;
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
            return new BookingLink(username, publicId, calendarUrl, duration, active, autoAccept, availabilityRules, extraAttendees,
                name, description, color, location, visibility, transparency, resources, alarm, createdAt, updatedAt);
        }
    }

}
