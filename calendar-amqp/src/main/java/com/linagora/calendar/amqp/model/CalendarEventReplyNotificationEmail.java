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

import static com.linagora.calendar.storage.event.EventParseUtils.DuplicateAttendeePolicy.KEEP_FIRST;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.smtp.template.content.model.ReplyContentModelBuilder;
import com.linagora.calendar.storage.event.EventFields.Person;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public record CalendarEventReplyNotificationEmail(CalendarEventNotificationEmail base) {

    public static CalendarEventReplyNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventReplyNotificationEmail(CalendarEventNotificationEmail.from(dto));
    }

    public ReplyContentModelBuilder.LocaleStep toReplyContentModelBuilder() {
        VEvent vEvent = base.getFirstVEvent();

        Person attendee = EventParseUtils.getAttendees(vEvent).getFirst();
        PersonModel attendeeModel = PersonModel.from(attendee);
        PartStat attendeePartStat = attendee.partStat()
            .orElseThrow(() -> new IllegalStateException("Attendee partStat is missing"));

        List<PersonModel> resourceModel = PersonModel.fromList(EventParseUtils.getResources(vEvent, KEEP_FIRST));

        return ReplyContentModelBuilder.builder()
            .eventSummary(EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY))
            .eventAllDay(EventParseUtils.isAllDay(vEvent))
            .eventStart(EventParseUtils.getStartTime(vEvent))
            .eventEnd(EventParseUtils.getEndTime(vEvent))
            .eventLocation(EventParseUtils.getLocation(vEvent))
            .eventAttendee(attendeeModel, attendeePartStat)
            .eventOrganizer(PersonModel.from(EventParseUtils.getOrganizer(vEvent)))
            .eventResources(resourceModel)
            .eventDescription(EventParseUtils.getDescription(vEvent));
    }
}
