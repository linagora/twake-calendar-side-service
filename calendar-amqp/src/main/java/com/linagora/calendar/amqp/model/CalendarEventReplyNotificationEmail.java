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

import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.ReplyContentModelBuilder;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.component.VEvent;

public record CalendarEventReplyNotificationEmail(CalendarEventNotificationEmail base) {

    public static CalendarEventReplyNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventReplyNotificationEmail(CalendarEventNotificationEmail.from(dto));
    }

    public ReplyContentModelBuilder.LocaleStep toReplyContentModelBuilder() {
        VEvent vEvent = base.getFirstVEvent();

        return ReplyContentModelBuilder.from(vEvent, EventParseUtils.getAttendees(vEvent).getFirst());
    }
}
