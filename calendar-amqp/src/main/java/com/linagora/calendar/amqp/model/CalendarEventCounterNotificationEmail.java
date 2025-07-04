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

import static com.linagora.calendar.amqp.model.CalendarEventReplyNotificationEmail.PERSON_TO_MODEL;

import java.util.List;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.CounterContentModelBuilder;
import com.linagora.calendar.smtp.template.content.model.CounterContentModelBuilder.LocaleStep;
import com.linagora.calendar.smtp.template.content.model.CounterContentModelBuilder.NewEventModel;
import com.linagora.calendar.smtp.template.content.model.CounterContentModelBuilder.OldEventModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Comment;

public record CalendarEventCounterNotificationEmail(CalendarEventNotificationEmail base,
                                                    Calendar oldEvent) {

    public static CalendarEventCounterNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventCounterNotificationEmail(
            CalendarEventNotificationEmail.from(dto),
            dto.oldEvent().orElseThrow(() -> new IllegalArgumentException("oldEvent must not be empty"))
        );
    }

    public static Function<Calendar, VEvent> getFirstVEventFunction() {
        return calendar -> calendar.getComponent(Component.VEVENT)
            .map(VEvent.class::cast)
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar event"));
    }

    public LocaleStep toCounterContentModelBuilder() {
        Function<VEvent, NewEventModel> newEventModelFunction = vEvent -> NewEventModel
            .builder()
            .allDay(EventParseUtils.isAllDay(vEvent))
            .start(EventParseUtils.getStartTime(vEvent))
            .end(EventParseUtils.getEndTime(vEvent))
            .comment(vEvent.getComments().stream().findFirst().map(Comment::getValue))
            .build();

        Function<VEvent, OldEventModel> oldEventModelFunction = vEvent -> {
            List<PersonModel> attendeeModel = EventParseUtils.getAttendees(vEvent)
                .stream()
                .map(PERSON_TO_MODEL)
                .toList();
            List<PersonModel> resourceModel = EventParseUtils.getResources(vEvent)
                .stream()
                .map(PERSON_TO_MODEL)
                .toList();

            return OldEventModel
                .builder()
                .summary(EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY))
                .allDay(EventParseUtils.isAllDay(vEvent))
                .start(EventParseUtils.getStartTime(vEvent))
                .end(EventParseUtils.getEndTime(vEvent))
                .location(EventParseUtils.getLocation(vEvent))
                .description(EventParseUtils.getDescription(vEvent))
                .attendee(attendeeModel)
                .organizer(PERSON_TO_MODEL.apply(EventParseUtils.getOrganizer(vEvent)))
                .resources(resourceModel)
                .build();
        };

        VEvent oldFirstEvent = getFirstVEventFunction().apply(this.oldEvent);
        String editorDisplayName = EventParseUtils.getAttendees(oldFirstEvent).stream()
            .filter(person -> base.senderEmail().equals(person.email()))
            .map(person -> StringUtils.defaultIfEmpty(person.cn(), person.email().asString()))
            .findFirst().orElse(StringUtils.EMPTY);

        return CounterContentModelBuilder.builder()
            .oldEvent(oldEventModelFunction.apply(oldFirstEvent))
            .newEvent(newEventModelFunction.apply(getFirstVEventFunction().apply(base().event())))
            .editorDisplayName(editorDisplayName);
    }
}
