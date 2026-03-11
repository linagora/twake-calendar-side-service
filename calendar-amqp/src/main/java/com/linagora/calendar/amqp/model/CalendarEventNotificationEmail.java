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

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.LocationModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Method;

public record CalendarEventNotificationEmail(MailAddress senderEmail,
                                             MailAddress recipientEmail,
                                             Method method,
                                             Calendar event,
                                             boolean notifyEvent,
                                             String calendarURI,
                                             String eventPath) {

    public static final Function<Calendar, VEvent> GET_FIRST_VEVENT_FUNCTION =
        calendar -> calendar.getComponent(Component.VEVENT)
            .map(VEvent.class::cast)
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar event"));

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

    public Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay) {
        VEvent vEvent = getFirstVEvent();
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);
        List<EventFields.Person> attendees = EventParseUtils.getAttendees(vEvent, KEEP_FIRST);
        List<EventFields.Person> resourceList = EventParseUtils.getResources(vEvent, KEEP_FIRST);

        ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
        eventBuilder.put("organizer", toPugModel(EventParseUtils.getOrganizer(vEvent)))
            .put("attendees", toPeoplePugModel(attendees))
            .put("summary", summary)
            .put("allDay", EventParseUtils.isAllDay(vEvent))
            .put("start", new EventTimeModel(startDate).toPugModel(locale, zoneToDisplay))
            .put("hasResources", !resourceList.isEmpty())
            .put("resources", toPeoplePugModel(resourceList));
        EventParseUtils.getEndTime(vEvent).map(endDate -> new EventTimeModel(endDate).toPugModel(locale, zoneToDisplay))
            .ifPresent(model -> eventBuilder.put("end", model));
        EventParseUtils.getLocation(vEvent).ifPresent(location -> eventBuilder.put("location", new LocationModel(location).toPugModel()));
        EventParseUtils.getDescription(vEvent).ifPresent(description -> eventBuilder.put("description", description));
        EventParseUtils.getPropertyValueIgnoreCase(vEvent, "X-OPENPAAS-VIDEOCONFERENCE")
            .ifPresent(value -> eventBuilder.put("videoConferenceLink", value));

        return eventBuilder.build();
    }

    private Map<String, Object> toPugModel(EventFields.Person person) {
        return PersonModel.from(person).toPugModel();
    }

    private Map<String, Map<String, Object>> toPeoplePugModel(List<EventFields.Person> people) {
        return people.stream()
            .collect(ImmutableMap.toImmutableMap(person -> person.email().asString(),
                this::toPugModel));
    }

    public byte[] eventAsBytes() {
        return event().toString().getBytes(StandardCharsets.UTF_8);
    }

    public VEvent getFirstVEvent() {
        return GET_FIRST_VEVENT_FUNCTION.apply(event());
    }
}
