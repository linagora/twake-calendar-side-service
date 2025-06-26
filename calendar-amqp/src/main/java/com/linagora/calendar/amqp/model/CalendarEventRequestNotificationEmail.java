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

import java.util.Optional;

import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;

public record CalendarEventRequestNotificationEmail(CalendarEventNotificationEmail calendarEventNotificationEmail,
                                                    Optional<CalendarEventNotificationEmailDTO.Changes> changes,
                                                    boolean isNewEvent) {

    public static CalendarEventRequestNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventRequestNotificationEmail(
            CalendarEventNotificationEmail.from(dto),
            dto.changes(),
            dto.isNewEvent().orElse(false)
        );
    }
}
