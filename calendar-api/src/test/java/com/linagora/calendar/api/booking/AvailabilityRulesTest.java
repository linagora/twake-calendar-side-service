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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;

class AvailabilityRulesTest {

    @Test
    void toRangeSetShouldMergeOverlappingFixedRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T13:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T00:00:00Z"), Instant.parse("2026-02-25T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-24T09:00:00Z"), Instant.parse("2026-02-24T13:00:00Z")));
    }

    @Test
    void toRangeSetShouldKeepSeparatedNonOverlappingFixedRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T00:00:00Z"), Instant.parse("2026-02-25T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-24T09:00:00Z"), Instant.parse("2026-02-24T10:00:00Z")),
                Range.closedOpen(Instant.parse("2026-02-24T11:00:00Z"), Instant.parse("2026-02-24T12:00:00Z")));
    }

    @Test
    void toRangeSetShouldReturnEmptyWhenWindowMatchesNoFixedRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T12:30:00Z"), Instant.parse("2026-02-24T13:30:00Z"));

        assertThat(actual.asRanges()).isEmpty();
    }

    @Test
    void toRangeSetShouldIncludeOnlyFixedRuleOverlappingWindow() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T13:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T14:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T08:00:00Z"), Instant.parse("2026-02-24T11:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-24T09:00:00Z"), Instant.parse("2026-02-24T10:00:00Z")));
    }

    @Test
    void toRangeSetShouldClipPartiallyOverlappingFixedRule() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T13:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T14:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T09:30:00Z"), Instant.parse("2026-02-24T10:30:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-24T09:30:00Z"), Instant.parse("2026-02-24T10:00:00Z")));
    }

    @Test
    void toRangeSetShouldClipRangesToRequestedWindow() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T11:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T11:00:00Z")));
    }

    @Test
    void toRangeSetShouldExcludeRangesTouchingWindowBoundaries() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T11:00:00Z"));

        assertThat(actual.asRanges()).isEmpty();
    }

    @Test
    void toRangeSetShouldMergeAdjacentFixedRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-24T00:00:00Z"), Instant.parse("2026-02-25T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-24T09:00:00Z"), Instant.parse("2026-02-24T11:00:00Z")));
    }

    @Test
    void toRangeSetShouldMergeFixedAndWeeklyRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-23T09:00:00Z[UTC]"), ZonedDateTime.parse("2026-02-23T10:00:00Z[UTC]")),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:30"), LocalTime.parse("11:00")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T00:00:00Z"), Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-23T09:00:00Z"), Instant.parse("2026-02-23T11:00:00Z")));
    }

    @Test
    void toRangeSetShouldReturnEmptyWhenWeeklyRuleHasNoMatchingDay() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.SUNDAY, LocalTime.parse("09:00"),
                LocalTime.parse("11:00"), ZoneId.of("UTC")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T00:00:00Z"), Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual.asRanges()).isEmpty();
    }

    @Test
    void toRangeSetShouldReturnEmptyWhenNoWeeklyRuleMatchesWindow() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.SATURDAY, LocalTime.parse("09:00"), LocalTime.parse("10:00")),
            new WeeklyAvailabilityRule(DayOfWeek.SUNDAY, LocalTime.parse("14:00"), LocalTime.parse("15:00")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T00:00:00Z"), Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual.asRanges()).isEmpty();
    }

    @Test
    void toRangeSetShouldIncludeOnlyWeeklyRuleOverlappingWindow() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("10:00")),
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.parse("14:00"), LocalTime.parse("15:00")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T00:00:00Z"), Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-23T09:00:00Z"), Instant.parse("2026-02-23T10:00:00Z")));
    }

    @Test
    void toRangeSetShouldClipPartiallyOverlappingWeeklyRule() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("10:00")),
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.parse("14:00"), LocalTime.parse("15:00")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T09:30:00Z"), Instant.parse("2026-02-23T12:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-23T09:30:00Z"), Instant.parse("2026-02-23T10:00:00Z")));
    }

    @Test
    void toRangeSetShouldReturnMultipleRangesForWeeklyRuleAcrossWeeks() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("10:00")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T00:00:00Z"), Instant.parse("2026-03-10T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-23T09:00:00Z"), Instant.parse("2026-02-23T10:00:00Z")),
            Range.closedOpen(Instant.parse("2026-03-02T09:00:00Z"), Instant.parse("2026-03-02T10:00:00Z")),
                Range.closedOpen(Instant.parse("2026-03-09T09:00:00Z"), Instant.parse("2026-03-09T10:00:00Z")));
    }

    @Test
    void toRangeSetShouldExpandWeeklyRuleUsingTimezone() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("11:00"), ZoneId.of("Europe/Paris")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-23T00:00:00Z"), Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-23T08:00:00Z"), Instant.parse("2026-02-23T10:00:00Z")));
    }

    @Test
    void toRangeSetShouldConvertWeeklyRuleAcrossUtcDateBoundary() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("00:30"),
                LocalTime.parse("01:30"), ZoneId.of("Asia/Tokyo")));

        RangeSet<Instant> actual = rules.toRangeSet(Instant.parse("2026-02-22T00:00:00Z"), Instant.parse("2026-02-23T00:00:00Z"));

        assertThat(actual.asRanges())
            .containsExactly(Range.closedOpen(Instant.parse("2026-02-22T15:30:00Z"), Instant.parse("2026-02-22T16:30:00Z")));
    }
}
