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

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.api.EventParticipationActionLinkFactory.ActionLinks;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.component.VEvent;

public record CalendarEventUpdateNotificationEmail(CalendarEventNotificationEmail base,
                                                   Optional<CalendarEventNotificationEmailDTO.Changes> maybeChanges) {

    // Lenient on purpose: 'T' or space separator, optional fraction of second (0 to 9 digits)
    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .optionalStart().appendLiteral(' ').optionalEnd()
        .optionalStart().appendLiteral('T').optionalEnd()
        .appendPattern("HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .toFormatter();

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventUpdateNotificationEmail.class);

    public static CalendarEventUpdateNotificationEmail from(CalendarEventNotificationEmailDTO dto) {
        return new CalendarEventUpdateNotificationEmail(
            CalendarEventNotificationEmail.from(dto),
            dto.changes()
        );
    }

    public Map<String, Object> toPugModel(Locale locale,
                                          ZoneId zoneToDisplay,
                                          EventInCalendarLinkFactory eventInCalendarLinkFactory,
                                          boolean isInternalUser,
                                          Optional<ActionLinks> actionLinks) {
        VEvent vEvent = base.getFirstVEvent();
        PersonModel organizer = PersonModel.from(EventParseUtils.getOrganizer(vEvent));
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", base.toPugModel(locale, zoneToDisplay));
        actionLinks.ifPresent(links -> contentBuilder
            .put("yesLink", links.yes())
            .put("noLink", links.no())
            .put("maybeLink", links.maybe()));
        if (isInternalUser) {
            contentBuilder.put("seeInCalendarLink", eventInCalendarLinkFactory.getEventInCalendarLink(startDate));
        }

        maybeChanges.ifPresent(changes -> contentBuilder.put("changes", toPugModel(locale, zoneToDisplay, changes)));

        return ImmutableMap.of(
            "content", contentBuilder.build(),
            "subject.summary", maybeChanges.flatMap(CalendarEventNotificationEmailDTO.Changes::summary)
                .map(CalendarEventNotificationEmailDTO.StringChange::previous)
                .orElse(summary),
            "subject.organizer", StringUtils.defaultIfEmpty(organizer.cn(), organizer.email()));
    }

    private Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay, CalendarEventNotificationEmailDTO.Changes changes) {
        ImmutableMap.Builder<String, Object> changesBuilder = ImmutableMap.builder();
        changes.summary().ifPresent(summaryChange -> changesBuilder.put("summary", toPugModel(summaryChange)));
        putPreviousTimes(changesBuilder, locale, zoneToDisplay, changes);
        changes.location().ifPresent(locationChange -> changesBuilder.put("location", toPugModel(locationChange)));
        changes.description().ifPresent(descriptionChange -> changesBuilder.put("description", toPugModel(descriptionChange)));
        boolean isOldEventAllDay = Stream.of(changes.dtstart(), changes.dtend())
            .flatMap(Optional::stream)
            .map(CalendarEventNotificationEmailDTO.DateTimeChange::previous)
            .filter(Objects::nonNull)
            .findFirst()
            .map(CalendarEventNotificationEmailDTO.DateTimeValue::isAllDay)
            .orElse(false);
        changesBuilder.put("isOldEventAllDay", isOldEventAllDay);
        return changesBuilder.build();
    }

    private Map<String, Object> toPugModel(CalendarEventNotificationEmailDTO.StringChange stringChange) {
        // An added property may come with an absent previous value: render it as empty (the template then tags it as added)
        return ImmutableMap.of("previous", StringUtils.defaultString(stringChange.previous()));
    }

    // An unreadable previous start or end omits the whole "previous time" line rather than rendering a misleading half of it
    private void putPreviousTimes(ImmutableMap.Builder<String, Object> changesBuilder, Locale locale, ZoneId zoneToDisplay,
                                  CalendarEventNotificationEmailDTO.Changes changes) {
        Optional<Optional<EventTimeModel>> previousStart = changes.dtstart().map(dtstart -> toEventTimeModel(dtstart, zoneToDisplay));
        Optional<Optional<EventTimeModel>> previousEnd = changes.dtend().map(dtend -> toEventTimeModel(dtend, zoneToDisplay));
        boolean anyUnreadable = Stream.of(previousStart, previousEnd)
            .flatMap(Optional::stream)
            .anyMatch(Optional::isEmpty);
        if (anyUnreadable) {
            return;
        }
        previousStart.flatMap(Function.identity()).ifPresent(start -> changesBuilder.put("dtstart", ImmutableMap.of(
            "previous", start.toPugModel(locale, zoneToDisplay))));
        previousEnd.flatMap(Function.identity()).ifPresent(end -> changesBuilder.put("dtend", ImmutableMap.of(
            "previous", end.toPugModel(locale, zoneToDisplay))));
    }

    private Optional<EventTimeModel> toEventTimeModel(CalendarEventNotificationEmailDTO.DateTimeChange dateTimeChange, ZoneId fallbackZone) {
        return Optional.ofNullable(dateTimeChange.previous())
            .filter(previous -> previous.date() != null)
            .flatMap(previous -> toEventTimeModel(previous, fallbackZone));
    }

    private Optional<EventTimeModel> toEventTimeModel(CalendarEventNotificationEmailDTO.DateTimeValue previous, ZoneId fallbackZone) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(previous.date().trim(), DATE_TIME_FORMATTER);
            return Optional.of(new EventTimeModel(localDateTime.atZone(resolveZone(previous.timezone(), fallbackZone))));
        } catch (DateTimeException e) {
            LOGGER.warn("Unreadable previous date time '{}' with timezone '{}', omitting it from the update mail",
                previous.date(), previous.timezone(), e);
            return Optional.empty();
        }
    }

    // Unknown timezones (e.g. Windows names not mapped, abbreviations) fall back to the display zone rather than dropping the line
    private ZoneId resolveZone(String timezone, ZoneId fallbackZone) {
        return Optional.ofNullable(timezone)
            .flatMap(EventParseUtils::extractZoneId)
            .orElse(fallbackZone);
    }
}
