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

import net.fortuna.ical4j.model.component.VEvent;

public record CalendarEventCancelNotificationEmail(CalendarEventNotificationEmail base) {

    public static final boolean DISPLAY_FORWARD_WARNING = true;

    public static CalendarEventCancelNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventCancelNotificationEmail(CalendarEventNotificationEmail.from(dto));
    }

    public Map<String, Object> toPugModel(Locale locale,
                                          ZoneId zoneToDisplay,
                                          EventInCalendarLinkFactory eventInCalendarLinkFactory,
                                          boolean isInternalUser) {
        VEvent vEvent = base.getFirstVEvent();
        PersonModel organizer = PersonModel.from(EventParseUtils.getOrganizer(vEvent));
        return toPugModel(locale, zoneToDisplay, eventInCalendarLinkFactory, isInternalUser, organizer, organizer, DISPLAY_FORWARD_WARNING);
    }

    public Map<String, Object> toPugModel(Locale locale,
                                          ZoneId zoneToDisplay,
                                          EventInCalendarLinkFactory eventInCalendarLinkFactory,
                                          boolean isInternalUser,
                                          PersonModel creator,
                                          PersonModel canceler,
                                          boolean displayForwardWarning) {
        VEvent vEvent = base.getFirstVEvent();
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", base.toPugModel(locale, zoneToDisplay));
        contentBuilder.put("canceler", canceler.displayName());
        contentBuilder.put("displayForwardWarning", displayForwardWarning);
        if (isInternalUser) {
            contentBuilder.put("seeInCalendarLink", eventInCalendarLinkFactory.getEventInCalendarLink(startDate));
        }
        return ImmutableMap.of("content", contentBuilder.build(),
            "subject.summary", summary,
            "subject.creator", StringUtils.defaultIfEmpty(creator.cn(), creator.email()));
    }
}
