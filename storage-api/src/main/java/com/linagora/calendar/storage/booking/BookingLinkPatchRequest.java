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
import java.util.Optional;

import org.apache.james.util.ValuePatch;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;

public record BookingLinkPatchRequest(Optional<CalendarURL> calendarUrl,
                                      Optional<Duration> duration,
                                      Optional<Boolean> active,
                                      ValuePatch<AvailabilityRules> availabilityRules) {
    public BookingLinkPatchRequest {
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(duration, "'eventDuration' must not be null");
        Preconditions.checkNotNull(active, "'active' must not be null");
        Preconditions.checkNotNull(availabilityRules, "'availabilityRules' must not be null");
        duration.ifPresent(value -> Preconditions.checkArgument(!value.isNegative() && !value.isZero(), "'eventDuration' must be positive"));

        Preconditions.checkArgument(calendarUrl.isPresent()
            || duration.isPresent()
            || active.isPresent()
            || !availabilityRules.isKept(), "At least one updatable field is required");
    }
}
