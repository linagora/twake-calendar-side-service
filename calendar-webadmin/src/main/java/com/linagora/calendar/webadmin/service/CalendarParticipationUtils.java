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

package com.linagora.calendar.webadmin.service;

import org.apache.james.core.Username;

import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public final class CalendarParticipationUtils {

    public static boolean allVEventsInPartStatForUser(Calendar calendar, Username username, PartStat expectedPartStat) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .allMatch(event -> userInPartStat(event, username, expectedPartStat));
    }

    public static boolean userInPartStat(VEvent event, Username username, PartStat expectedPartStat) {
        return EventParseUtils.getAttendees(event).stream()
            .filter(person -> person.email().asString().equalsIgnoreCase(username.asString()))
            .allMatch(person -> person.partStat().isPresent() && person.partStat().get().equals(expectedPartStat));
    }
}