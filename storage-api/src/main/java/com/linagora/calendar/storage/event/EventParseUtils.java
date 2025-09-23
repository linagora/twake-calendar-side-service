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

package com.linagora.calendar.storage.event;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.field.address.LenientAddressParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import jakarta.mail.internet.AddressException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;

public class EventParseUtils {
    public static final Logger LOGGER = LoggerFactory.getLogger(EventParseUtils.class);

    public static final DateTimeFormatter UTC_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmssX")
        .toFormatter();
    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmss")
        .toFormatter();
    public static final DateTimeFormatter DATE_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd")
        .toFormatter();

    private static final DateTimeFormatter FLEXIBLE_DATE_TIME_FORMATTER =
        new DateTimeFormatterBuilder()
            .appendPattern("yyyyMMdd'T'HHmmss")
            .optionalStart()
            .appendPattern("X")
            .optionalEnd()
            .toFormatter();


    private static final boolean GET_RESOURCE = true;
    private static final boolean GET_ATTENDEE = false;

    public static Optional<ZonedDateTime> parseTime(Property property) {
        try {
            String value = property.getValue();
            if (isDateType(property)) {
                LocalDate localDate = LocalDate.from(DATE_FORMATTER.parse(value));
                return Optional.of(localDate.atStartOfDay(ZoneOffset.UTC));
            } else {
                return Optional.of(property.getParameter(Parameter.TZID)
                    .map(tzId -> TimeZone.getTimeZone(tzId.getValue()).toZoneId())
                    .map(zoneId -> LocalDateTime.parse(value, DATE_TIME_FORMATTER).atZone(zoneId))
                    .orElseGet(() -> {
                        TemporalAccessor temporalAccessor = FLEXIBLE_DATE_TIME_FORMATTER.parse(value);
                        if (temporalAccessor.isSupported(ChronoField.OFFSET_SECONDS)) {
                            return ZonedDateTime.from(temporalAccessor);
                        } else {
                            return LocalDateTime.from(temporalAccessor).atZone(ZoneOffset.UTC);
                        }
                    }));
            }
        } catch (Exception e) {
            LOGGER.info("Failed to parse time: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<Instant> parseTimeAsInstant(Property property) {
        return parseTime(property)
            .map(ZonedDateTime::toInstant);
    }

    public static boolean isDateType(Property prop) {
        return prop.getParameter(Parameter.VALUE).map(parameter -> "DATE".equals(parameter.getValue())).orElse(false);
    }

    public static List<EventFields.Person> getAttendees(VEvent vEvent) {
        return getPeople(vEvent, GET_ATTENDEE);
    }

    public static List<EventFields.Person> getResources(VEvent vEvent) {
        return getPeople(vEvent, GET_RESOURCE);
    }

    public static EventFields.Person getOrganizer(VEvent vEvent) {
        return vEvent.getProperty(Property.ORGANIZER)
            .flatMap(EventParseUtils::toPerson)
            .orElse(null);
    }

    private static Predicate<Property> attendeeFilter(boolean getResource) {
        return attendee -> {
            if (getResource) {
                return attendee.getParameter(Parameter.CUTYPE)
                    .map(parameter -> "RESOURCE".equalsIgnoreCase(parameter.getValue()))
                    .orElse(false);
            } else {
                return attendee.getParameter(Parameter.CUTYPE).isEmpty()
                    || !"RESOURCE".equalsIgnoreCase(attendee.getParameter(Parameter.CUTYPE).get().getValue());
            }
        };
    }

    public static List<EventFields.Person> getPeople(VEvent vEvent, boolean getResource) {
        return vEvent.getProperties(Property.ATTENDEE)
            .stream()
            .filter(attendeeFilter(getResource))
            .map(EventParseUtils::toPerson)
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<EventFields.Person> toPerson(Property property) {
        try {
            String cn = property.getParameter(Parameter.CN).map(Parameter::getValue).orElse("");
            MailAddress email = getEmail(property);
            Optional<PartStat> partStat = property.getParameter(Parameter.PARTSTAT)
                .map(value -> (PartStat) value);
            return Optional.of(new EventFields.Person(cn, email, partStat));
        } catch (AddressException | MalformedURLException e) {
            LOGGER.error("Invalid person: {}", property.getValue());
            return Optional.empty();
        }
    }

    public static Optional<Boolean> isRecurrentMaster(VEvent vEvent) {
        if (vEvent.getProperty(Property.RECURRENCE_ID).isPresent()) {
            return Optional.of(false);
        }
        if (vEvent.getProperty(Property.RRULE).isPresent()) {
            return Optional.of(true);
        }
        return Optional.empty();
    }

    public static Optional<String> getSummary(VEvent vEvent) {
        return Optional.ofNullable(vEvent.getSummary())
            .map(Summary::getValue);
    }

    public static Optional<String> getLocation(VEvent vEvent) {
        return Optional.ofNullable(vEvent.getLocation())
            .map(Location::getValue);
    }

    public static Optional<String> getDescription(VEvent vEvent) {
        return Optional.ofNullable(vEvent.getDescription())
            .map(Description::getValue);
    }

    public static Optional<String> getPropertyValueIgnoreCase(VEvent vEvent, String property) {
        return vEvent.getProperties().stream()
            .filter(p -> Strings.CI.equals(property, p.getName()))
            .map(Property::getValue)
            .filter(StringUtils::isNotBlank)
            .findFirst();
    }

    public static ZonedDateTime getStartTime(VEvent vEvent) {
        return vEvent.getProperty(Property.DTSTART)
            .flatMap(EventParseUtils::parseTime)
            .orElseThrow(() -> new IllegalStateException("DTSTART property is missing or invalid:\n" + vEvent));
    }

    public static Optional<ZonedDateTime> getEndTime(VEvent vEvent) {
        return vEvent.getProperty(Property.DTEND)
            .flatMap(EventParseUtils::parseTime)
            .or(() -> Optional.ofNullable(vEvent.getDuration())
                .map(icsDuration -> getStartTime(vEvent).plus(icsDuration.getDuration())));
    }

    public static boolean isAllDay(VEvent vEvent) {
        return vEvent.getProperty(Property.DTSTART)
            .map(EventParseUtils::isDateType)
            .orElse(false);
    }

    public static Optional<ZoneId> getZoneIdFromStartDate(VEvent calendarEvent) {
        return calendarEvent.getDateTimeStart()
            .getParameter(Parameter.TZID)
            .map(Content::getValue)
            .flatMap(EventParseUtils::extractZoneId);
    }

    public static Optional<ZonedDateTime> temporalToZonedDateTime(Temporal temporal) {
        return temporalToZonedDateTime(temporal, ZoneId.of("UTC"));
    }

    public static Optional<ZonedDateTime> temporalToZonedDateTime(Temporal temporal, ZoneId zoneIdDefault) {
        return switch (temporal) {
            case ZonedDateTime zdt -> Optional.of(zdt);
            case OffsetDateTime odt -> Optional.of(odt.atZoneSameInstant(zoneIdDefault));
            case LocalDateTime ldt -> Optional.of(ldt.atZone(zoneIdDefault));
            case Instant instant -> Optional.of(instant.atZone(zoneIdDefault));
            case null, default -> Optional.empty();
        };
    }

    public static ZoneId getAlternativeZoneId(VEvent calendarEvent) {
        return getZoneIdFromTZID(calendarEvent).orElse(ZoneId.of("UTC"));
    }

    public static Optional<ZoneId> getZoneIdFromTZID(VEvent calendarEvent) {
        return calendarEvent.getProperty(Property.TZID)
            .map(Property::getValue)
            .flatMap(EventParseUtils::extractZoneId);
    }

    private static Optional<ZoneId> extractZoneId(String value) {
        // Try IANA zone
        try {
            return Optional.of(ZoneId.of(value));
        } catch (Exception ignored) {
            // Fallback to Windows → IANA using ICU4J
            try {
                String ianaEquivalent = com.ibm.icu.util.TimeZone.getIDForWindowsID(value, "US");
                if (ianaEquivalent != null) {
                    return Optional.of(ZoneId.of(ianaEquivalent));
                }
            } catch (Exception ignored2) {
                // do nothing
            }
        }
        return Optional.empty();
    }

    public static boolean isRecurringEvent(Calendar calendar) {
        List<CalendarComponent> events = calendar.getComponents(Component.VEVENT);
        Preconditions.checkArgument(!events.isEmpty(), "VEVENT is empty");

        return events.size() > 1 || events.getFirst().getProperty(Property.RRULE).isPresent();
    }

    public static String extractEventUid(Calendar calendar) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(Component::getUid)
            .flatMap(Optional::stream)
            .map(Property::getValue)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No UID found in the calendar event"));
    }

    public static boolean isCancelled(VEvent event) {
        return event.getStatus() != null && "CANCELLED".equalsIgnoreCase(event.getStatus().getValue());
    }

    public static Map<String, List<VEvent>> groupByUid(Calendar calendar) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .collect(Collectors.groupingBy(v ->
                v.getUid()
                    .map(Uid::getValue)
                    .orElseThrow(() ->
                        new IllegalArgumentException("VEVENT is missing UID, invalid ICS"))));
    }

    private static MailAddress getEmail(Property property) throws MalformedURLException, AddressException {
        try {
            String email = Strings.CI.removeStart(property.getValue(), "mailto:");
            return new MailAddress(email);
        } catch (AddressException e) {
            // Try to decode URL-encoded email
            String decoded = URLDecoder.decode(URI.create(property.getValue()).toURL().getPath(), StandardCharsets.UTF_8);
            return new MailAddress(LenientAddressParser.DEFAULT.parseAddress(decoded).toString());
        }
    }
}
