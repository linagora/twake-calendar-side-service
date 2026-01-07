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

import java.net.URI;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

public record CalendarURL(OpenPaaSId base, OpenPaaSId calendarId) {
    public static final String CALENDAR_SEGMENT = "calendars";
    public static final String CALENDAR_URL_PATH_PREFIX = "/" + CALENDAR_SEGMENT;

    public static CalendarURL from(OpenPaaSId id) {
        return new CalendarURL(id, id);
    }

    public static CalendarURL deserialize(String rawValue) {
        return parse(rawValue);
    }

    /**
     * Supported inputs:
     * - base/calendar
     * - /base/calendar
     * - /calendars/base/calendar
     * - /calendars/base/calendar/
     * - /calendars/base/calendar/event.ics
     */
    public static CalendarURL parse(String rawValue) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(rawValue),
            "CalendarURL must not be null or empty");

        List<String> parts = Splitter.on('/')
            .omitEmptyStrings()
            .trimResults()
            .splitToList(rawValue);

        Preconditions.checkArgument(!parts.isEmpty(), "Invalid CalendarURL format: %s", rawValue);

        if (CALENDAR_SEGMENT.equals(parts.getFirst())) {
            Preconditions.checkArgument(parts.size() >= 3, "Invalid CalendarURL format, expected /calendars/{base}/{calendar}: %s", rawValue);
            return new CalendarURL(new OpenPaaSId(parts.get(1)), new OpenPaaSId(parts.get(2)));
        }

        Preconditions.checkArgument(parts.size() >= 2, "Invalid CalendarURL format, expected {base}/{calendar}: %s", rawValue);
        return new CalendarURL(new OpenPaaSId(parts.get(0)), new OpenPaaSId(parts.get(1)));
    }

    public CalendarURL {
        Preconditions.checkArgument(base != null, "baseCalendarId must not be null");
        Preconditions.checkArgument(calendarId != null, "calendarId must not be null");
    }

    public URI asUri() {
        return URI.create(CALENDAR_URL_PATH_PREFIX + "/" + base.value() + "/" + calendarId.value());
    }

    public String serialize() {
        return base().value() + "/" + calendarId().value();
    }
}
