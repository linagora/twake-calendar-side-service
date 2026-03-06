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

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;

public record BookingLinkInsertRequest(CalendarURL calendarUrl,
                                       Duration eventDuration,
                                       boolean active,
                                       Optional<AvailabilityRules> availabilityRules) {
    public static final boolean ACTIVE = true;

    public BookingLinkInsertRequest {
        Preconditions.checkNotNull(calendarUrl, "'calendarUrl' must not be null");
        Preconditions.checkNotNull(eventDuration, "'eventDuration' must not be null");
        Preconditions.checkArgument(!eventDuration.isNegative() && !eventDuration.isZero(), "'eventDuration' must be positive");
        Preconditions.checkNotNull(availabilityRules, "'availabilityRules' must not be null");
    }

    public BookingLinkInsertRequest(CalendarURL calendarUrl,
                                    Duration eventDuration,
                                    AvailabilityRules availabilityRules) {
        this(calendarUrl, eventDuration, ACTIVE, Optional.of(availabilityRules));
    }

}
