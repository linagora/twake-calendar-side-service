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

package com.linagora.calendar.storage.exception;

import java.util.List;
import java.util.stream.Collectors;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.EventUid;

public class CalendarSearchIndexingException extends RuntimeException {

    public static CalendarSearchIndexingException of(String message, CalendarURL calendarURL, EventUid eventUid, Throwable cause) {
        return new CalendarSearchIndexingException(message, "CalendarURL = " + calendarURL.serialize() +
            ", eventUid = " + eventUid.value(), cause);
    }

    public static CalendarSearchIndexingException of(String message, List<CalendarURL> calendarURLs, Throwable cause) {
        return new CalendarSearchIndexingException(message, "CalendarURLs = " + calendarURLs.stream()
            .map(CalendarURL::serialize)
            .collect(Collectors.joining(", ")), cause);
    }

    public static CalendarSearchIndexingException of(String message, OpenPaaSId baseCalendarId, Throwable cause) {
        return new CalendarSearchIndexingException(message, "baseCalendarId = " + baseCalendarId.value(), cause);
    }

    private CalendarSearchIndexingException(String message, String context, Throwable cause) {
        super(message + ". " + context, cause);
    }
}