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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RDate;
import net.fortuna.ical4j.model.property.RRule;

public class CalendarEventUtils {

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

    private static boolean recurrenceExpired(VEvent vevent, ZonedDateTime startDateTime, Instant now) {
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
            .flatMap(rDate -> ((List<Temporal>) rDate.getDates()).stream())
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

    private static boolean hasUpcomingOccurrenceFromRRule(ZonedDateTime startDateTime,
                                                          Recur<Temporal> recur,
                                                          Set<Instant> excludedDates,
                                                          Instant now) {
        Temporal current = startDateTime;

        while (true) {
            Optional<Instant> instantOpt = toInstant(current);
            if (instantOpt.isPresent()) {
                Instant instant = instantOpt.get();
                if (!excludedDates.contains(instant) && !instant.isBefore(now)) {
                    return true;
                }
            }

            Temporal next = recur.getNextDate(startDateTime, current);
            if (next == null || next.equals(current)) {
                return false;
            }
            current = next;
        }
    }

    private static Set<Instant> extractExcludedDates(VEvent vevent) {
        return vevent.getProperties(Property.EXDATE).stream()
            .map(ExDate.class::cast)
            .flatMap(exDate -> ((List<Temporal>) exDate.getDates()).stream()
                .flatMap(date -> toInstant(date).stream()))
            .collect(Collectors.toSet());
    }

    private static Optional<Instant> toInstant(Temporal temporal) {
        if (temporal instanceof LocalDate localDate) {
            return Optional.of(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        return EventParseUtils.temporalToZonedDateTime(temporal)
            .map(ZonedDateTime::toInstant);
    }
}
