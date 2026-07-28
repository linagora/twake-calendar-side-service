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

import org.apache.james.util.ValuePatch;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.model.ResourceId;

public record BookingLinkPatchRequest(ValuePatch<CalendarURL> calendarUrl,
                                      ValuePatch<Duration> duration,
                                      ValuePatch<Boolean> active,
                                      ValuePatch<Boolean> autoAccept,
                                      ValuePatch<AvailabilityRules> availabilityRules,
                                      ValuePatch<ExtraAttendees> extraAttendees,
                                      ValuePatch<String> name,
                                      ValuePatch<String> description,
                                      ValuePatch<String> color,
                                      ValuePatch<String> location,
                                      ValuePatch<EventVisibility> visibility,
                                      ValuePatch<EventTransparency> transparency,
                                      ValuePatch<List<ResourceId>> resources,
                                      ValuePatch<BookingLinkAlarm> alarm) {
    public BookingLinkPatchRequest {
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(duration, "'eventDuration' must not be null");
        Preconditions.checkNotNull(active, "'active' must not be null");
        Preconditions.checkNotNull(autoAccept, "'autoAccept' must not be null");
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
        Preconditions.checkArgument(!calendarUrl.isRemoved(), "'calendarUrl' can not be removed");
        Preconditions.checkArgument(!duration.isRemoved(), "'eventDuration' can not be removed");
        Preconditions.checkArgument(!active.isRemoved(), "'active' can not be removed");
        Preconditions.checkArgument(!autoAccept.isRemoved(), "'autoAccept' can not be removed");
        if (duration.isModified()) {
            Duration value = duration.get();
            Preconditions.checkArgument(!value.isNegative() && !value.isZero(), "'eventDuration' must be positive");
        }

        Preconditions.checkArgument(!calendarUrl.isKept()
            || !duration.isKept()
            || !active.isKept()
            || !autoAccept.isKept()
            || !availabilityRules.isKept()
            || !extraAttendees.isKept()
            || !name.isKept()
            || !description.isKept()
            || !color.isKept()
            || !location.isKept()
            || !visibility.isKept()
            || !transparency.isKept()
            || !resources.isKept()
            || !alarm.isKept(), "At least one updatable field is required");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a patch where every field defaults to {@link ValuePatch#keep()} - only the fields explicitly set are
     * modified or removed.
     */
    public static class Builder {
        private ValuePatch<CalendarURL> calendarUrl = ValuePatch.keep();
        private ValuePatch<Duration> duration = ValuePatch.keep();
        private ValuePatch<Boolean> active = ValuePatch.keep();
        private ValuePatch<Boolean> autoAccept = ValuePatch.keep();
        private ValuePatch<AvailabilityRules> availabilityRules = ValuePatch.keep();
        private ValuePatch<ExtraAttendees> extraAttendees = ValuePatch.keep();
        private ValuePatch<String> name = ValuePatch.keep();
        private ValuePatch<String> description = ValuePatch.keep();
        private ValuePatch<String> color = ValuePatch.keep();
        private ValuePatch<String> location = ValuePatch.keep();
        private ValuePatch<EventVisibility> visibility = ValuePatch.keep();
        private ValuePatch<EventTransparency> transparency = ValuePatch.keep();
        private ValuePatch<List<ResourceId>> resources = ValuePatch.keep();
        private ValuePatch<BookingLinkAlarm> alarm = ValuePatch.keep();

        public Builder calendarUrl(ValuePatch<CalendarURL> calendarUrl) {
            this.calendarUrl = calendarUrl;
            return this;
        }

        public Builder duration(ValuePatch<Duration> duration) {
            this.duration = duration;
            return this;
        }

        public Builder active(ValuePatch<Boolean> active) {
            this.active = active;
            return this;
        }

        public Builder autoAccept(ValuePatch<Boolean> autoAccept) {
            this.autoAccept = autoAccept;
            return this;
        }

        public Builder availabilityRules(ValuePatch<AvailabilityRules> availabilityRules) {
            this.availabilityRules = availabilityRules;
            return this;
        }

        public Builder extraAttendees(ValuePatch<ExtraAttendees> extraAttendees) {
            this.extraAttendees = extraAttendees;
            return this;
        }

        public Builder name(ValuePatch<String> name) {
            this.name = name;
            return this;
        }

        public Builder description(ValuePatch<String> description) {
            this.description = description;
            return this;
        }

        public Builder color(ValuePatch<String> color) {
            this.color = color;
            return this;
        }

        public Builder location(ValuePatch<String> location) {
            this.location = location;
            return this;
        }

        public Builder visibility(ValuePatch<EventVisibility> visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder transparency(ValuePatch<EventTransparency> transparency) {
            this.transparency = transparency;
            return this;
        }

        public Builder resources(ValuePatch<List<ResourceId>> resources) {
            this.resources = resources;
            return this;
        }

        public Builder alarm(ValuePatch<BookingLinkAlarm> alarm) {
            this.alarm = alarm;
            return this;
        }

        public BookingLinkPatchRequest build() {
            return new BookingLinkPatchRequest(calendarUrl, duration, active, autoAccept, availabilityRules, extraAttendees,
                name, description, color, location, visibility, transparency, resources, alarm);
        }
    }
}
