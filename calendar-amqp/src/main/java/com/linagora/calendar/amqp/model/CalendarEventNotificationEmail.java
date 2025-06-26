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

import org.apache.james.core.MailAddress;

import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.Method;

public record CalendarEventNotificationEmail(MailAddress senderEmail,
                                             MailAddress recipientEmail,
                                             Method method,
                                             Calendar event,
                                             boolean notifyEvent,
                                             String calendarURI,
                                             String eventPath) {

    public static CalendarEventNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventNotificationEmail(
            dto.senderEmail(),
            dto.recipientEmail(),
            dto.method(),
            dto.event(),
            dto.notifyEvent(),
            dto.calendarURI(),
            dto.eventPath()
        );
    }
}
