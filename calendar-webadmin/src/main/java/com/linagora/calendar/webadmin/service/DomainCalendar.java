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

import org.apache.james.core.Domain;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

/** A calendar owned by a domain rather than by a user: a team calendar or the calendar of a resource. */
public record DomainCalendar(Domain domain, OpenPaaSId domainId, CalendarType calendarType, CalendarURL calendarURL) {

    /**
     * Which kind of domain scoped calendar is at hand. Recorded in the import task details: team calendars and
     * resources share the same calendar layout on the DAV server, their identifiers alone do not tell them apart.
     */
    public enum CalendarType {
        TEAM_CALENDAR("team-calendar"),
        RESOURCE("resource");

        private final String value;

        CalendarType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
