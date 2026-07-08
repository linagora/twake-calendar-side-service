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

import org.apache.james.util.ValuePatch;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;

public record BookingLinkPatchRequest(ValuePatch<CalendarURL> calendarUrl,
                                      ValuePatch<Duration> duration,
                                      ValuePatch<Boolean> active,
                                      ValuePatch<Boolean> autoAccept,
                                      ValuePatch<AvailabilityRules> availabilityRules,
                                      ValuePatch<String> name,
                                      ValuePatch<String> description,
                                      ValuePatch<String> color) {
    public BookingLinkPatchRequest {
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(duration, "'eventDuration' must not be null");
        Preconditions.checkNotNull(active, "'active' must not be null");
        Preconditions.checkNotNull(autoAccept, "'autoAccept' must not be null");
        Preconditions.checkNotNull(availabilityRules, "'availabilityRules' must not be null");
        Preconditions.checkNotNull(name, "'name' must not be null");
        Preconditions.checkNotNull(description, "'description' must not be null");
        Preconditions.checkNotNull(color, "'color' must not be null");
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
            || !name.isKept()
            || !description.isKept()
            || !color.isKept(), "At least one updatable field is required");
    }

    public BookingLinkPatchRequest(ValuePatch<CalendarURL> calendarUrl,
                                   ValuePatch<Duration> duration,
                                   ValuePatch<Boolean> active,
                                   ValuePatch<Boolean> autoAccept,
                                   ValuePatch<AvailabilityRules> availabilityRules,
                                   ValuePatch<String> name,
                                   ValuePatch<String> description) {
        this(calendarUrl, duration, active, autoAccept, availabilityRules, name, description, ValuePatch.keep());
    }

    public BookingLinkPatchRequest(ValuePatch<CalendarURL> calendarUrl,
                                   ValuePatch<Duration> duration,
                                   ValuePatch<Boolean> active,
                                   ValuePatch<AvailabilityRules> availabilityRules,
                                   ValuePatch<String> name,
                                   ValuePatch<String> description) {
        this(calendarUrl, duration, active, ValuePatch.keep(), availabilityRules, name, description);
    }

    public BookingLinkPatchRequest(ValuePatch<CalendarURL> calendarUrl,
                                   ValuePatch<Duration> duration,
                                   ValuePatch<Boolean> active,
                                   ValuePatch<AvailabilityRules> availabilityRules) {
        this(calendarUrl, duration, active, availabilityRules, ValuePatch.keep(), ValuePatch.keep());
    }
}
