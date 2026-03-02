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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Range;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;

class WeeklyAvailabilityRuleTest {

    @Test
    void availabilityRangesShouldReturnRangesOnlyForMatchingDayOfWeek() {
        WeeklyAvailabilityRule rule = new WeeklyAvailabilityRule(
            DayOfWeek.MONDAY,
            LocalTime.parse("09:00"),
            LocalTime.parse("11:00"),
            ZoneId.of("UTC"));

        List<Range<Instant>> actual = rule.availabilityRanges(
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-26T00:00:00Z"));

        assertThat(actual).containsExactly(
            Range.closedOpen(Instant.parse("2026-02-23T09:00:00Z"), Instant.parse("2026-02-23T11:00:00Z")));
    }

    @Test
    void availabilityRangesShouldReturnEmptyWhenNoMatchingDayInRange() {
        WeeklyAvailabilityRule rule = new WeeklyAvailabilityRule(
            DayOfWeek.SUNDAY,
            LocalTime.parse("09:00"),
            LocalTime.parse("11:00"),
            Optional.empty());

        List<Range<Instant>> actual = rule.availabilityRanges(
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual).isEmpty();
    }

    @Test
    void availabilityRangesShouldReturnMultipleWeeksWhenRangeSpansManyWeeks() {
        WeeklyAvailabilityRule rule = new WeeklyAvailabilityRule(
            DayOfWeek.MONDAY,
            LocalTime.parse("09:00"),
            LocalTime.parse("10:00"),
            ZoneId.of("UTC"));

        List<Range<Instant>> actual = rule.availabilityRanges(
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-03-10T00:00:00Z"));

        assertThat(actual).containsExactly(
            Range.closedOpen(Instant.parse("2026-02-23T09:00:00Z"), Instant.parse("2026-02-23T10:00:00Z")),
            Range.closedOpen(Instant.parse("2026-03-02T09:00:00Z"), Instant.parse("2026-03-02T10:00:00Z")),
            Range.closedOpen(Instant.parse("2026-03-09T09:00:00Z"), Instant.parse("2026-03-09T10:00:00Z")));
    }

    @Test
    void availabilityRangesShouldThrowWhenStartIsAfterEnd() {
        WeeklyAvailabilityRule rule = new WeeklyAvailabilityRule(
            DayOfWeek.MONDAY,
            LocalTime.parse("09:00"),
            LocalTime.parse("10:00"),
            ZoneId.of("UTC"));

        assertThatThrownBy(() -> rule.availabilityRanges(
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-23T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'end' must not be before 'start'");
    }

    @Test
    void availabilityRangesShouldDefaultToUtcWhenTimezoneIsEmpty() {
        WeeklyAvailabilityRule rule = new WeeklyAvailabilityRule(
            DayOfWeek.MONDAY,
            LocalTime.parse("09:00"),
            LocalTime.parse("10:00"),
            Optional.empty());

        List<Range<Instant>> actual = rule.availabilityRanges(
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual).containsExactly(
            Range.closedOpen(Instant.parse("2026-02-23T09:00:00Z"), Instant.parse("2026-02-23T10:00:00Z")));
    }

    @Test
    void availabilityRangesShouldConvertTimezoneToUtc() {
        WeeklyAvailabilityRule rule = new WeeklyAvailabilityRule(
            DayOfWeek.MONDAY,
            LocalTime.parse("09:00"),
            LocalTime.parse("11:00"),
            ZoneId.of("Europe/Paris"));

        List<Range<Instant>> actual = rule.availabilityRanges(
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-24T00:00:00Z"));

        assertThat(actual).containsExactly(
            Range.closedOpen(Instant.parse("2026-02-23T08:00:00Z"), Instant.parse("2026-02-23T10:00:00Z")));
    }
}
