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

package com.linagora.calendar.amqp;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.DateListProperty;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RDate;
import net.fortuna.ical4j.model.property.RRule;

public class CalendarEventUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventUtils.class);
    @VisibleForTesting
    static final int MAX_EXAMINED_OCCURRENCES = Integer.getInteger("tcalendar.itip.max.examined.occurences", 20_000);
    private static final ZonedDateTime LAST_EXPANDED_DATE = ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);

    public static boolean vEventExpired(VEvent vevent, Clock clock) {
        Optional<ZonedDateTime> startDateTime = vevent.getProperty(Property.DTSTART)
            .flatMap(EventParseUtils::parseTime);
        if (startDateTime.isEmpty()) {
            return false;
        }

        Instant now = clock.instant();
        if (hasNoRecurrence(vevent)) {
            return startDateTime.get().toInstant().isBefore(now);
        }

        return recurrenceExpired(vevent, startDateTime.get(), now);
    }

    /**
     * An unreadable RDATE may designate an upcoming occurrence: expiry is then uncertain and the event is kept.
     * An unreadable EXDATE can only exclude more occurrences, ignoring it errs on the same safe side.
     */
    private static boolean recurrenceExpired(VEvent vevent, ZonedDateTime startDateTime, Instant now) {
        if (hasUnreadableDates(vevent, Property.RDATE)) {
            return false;
        }
        Set<Instant> excludedDates = extractExcludedDates(vevent);
        boolean hasUpcomingFromRRule = hasUpcomingFromRRule(vevent, startDateTime, excludedDates, now);
        boolean hasUpcomingFromRDate = hasUpcomingRDate(vevent, excludedDates, now);
        return !hasUpcomingFromRRule && !hasUpcomingFromRDate;
    }

    private static boolean hasUpcomingFromRRule(VEvent vevent, ZonedDateTime startDateTime, Set<Instant> excludedDates, Instant now) {
        return extractRecur(vevent)
            .map(recur -> isInfinite(recur) || hasUpcomingOccurrenceFromRRule(startDateTime, recur, excludedDates, now))
            .orElseGet(() -> !startDateTime.toInstant().isBefore(now));
    }

    private static boolean hasUpcomingRDate(VEvent vevent, Set<Instant> excludedDates, Instant now) {
        return vevent.getProperties(Property.RDATE).stream()
            .filter(RDate.class::isInstance)
            .map(RDate.class::cast)
            .flatMap(CalendarEventUtils::readableDates)
            .flatMap(date -> toInstant(date).stream())
            .anyMatch(instant -> !excludedDates.contains(instant) && !instant.isBefore(now));
    }

    private static Optional<Recur<Temporal>> extractRecur(VEvent vEvent) {
        return vEvent.getProperty(Property.RRULE)
            .filter(RRule.class::isInstance)
            .map(RRule.class::cast)
            .map(RRule::getRecur)
            .map(recur -> (Recur<Temporal>) recur);
    }

    private static boolean hasNoRecurrence(VEvent vevent) {
        return vevent.getProperty(Property.RRULE).isEmpty() && vevent.getProperty(Property.RDATE).isEmpty();
    }

    private static boolean isInfinite(Recur<Temporal> recur) {
        return recur.getUntil() == null && recur.getCount() <= 0;
    }

    /**
     * Walks the occurrences once, from DTSTART on: ical4j regenerates them from the seed on each call, as COUNT
     * applies from DTSTART. A rule with more than {@link #MAX_EXAMINED_OCCURRENCES} occurrences before {@code now} is
     * deemed not expired, like infinite rules: expiry is then uncertain and the event is kept.
     */
    private static boolean hasUpcomingOccurrenceFromRRule(ZonedDateTime startDateTime,
                                                          Recur<Temporal> recur,
                                                          Set<Instant> excludedDates,
                                                          Instant now) {
        if (endsBefore(recur, now)) {
            return false;
        }
        Iterator<Temporal> occurrences = recur.getDatesAsStream(startDateTime, startDateTime, LAST_EXPANDED_DATE, -1)
            .iterator();
        for (int examined = 0; examined < MAX_EXAMINED_OCCURRENCES; examined++) {
            if (!occurrences.hasNext()) {
                return false;
            }
            Optional<Instant> instant = toInstant(occurrences.next());
            if (instant.isPresent() && !excludedDates.contains(instant.get()) && !instant.get().isBefore(now)) {
                return true;
            }
        }
        LOGGER.info("More than {} occurrences of {} before {}: considering the event as not expired", MAX_EXAMINED_OCCURRENCES, recur, now);
        return true;
    }

    private static boolean endsBefore(Recur<Temporal> recur, Instant now) {
        return Optional.ofNullable(recur.getUntil())
            .flatMap(CalendarEventUtils::toInstant)
            .map(until -> until.isBefore(now))
            .orElse(false);
    }

    private static Set<Instant> extractExcludedDates(VEvent vevent) {
        return vevent.getProperties(Property.EXDATE).stream()
            .map(ExDate.class::cast)
            .flatMap(CalendarEventUtils::readableDates)
            .flatMap(date -> toInstant(date).stream())
            .collect(Collectors.toSet());
    }

    /**
     * ical4j parses date lists lazily: an unreadable value only fails on {@link DateListProperty#getDates()}.
     * Such a property yields no date: callers needing certainty should check {@link #hasUnreadableDates}.
     */
    public static Stream<Temporal> readableDates(DateListProperty<?> property) {
        return parseDates(property)
            .stream()
            .flatMap(List::stream);
    }

    public static boolean hasUnreadableDates(VEvent vevent, String propertyName) {
        return vevent.getProperties(propertyName).stream()
            .filter(DateListProperty.class::isInstance)
            .map(property -> (DateListProperty<?>) property)
            .anyMatch(property -> parseDates(property).isEmpty());
    }

    /**
     * ical4j keeps leniently parsed date values as text and parses them again on rendering: such a VEVENT cannot be
     * serialized. Returns the VEVENT without its unreadable date properties, consistent with {@link #readableDates}.
     *
     * <p>The supplied VEVENT is left untouched: it may be shared with other calendars.
     */
    public static VEvent withoutUnreadableDates(VEvent vevent) {
        List<Property> readableProperties = vevent.getProperties().stream()
            .filter(CalendarEventUtils::isRenderable)
            .toList();
        if (readableProperties.size() == vevent.getProperties().size()) {
            return vevent;
        }
        return new VEvent(new PropertyList(readableProperties), new ComponentList<>(vevent.getAlarms()));
    }

    private static boolean isRenderable(Property property) {
        if (!(property instanceof DateProperty<?> || property instanceof DateListProperty<?>)) {
            return true;
        }
        try {
            property.getValue();
            return true;
        } catch (DateTimeException e) {
            // getValue() is the failing parse: the exception message carries the faulty text
            LOGGER.info("Dropping unreadable {} property from the notification payload", property.getName(), e);
            return false;
        }
    }

    /**
     * An RDATE of PERIOD value carries periods instead of dates: each period start is an occurrence.
     */
    private static Optional<List<Temporal>> parseDates(DateListProperty<?> property) {
        try {
            if (property instanceof RDate<?> rDate && isPeriodValued(rDate)) {
                return periodStarts(rDate);
            }
            return Optional.of((List<Temporal>) property.getDates());
        } catch (DateTimeException e) {
            // getValue() would parse again: the exception message carries the faulty text
            LOGGER.info("Unreadable {} property", property.getName(), e);
            return Optional.empty();
        }
    }

    private static boolean isPeriodValued(RDate<?> rDate) {
        return rDate.getParameter(Parameter.VALUE)
            .map(Value.PERIOD::equals)
            .orElse(false);
    }

    private static Optional<List<Temporal>> periodStarts(RDate<?> rDate) {
        return rDate.getPeriods()
            .map(periods -> periods.stream()
                .map(period -> (Temporal) period.getStart())
                .toList());
    }

    private static Optional<Instant> toInstant(Temporal temporal) {
        if (temporal instanceof LocalDate localDate) {
            return Optional.of(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        return EventParseUtils.temporalToZonedDateTime(temporal)
            .map(ZonedDateTime::toInstant);
    }
}
