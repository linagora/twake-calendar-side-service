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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Summary;

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
                    .orElseGet(() -> ZonedDateTime.parse(value, UTC_DATE_TIME_FORMATTER)));
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

    public static List<EventFields.Person> getPeople(VEvent vEvent, boolean getResource) {
        return vEvent.getProperties(Property.ATTENDEE)
            .stream()
            .filter(attendee -> attendee.getParameter(Parameter.CUTYPE)
                .map(parameter -> getResource == "RESOURCE".equals(parameter.getValue()))
                .orElse(false))
            .map(EventParseUtils::toPerson)
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<EventFields.Person> toPerson(Property property) {
        try {
            String cn = property.getParameter(Parameter.CN).map(Parameter::getValue).orElse("");
            String email = StringUtils.removeStartIgnoreCase(property.getValue(), "mailto:");
            Optional<PartStat> partStat = property.getParameter(Parameter.PARTSTAT)
                .map(value -> (PartStat) value);
            return Optional.of(new EventFields.Person(cn, new MailAddress(email), partStat));
        } catch (AddressException e) {
            LOGGER.info("Invalid person: {}", property.getValue());
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

    public static ZonedDateTime getStartTime(VEvent vEvent) {
        return vEvent.getProperty(Property.DTSTART)
            .flatMap(EventParseUtils::parseTime)
            .orElseThrow(() -> new IllegalStateException("DTSTART property is missing or invalid"));
    }

    public static Optional<ZonedDateTime> getEndTime(VEvent vEvent) {
        return vEvent.getProperty(Property.DTEND)
            .flatMap(EventParseUtils::parseTime);
    }

    public static boolean isAllDay(VEvent vEvent) {
        return vEvent.getProperty(Property.DTSTART)
            .map(EventParseUtils::isDateType)
            .orElse(false);
    }
}
