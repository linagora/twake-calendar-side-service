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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.LocationModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;

public record CalendarEventCancelNotificationEmail(CalendarEventNotificationEmail base) {

    public static final Function<EventFields.Person, PersonModel> PERSON_TO_MODEL =
        person -> new PersonModel(person.cn(), person.email().asString());

    public static CalendarEventCancelNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventCancelNotificationEmail(CalendarEventNotificationEmail.from(dto));
    }

    public Map<String, Object> toPugModel(Locale locale, EventInCalendarLinkFactory eventInCalendarLinkFactory) {
        VEvent vEvent = (VEvent) base.event().getComponent(Component.VEVENT).get();
        PersonModel organizer = PERSON_TO_MODEL.apply(EventParseUtils.getOrganizer(vEvent));
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);
        List<EventFields.Person> resourceList = EventParseUtils.getResources(vEvent);

        ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
        eventBuilder.put("organizer", organizer.toPugModel())
            .put("attendees", EventParseUtils.getAttendees(vEvent).stream()
                .collect(ImmutableMap.toImmutableMap(attendee -> attendee.email().asString(),
                    attendee -> PERSON_TO_MODEL.apply(attendee).toPugModel())))
            .put("summary", summary)
            .put("allDay", EventParseUtils.isAllDay(vEvent))
            .put("start", new EventTimeModel(startDate).toPugModel(locale))
            .put("end", EventParseUtils.getEndTime(vEvent).map(endDate -> new EventTimeModel(endDate).toPugModel(locale))
                .orElseThrow(() -> new IllegalArgumentException("Missing endDate")))
            .put("hasResources", !resourceList.isEmpty())
            .put("resources", resourceList.stream()
                .collect(ImmutableMap.toImmutableMap(resource -> resource.email().asString(),
                    resource -> PERSON_TO_MODEL.apply(resource).toPugModel())));
        EventParseUtils.getLocation(vEvent).ifPresent(location -> eventBuilder.put("location", new LocationModel(location).toPugModel()));
        EventParseUtils.getDescription(vEvent).ifPresent(description -> eventBuilder.put("description", description));

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", eventBuilder.build())
            .put("seeInCalendarLink", eventInCalendarLinkFactory.getEventInCalendarLink(startDate));
        return ImmutableMap.of("content", contentBuilder.build(),
            "subject.summary", summary,
            "subject.organizer", organizer.cn());
    }
}
