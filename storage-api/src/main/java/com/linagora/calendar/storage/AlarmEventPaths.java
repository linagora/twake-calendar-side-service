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

package com.linagora.calendar.storage;

import java.util.regex.Pattern;

/**
 * Matches the stored event path of an alarm, {@code /calendars/<home>/<calendar>/<object>}, against a calendar home.
 * The patterns are plain regular expressions so that a database can evaluate them in its write condition.
 */
public final class AlarmEventPaths {
    private static final String SEGMENT = "[^/]+";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("([^A-Za-z0-9])");

    public static final Pattern ANY_CALENDAR_HOME = calendarObjectIn(SEGMENT);

    public static Pattern calendarObjectIn(OpenPaaSId calendarHome) {
        return calendarObjectIn(NON_ALPHANUMERIC.matcher(calendarHome.value()).replaceAll("\\\\$1"));
    }

    /**
     * An alarm is replaceable from a calendar home when it was scheduled from it, or when its stored path is not the one
     * of a calendar object (written before the field was stored).
     */
    public static boolean replaceableFrom(String storedEventPath, OpenPaaSId calendarHome) {
        return !ANY_CALENDAR_HOME.matcher(storedEventPath).matches()
            || calendarObjectIn(calendarHome).matcher(storedEventPath).matches();
    }

    private static Pattern calendarObjectIn(String calendarHomeRegex) {
        return Pattern.compile("^/*calendars/+" + calendarHomeRegex + "/+" + SEGMENT + "/+" + SEGMENT + "/*$");
    }

    private AlarmEventPaths() {
    }
}
