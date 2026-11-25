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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.property.DateProperty;

final class IcsDateParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(IcsDateParser.class);

    static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FLEXIBLE_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd'T'HHmmss")
        .optionalStart()
        .appendPattern("X")
        .optionalEnd()
        .toFormatter();

    private IcsDateParser() {
    }

    static Optional<ZonedDateTime> parse(Property property) {
        try {
            return parsedZonedDateTime(property)
                .or(() -> Optional.of(parseText(property)));
        } catch (Exception exception) {
            LOGGER.info("Failed to parse time: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<ZonedDateTime> parsedZonedDateTime(Property property) {
        if (!(property instanceof DateProperty<?> dateProperty)) {
            return Optional.empty();
        }
        try {
            // Use the same timezone resolution as ical4j applies to EXDATE and RECURRENCE-ID.
            return dateProperty.getDate() instanceof ZonedDateTime zonedDateTime
                ? Optional.of(zonedDateTime)
                : Optional.empty();
        } catch (DateTimeException exception) {
            LOGGER.debug("Could not read parsed {}: falling back to its text value", property.getName(), exception);
            return Optional.empty();
        }
    }

    private static ZonedDateTime parseText(Property property) {
        String value = property.getValue();
        if (isDateType(property)) {
            return LocalDate.parse(value, DATE_FORMATTER).atStartOfDay(ZoneOffset.UTC);
        }
        return property.getParameter(Parameter.TZID)
            .map(tzId -> TimeZone.getTimeZone(tzId.getValue()).toZoneId())
            .map(zoneId -> LocalDateTime.parse(value, DATE_TIME_FORMATTER).atZone(zoneId))
            .orElseGet(() -> parseDateTime(value));
    }

    private static ZonedDateTime parseDateTime(String value) {
        TemporalAccessor temporal = FLEXIBLE_DATE_TIME_FORMATTER.parse(value);
        if (temporal.isSupported(ChronoField.OFFSET_SECONDS)) {
            return ZonedDateTime.from(temporal);
        }
        return LocalDateTime.from(temporal).atZone(ZoneOffset.UTC);
    }

    static boolean isDateType(Property property) {
        return property.getParameter(Parameter.VALUE)
            .map(parameter -> "DATE".equals(parameter.getValue()))
            .orElse(false);
    }
}
