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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.component.VEvent;

public record CalendarEventUpdateNotificationEmail(CalendarEventNotificationEmail base,
                                                   Optional<CalendarEventNotificationEmailDTO.Changes> maybeChanges) {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    public static CalendarEventUpdateNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventUpdateNotificationEmail(
            CalendarEventNotificationEmail.from(dto),
            dto.changes()
        );
    }

    public Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay, EventInCalendarLinkFactory eventInCalendarLinkFactory, boolean isInternalUser) {
        VEvent vEvent = base.getFirstVEvent();
        PersonModel organizer = PERSON_TO_MODEL.apply(EventParseUtils.getOrganizer(vEvent));
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", base.toPugModel(locale, zoneToDisplay));
        if (isInternalUser) {
            contentBuilder.put("seeInCalendarLink", eventInCalendarLinkFactory.getEventInCalendarLink(startDate));
        }

        maybeChanges.ifPresent(changes -> contentBuilder.put("changes", toPugModel(locale, zoneToDisplay, changes)));

        return ImmutableMap.of(
            "content", contentBuilder.build(),
            "subject.summary", maybeChanges.flatMap(CalendarEventNotificationEmailDTO.Changes::summary)
                .map(CalendarEventNotificationEmailDTO.StringChange::previous)
                .orElse(summary),
            "subject.organizer", organizer.cn()
        );
    }

    private Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay, CalendarEventNotificationEmailDTO.Changes changes) {
        ImmutableMap.Builder<String, Object> changesBuilder = ImmutableMap.builder();
        changes.summary().ifPresent(summaryChange -> {
            changesBuilder.put("summary", ImmutableMap.of(
                "previous", summaryChange.previous()
            ));
        });
        changes.dtstart().ifPresent(dtstartChange -> {
            changesBuilder.put("isOldEventAllDay", dtstartChange.previous().isAllDay());
            changesBuilder.put("dtstart", ImmutableMap.of(
                "previous", toEventTimeModel(dtstartChange).toPugModel(locale, zoneToDisplay)
            ));
        });
        changes.dtend().ifPresent(dtendChange ->
            changesBuilder.put("dtend", ImmutableMap.of(
                "previous", toEventTimeModel(dtendChange).toPugModel(locale, zoneToDisplay)
        )));
        changes.location().ifPresent(locationChange ->
            changesBuilder.put("location", ImmutableMap.of(
                "previous", locationChange.previous()
        )));
        changes.description().ifPresent(descriptionChange ->
            changesBuilder.put("description", ImmutableMap.of(
                "previous", descriptionChange.previous()
        )));
        return changesBuilder.build();
    }

    private EventTimeModel toEventTimeModel(CalendarEventNotificationEmailDTO.DateTimeChange dateTimeChange) {
        LocalDateTime ldt = LocalDateTime.parse(dateTimeChange.previous().date(), DATE_TIME_FORMATTER);
        ZoneId zoneId = ZoneId.of(dateTimeChange.previous().timezone());
        ZonedDateTime zonedDateTime = ldt.atZone(zoneId);
        return new EventTimeModel(zonedDateTime);
    }
}
