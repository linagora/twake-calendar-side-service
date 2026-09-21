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

package com.linagora.calendar.dav.importer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * A single calendar object, as stored on the DAV server: the VEVENTs sharing a given UID - a master event
 * and its recurrence overrides - alongside the calendar level properties and time zones they rely on.
 */
public record EventToImport(String uid, String resourceName, List<VEvent> vEvents, byte[] ics) {

    public static List<EventToImport> parse(byte[] icsPayload) {
        Calendar calendar = CalendarUtil.parseIcs(icsPayload);

        return EventParseUtils.groupByUid(calendar)
            .entrySet()
            .stream()
            .map(entry -> of(calendar, entry.getKey(), entry.getValue()))
            .toList();
    }

    private static EventToImport of(Calendar calendar, String uid, List<VEvent> vEvents) {
        return new EventToImport(uid, DavResourceName.fromUid(uid), vEvents,
            CalendarUtil.withVEvents(calendar, vEvents).toString().getBytes(StandardCharsets.UTF_8));
    }
}
