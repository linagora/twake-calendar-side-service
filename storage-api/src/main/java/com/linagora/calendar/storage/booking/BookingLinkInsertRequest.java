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
import java.util.List;
import java.util.Optional;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.model.ResourceId;

public record BookingLinkInsertRequest(CalendarURL calendarUrl,
                                       Duration eventDuration,
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
                                       Optional<BookingLinkAlarm> alarm) {
    public static final boolean ACTIVE = true;
    public static final boolean AUTO_ACCEPT = false;
    public static final ExtraAttendees NO_EXTRA_ATTENDEE = ExtraAttendees.NONE;
    public static final List<ResourceId> NO_RESOURCE = List.of();

    public BookingLinkInsertRequest {
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(eventDuration, "'eventDuration' must not be null");
        Preconditions.checkArgument(!eventDuration.isNegative() && !eventDuration.isZero(), "'eventDuration' must be positive");
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private CalendarURL calendarUrl;
        private Duration eventDuration;
        private boolean active = ACTIVE;
        private boolean autoAccept = AUTO_ACCEPT;
        private Optional<AvailabilityRules> availabilityRules = Optional.empty();
        private ExtraAttendees extraAttendees = NO_EXTRA_ATTENDEE;
        private Optional<String> name = Optional.empty();
        private Optional<String> description = Optional.empty();
        private Optional<String> color = Optional.empty();
        private Optional<String> location = Optional.empty();
        private Optional<EventVisibility> visibility = Optional.empty();
        private Optional<EventTransparency> transparency = Optional.empty();
        private List<ResourceId> resources = NO_RESOURCE;
        private Optional<BookingLinkAlarm> alarm = Optional.empty();

        public Builder calendarUrl(CalendarURL calendarUrl) {
            this.calendarUrl = calendarUrl;
            return this;
        }

        public Builder eventDuration(Duration eventDuration) {
            this.eventDuration = eventDuration;
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

        public Builder availabilityRules(AvailabilityRules availabilityRules) {
            this.availabilityRules = Optional.of(availabilityRules);
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

        public Builder name(String name) {
            this.name = Optional.of(name);
            return this;
        }

        public Builder description(String description) {
            this.description = Optional.of(description);
            return this;
        }

        public Builder color(String color) {
            this.color = Optional.of(color);
            return this;
        }

        public Builder location(String location) {
            this.location = Optional.of(location);
            return this;
        }

        public Builder visibility(EventVisibility visibility) {
            this.visibility = Optional.of(visibility);
            return this;
        }

        public Builder transparency(EventTransparency transparency) {
            this.transparency = Optional.of(transparency);
            return this;
        }

        public Builder resources(List<ResourceId> resources) {
            this.resources = resources;
            return this;
        }

        public Builder alarm(BookingLinkAlarm alarm) {
            this.alarm = Optional.of(alarm);
            return this;
        }

        public BookingLinkInsertRequest build() {
            return new BookingLinkInsertRequest(calendarUrl, eventDuration, active, autoAccept, availabilityRules, extraAttendees,
                name, description, color, location, visibility, transparency, resources, alarm);
        }
    }

}
