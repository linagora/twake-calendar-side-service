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

import static com.linagora.calendar.amqp.model.CalendarEventNotificationEmail.PERSON_TO_MODEL;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

public record CalendarEventInviteNotificationEmail(CalendarEventNotificationEmail base) {

    public static CalendarEventInviteNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventInviteNotificationEmail(
            CalendarEventNotificationEmail.from(dto)
        );
    }

    public Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay, EventInCalendarLinkFactory eventInCalendarLinkFactory, boolean isInternalUser) {
        VEvent vEvent = (VEvent) base.event().getComponent(Component.VEVENT).get();
        PersonModel organizer = PERSON_TO_MODEL.apply(EventParseUtils.getOrganizer(vEvent));
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", base.toPugModel(locale, zoneToDisplay));
        if (isInternalUser) {
            contentBuilder.put("seeInCalendarLink", eventInCalendarLinkFactory.getEventInCalendarLink(startDate));
        }
        return ImmutableMap.of("content", contentBuilder.build(),
            "subject.summary", summary,
            "subject.organizer", organizer.cn());
    }
}
