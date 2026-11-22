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

public class BookingLinkLimits {
    // The slots route refuses ranges over 60 days: a longer event can never be offered.
    public static final Duration MAX_EVENT_DURATION = Duration.ofDays(60);
    public static final int MAX_NAME_LENGTH = 255;
    public static final int MAX_LOCATION_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    private BookingLinkLimits() {
    }

    public static void checkEventDuration(Duration duration) {
        Preconditions.checkArgument(duration.compareTo(MAX_EVENT_DURATION) <= 0,
            "'durationMinutes' must not exceed %s minutes (%s days)", MAX_EVENT_DURATION.toMinutes(), MAX_EVENT_DURATION.toDays());
    }

    public static void checkName(Optional<String> name) {
        checkLength("name", name, MAX_NAME_LENGTH);
    }

    public static void checkLocation(Optional<String> location) {
        checkLength("location", location, MAX_LOCATION_LENGTH);
    }

    public static void checkDescription(Optional<String> description) {
        checkLength("description", description, MAX_DESCRIPTION_LENGTH);
    }

    private static void checkLength(String field, Optional<String> value, int maxLength) {
        value.ifPresent(text -> Preconditions.checkArgument(text.length() <= maxLength,
            "'%s' must not exceed %s characters", field, maxLength));
    }
}
