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

package com.linagora.calendar.api.booking;

import static com.linagora.calendar.api.booking.AvailableSlotsCalculator.validateTimeRange;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;

public sealed interface AvailabilityRule permits FixedAvailabilityRule, WeeklyAvailabilityRule {
    List<Range<Instant>> availabilityRanges(Instant start, Instant end);

    record FixedAvailabilityRule(ZonedDateTime start, ZonedDateTime end) implements AvailabilityRule {
        public FixedAvailabilityRule {
            validateTimeRange(start, end);
        }

        @Override
        public List<Range<Instant>> availabilityRanges(Instant start, Instant end) {
            validateTimeRange(start, end);
            Range<Instant> ruleRange = Range.closedOpen(this.start.toInstant(), this.end.toInstant());
            Range<Instant> requestRange = Range.closedOpen(start, end);

            if (!ruleRange.isConnected(requestRange)) {
                return List.of();
            }
            return List.of(ruleRange.intersection(requestRange));
        }
    }

    record WeeklyAvailabilityRule(DayOfWeek dayOfWeek, LocalTime start, LocalTime end,
                                  Optional<ZoneId> timeZone) implements AvailabilityRule {
        public static final ZoneOffset DEFAULT_ZONE_OFFSET = ZoneOffset.UTC;

        public WeeklyAvailabilityRule(DayOfWeek dayOfWeek, LocalTime start, LocalTime end, ZoneId timeZone) {
            this(dayOfWeek, start, end, Optional.ofNullable(timeZone));
        }

        public WeeklyAvailabilityRule(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
            this(dayOfWeek, start, end, Optional.empty());
        }

        public WeeklyAvailabilityRule {
            Preconditions.checkNotNull(dayOfWeek, "'dayOfWeek' must not be null");
            Preconditions.checkNotNull(start, "'start' must not be null");
            Preconditions.checkNotNull(end, "'end' must not be null");
            Preconditions.checkNotNull(timeZone, "'timeZone' must not be null");
            Preconditions.checkArgument(start.isBefore(end), "'start' must be before 'end'");
        }

        @Override
        public List<Range<Instant>> availabilityRanges(Instant start, Instant end) {
            validateTimeRange(start, end);
            ZoneId resolvedTimeZone = timeZone.orElse(DEFAULT_ZONE_OFFSET);
            LocalDate firstDate = start.atZone(resolvedTimeZone).toLocalDate();
            LocalDate lastDate = end.atZone(resolvedTimeZone).toLocalDate();

            return firstDate.datesUntil(lastDate.plusDays(1))
                .filter(localDate -> localDate.getDayOfWeek() == this.dayOfWeek)
                .map(localDate -> Range.closedOpen(
                    localDate.atTime(this.start).atZone(resolvedTimeZone).toInstant(),
                    localDate.atTime(this.end).atZone(resolvedTimeZone).toInstant()))
                .toList();
        }
    }
}
