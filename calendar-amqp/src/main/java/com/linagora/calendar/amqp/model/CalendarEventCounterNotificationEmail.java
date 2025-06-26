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

package com.linagora.calendar.amqp.model;

import net.fortuna.ical4j.model.Calendar;

public record CalendarEventCounterNotificationEmail(CalendarEventNotificationEmail calendarEventNotificationEmail,
                                                    Calendar oldEvent) {
    public static CalendarEventCounterNotificationEmail from(com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventCounterNotificationEmail(
            CalendarEventNotificationEmail.from(dto),
            dto.oldEvent().orElseThrow(() -> new IllegalArgumentException("oldEvent must not be empty"))
        );
    }
}
