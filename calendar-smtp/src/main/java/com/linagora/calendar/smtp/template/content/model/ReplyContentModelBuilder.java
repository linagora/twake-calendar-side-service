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

package com.linagora.calendar.smtp.template.content.model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

import net.fortuna.ical4j.model.parameter.PartStat;

public class ReplyContentModelBuilder {

    public static EventSummaryStep builder() {
        return eventSummary -> eventAllDay -> eventStart -> eventEnd ->
            eventLocation -> (eventAttendee, partStat) ->
                eventOrganizer -> eventResources -> eventDescription ->
                    locale -> displayZoneId -> translator -> eventInCalendarLinkFactory -> () -> {

                        ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
                        builder.put("partStat", partStat.getValue().toUpperCase(Locale.US));
                        builder.put("partStatColor", partStatColor(partStat));
                        builder.put("content.replyPersonDisplayName", eventAttendee.cn());
                        builder.put("content.replyMessage", translator.get("reply_message_" + partStat.getValue().toLowerCase(Locale.US)));
                        builder.put("content.event.summary", eventSummary);
                        builder.put("content.event.allDay", eventAllDay);
                        builder.put("content.event.start", new EventTimeModel(eventStart).toPugModel(locale, displayZoneId));
                        builder.put("content.seeInCalendarLink", eventInCalendarLinkFactory.getEventInCalendarLink(eventStart));
                        builder.put("content.event.attendees", Map.of(eventAttendee.email(), eventAttendee.toPugModel()));
                        builder.put("content.event.organizer", eventOrganizer.toPugModel());

                        builder.put("content.event.hasResources", !eventResources.isEmpty());
                        eventEnd.ifPresent(end ->
                            builder.put("content.event.end", new EventTimeModel(end).toPugModel(locale, displayZoneId)));

                        eventLocation.ifPresent(location ->
                            builder.put("content.event.location", new LocationModel(location).toPugModel()));

                        eventDescription.ifPresent(description ->
                            builder.put("content.event.description", description));

                        String eventResourcesJoined = eventResources.stream()
                            .map(PersonModel::cn)
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining(", "));

                        builder.put("content.event.resourcesJoined", eventResourcesJoined);

                        builder.put("subject.partStatDisplay", translator.get(partStat.getValue().toLowerCase(Locale.US)));
                        builder.put("subject.summary", eventSummary);
                        builder.put("subject.participant", eventAttendee.cn());
                        return builder.build();
                    };
    }

    private static String partStatColor(PartStat partStat) {
        return switch (partStat.getValue().toLowerCase(Locale.US)) {
            case "accepted" -> "#deffe1";
            case "declined" -> "#ffdede";
            case "tentative" -> "#fffcde";
            default -> "#deffe1";
        };
    }

    public interface LocaleStep {
        TimeZoneDisplayStep locale(Locale locale);
    }

    public interface TimeZoneDisplayStep {
        TranslatorStep timeZoneDisplay(ZoneId zoneId);
    }

    public interface TranslatorStep {
        EventInCalendarLinkStep translator(I18NTranslator translator);
    }

    public interface EventInCalendarLinkStep {
        FinalStep eventInCalendarLink(EventInCalendarLinkFactory value);
    }

    public interface EventSummaryStep {
        EventAllDayStep eventSummary(String value);
    }

    public interface EventAllDayStep {
        EventStartStep eventAllDay(boolean value);
    }

    public interface EventStartStep {
        EventEndStep eventStart(ZonedDateTime value);
    }

    public interface EventEndStep {
        EventLocationStep eventEnd(Optional<ZonedDateTime> value);
    }

    public interface EventLocationStep {
        EventAttendeeStep eventLocation(Optional<String> value);
    }

    public interface EventAttendeeStep {
        EventOrganizerStep eventAttendee(PersonModel value, PartStat partStat);
    }

    public interface EventOrganizerStep {
        EventResourcesStep eventOrganizer(PersonModel value);
    }

    public interface EventResourcesStep {
        EventDescriptionStep eventResources(List<PersonModel> value);
    }

    public interface EventDescriptionStep {
        LocaleStep eventDescription(Optional<String> value);
    }

    public interface FinalStep {
        Map<String, Object> buildAsMap();
    }
}
