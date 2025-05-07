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

import com.google.common.base.Preconditions;

public record CalendarURL(OpenPaaSId base, OpenPaaSId calendarId) {
    public static final String CALENDAR_URL_PATH_PREFIX = "/calendars";

    public static CalendarURL from(OpenPaaSId id) {
        return new CalendarURL(id, id);
    }

    public CalendarURL {
        Preconditions.checkArgument(base != null, "baseCalendarId must not be null");
        Preconditions.checkArgument(calendarId != null, "calendarId must not be null");
    }

    public URI asUri() {
        return URI.create(CALENDAR_URL_PATH_PREFIX + "/" + base.value() + "/" + calendarId.value());
    }
}
