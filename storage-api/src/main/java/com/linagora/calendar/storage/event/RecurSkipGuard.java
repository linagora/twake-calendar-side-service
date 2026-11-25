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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;

import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.transform.recurrence.Frequency;

/**
 * Tells whether ical4j can expand a rule over a walk in bounded time and memory:
 * <ul>
 *     <li>Without COUNT, ical4j reaches the start of the walk by doubling an int multiplier of the rule's period. It can
 *     only get as far as what this int holds: past it, the multiplier overflows to 0 and the search never ends, nor can it
 *     be interrupted. Whatever the interval, the last multiplier before the overflow goes at least 2^30 units of the
 *     frequency. It then walks each period up to the end of the walk.</li>
 *     <li>With a COUNT, ical4j does not skip: it walks every period from the seed, keeping each candidate before the
 *     start of the walk, until it has COUNT candidates or reaches the end of the walk. Periods without candidate do not
 *     count: COUNT bounds the walk only when every period holds one.</li>
 *     <li>In each walked period, ical4j builds the whole product of the BY* lists, repeated values included, before
 *     filtering it: {@code FREQ=YEARLY;BYMONTHDAY=1,...,31;BYHOUR=0,...,23;BYMINUTE=0,...,59;BYSECOND=0,...,59} builds
 *     32 million dates in its first period.</li>
 * </ul>
 * The walked periods times the candidates of a period are bounded by {@link #MAX_EXPANDED_CANDIDATES}.
 */
final class RecurSkipGuard {
    private static final long MAX_SKIPPED_UNITS = 1L << 30;
    /**
     * Every real world rule stays far below it: a rule on the five working days with a COUNT of 81920 builds 409,600
     * candidates.
     */
    @VisibleForTesting
    static final long MAX_EXPANDED_CANDIDATES = Long.getLong("tcalendar.recur.max.expanded.candidates", 1_000_000L);
    /**
     * A week-based year, plus the week it may start or end with across a calendar year.
     */
    private static final Duration MAPPED_BACK_OVERRUN = Duration.ofDays(366 + 7);
    private static final Set<Frequency> FINER_THAN_HOURLY = EnumSet.of(Frequency.SECONDLY, Frequency.MINUTELY);
    private static final Set<Frequency> FINER_THAN_DAILY = EnumSet.of(Frequency.SECONDLY, Frequency.MINUTELY, Frequency.HOURLY);
    private static final Set<Frequency> FINER_THAN_WEEKLY = EnumSet.of(Frequency.SECONDLY, Frequency.MINUTELY, Frequency.HOURLY, Frequency.DAILY);
    private static final Map<Frequency, Duration> SHORTEST_UNIT = Map.of(
        Frequency.SECONDLY, Duration.ofSeconds(1),
        Frequency.MINUTELY, Duration.ofMinutes(1),
        Frequency.HOURLY, Duration.ofHours(1),
        Frequency.DAILY, Duration.ofDays(1),
        Frequency.WEEKLY, Duration.ofDays(7),
        Frequency.MONTHLY, Duration.ofDays(28),
        Frequency.YEARLY, Duration.ofDays(365));

    /**
     * @param walkStart where ical4j starts to collect occurrences
     * @param walkEnd where it stops: the walk start for a lazy walk that only needs the next occurrences
     */
    static boolean canExpand(Recur<?> recur, Temporal seed, Temporal walkStart, Temporal walkEnd) {
        Instant from = instant(seed);
        if (recur.getCount() <= 0) {
            if (units(recur, from, instant(walkStart)) >= MAX_SKIPPED_UNITS) {
                return false;
            }
            from = later(from, instant(walkStart));
        }
        Instant end = instant(walkEnd);
        if (mapsCandidatesBeyondItsPeriod(recur)) {
            end = end.plus(MAPPED_BACK_OVERRUN);
        }
        long walkedPeriods = Math.max(0, units(recur, from, end) / Math.max(recur.getInterval(), 1)) + 1;
        if (mapsCandidatesBack(recur)) {
            // ical4j stops once a candidate passes the end of the walk: the period holding it is walked too.
            walkedPeriods++;
        }
        if (recur.getCount() > 0 && !mayLeavePeriodsEmpty(recur)) {
            walkedPeriods = Math.min(walkedPeriods, recur.getCount());
        }
        return candidatesPerPeriod(recur) <= MAX_EXPANDED_CANDIDATES / walkedPeriods;
    }

    /**
     * BYWEEKNO moves every candidate to its week of the year. ical4j only stops walking once a candidate passes the end
     * of the walk, so below YEARLY, where those candidates fall back before the walk, it may walk every period up to the
     * end of the next week-based year whatever end it was given: such a walk is counted up to
     * {@link #MAPPED_BACK_OVERRUN} further. RFC 5545 section 3.3.10 does not allow these rules, but cheap ones stay
     * expanded. An ordinal BYDAY below MONTHLY only filters the candidates of its period, so it does not walk further.
     */
    @VisibleForTesting
    static boolean mapsCandidatesBeyondItsPeriod(Recur<?> recur) {
        return !recur.getWeekNoList().isEmpty() && recur.getFrequency() != Frequency.YEARLY;
    }

    private static boolean mapsCandidatesBack(Recur<?> recur) {
        return !recur.getWeekNoList().isEmpty() || hasOrdinalDay(recur);
    }

    private static boolean hasOrdinalDay(Recur<?> recur) {
        return recur.getDayList().stream().anyMatch(day -> day.getOffset() != 0);
    }

    /**
     * Whether a period of the rule may hold no occurrence: COUNT then no longer bounds the walked periods. A BY* part
     * filters the candidates when it is as coarse as the frequency or coarser (eg BYMONTH of a DAILY rule), an ordinal
     * BYDAY may not exist in a month, and BYMONTHDAY, BYYEARDAY, BYWEEKNO or BYSETPOS may not either.
     */
    static boolean mayLeavePeriodsEmpty(Recur<?> recur) {
        Frequency frequency = recur.getFrequency();
        return !recur.getSetPosList().isEmpty()
            || !recur.getMonthDayList().isEmpty()
            || !recur.getYearDayList().isEmpty()
            || !recur.getWeekNoList().isEmpty()
            || (!recur.getMonthList().isEmpty() && frequency != Frequency.YEARLY)
            || (!recur.getDayList().isEmpty() && (FINER_THAN_WEEKLY.contains(frequency) || recur.getDayList().stream().anyMatch(day -> day.getOffset() != 0)))
            || (!recur.getHourList().isEmpty() && FINER_THAN_DAILY.contains(frequency))
            || (!recur.getMinuteList().isEmpty() && FINER_THAN_HOURLY.contains(frequency))
            || (!recur.getSecondList().isEmpty() && frequency == Frequency.SECONDLY);
    }

    private static long units(Recur<?> recur, Instant from, Instant to) {
        return Duration.between(from, to).dividedBy(SHORTEST_UNIT.get(recur.getFrequency()));
    }

    /**
     * Bounds the candidates ical4j builds in a period, repeated values included. BYDAY stands for every matching day of
     * the period (53 weeks of a year, 5 of a month) and BYMONTHDAY of a YEARLY rule without BYMONTH for each month. A
     * list that only filters the candidates (eg BYMONTH of a DAILY rule) is counted as well: this errs on the safe side.
     */
    @VisibleForTesting
    static long candidatesPerPeriod(Recur<?> recur) {
        boolean yearly = recur.getFrequency() == Frequency.YEARLY;
        long dayWeight = switch (recur.getFrequency()) {
            case YEARLY -> recur.getWeekNoList().isEmpty() && recur.getMonthList().isEmpty() ? 53 : 5;
            case MONTHLY -> 5;
            default -> 1;
        };
        long monthDayWeight = yearly && recur.getMonthList().isEmpty() ? 12 : 1;
        return Stream.of(
                (long) recur.getSecondList().size(),
                (long) recur.getMinuteList().size(),
                (long) recur.getHourList().size(),
                recur.getDayList().size() * dayWeight,
                recur.getMonthDayList().size() * monthDayWeight,
                (long) recur.getYearDayList().size(),
                recur.getWeekNoList().size() * weekNoWeight(recur),
                (long) recur.getMonthList().size())
            .mapToLong(Long::longValue)
            .filter(size -> size > 0)
            .reduce(1L, (a, b) -> Math.min(a * b, MAX_EXPANDED_CANDIDATES + 1));
    }

    /**
     * BYWEEKNO expands a WEEKLY or coarser period to the days of its weeks, but only filters a finer one.
     */
    private static long weekNoWeight(Recur<?> recur) {
        return FINER_THAN_WEEKLY.contains(recur.getFrequency()) ? 1 : 7;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    static Instant instant(Temporal temporal) {
        return switch (temporal) {
            case LocalDate date -> date.atStartOfDay(ZoneOffset.UTC).toInstant();
            case LocalDateTime dateTime -> dateTime.toInstant(ZoneOffset.UTC);
            default -> Instant.from(temporal);
        };
    }

    private RecurSkipGuard() {
    }
}
