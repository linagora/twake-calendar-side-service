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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.ComputeSlotsRequest;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.UnavailableTimeRanges;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.UnavailableTimeRanges.TimeRange;

class AvailableSlotsCalculatorTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final UnavailableTimeRanges EMPTY_UNAVAILABLE_TIME_RANGES = UnavailableTimeRanges.of();

    private final AvailableSlotsCalculator testee = new AvailableSlotsCalculator.Default();

    @Test
    void shouldGenerateSlotsFromWeeklyAvailabilityOnly() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY,
                LocalTime.parse("09:00"), LocalTime.parse("10:00"),
                UTC));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration, Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-24T00:00:00Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        Set<AvailabilitySlot> slots = testee.computeSlots(request);

        // A 1-hour window with 30-minute events should produce exactly 2 starts.
        assertThat(slots).hasSize(2)
            .containsExactlyInAnyOrder(
            new AvailabilitySlot(Instant.parse("2026-02-23T09:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-23T09:30:00Z"), eventDuration));
    }

    @Test
    void shouldGenerateSlotsFromFixedAvailabilityOnly() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T02:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T03:30:00Z[UTC]")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        Set<AvailabilitySlot> slots = testee.computeSlots(request);

        // A 90-minute fixed range with 30-minute events should produce 3 starts.
        assertThat(slots).hasSize(3)
            .containsExactlyInAnyOrder(
            new AvailabilitySlot(Instant.parse("2026-02-24T02:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T02:30:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T03:00:00Z"), eventDuration));
    }

    @Test
    void shouldMergeWeeklyAndFixedAvailabilityRules() {
        Duration eventDuration = Duration.ofHours(2);
        AvailabilityRules availabilityRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY,
                LocalTime.parse("09:00"), LocalTime.parse("10:00"), UTC),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T09:30:00Z[UTC]"),
                ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        // Weekly [09:00-10:00] and fixed [09:30-11:00] merge into one 2-hour slot at 09:00.
        assertThat(testee.computeSlots(request))
            .containsExactlyInAnyOrder(new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration));
    }

    @Test
    void shouldHandleComplexMixOfWeeklyFixedAndUnavailableRanges() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.parse("09:00"), LocalTime.parse("11:00"), UTC),
            new WeeklyAvailabilityRule(DayOfWeek.WEDNESDAY, LocalTime.parse("13:00"), LocalTime.parse("15:00"), UTC),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-24T10:30:00Z[UTC]"), ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")),
            new FixedAvailabilityRule(ZonedDateTime.parse("2026-02-25T14:30:00Z[UTC]"), ZonedDateTime.parse("2026-02-25T16:00:00Z[UTC]")));

        UnavailableTimeRanges unavailableTimeRanges = UnavailableTimeRanges.of(
            new TimeRange(Instant.parse("2026-02-24T09:30:00Z"), Instant.parse("2026-02-24T10:00:00Z")),
            new TimeRange(Instant.parse("2026-02-25T14:00:00Z"), Instant.parse("2026-02-25T15:30:00Z")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-26T00:00:00Z"),
            availabilityRules,
            unavailableTimeRanges);

        assertThat(testee.computeSlots(request)).containsExactlyInAnyOrder(
            // Tuesday slots
            new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T10:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T10:30:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T11:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T11:30:00Z"), eventDuration),
            // Wednesday slots
            new AvailabilitySlot(Instant.parse("2026-02-25T13:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-25T13:30:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-25T15:30:00Z"), eventDuration));
    }

    @Test
    void shouldExcludeSlotsOverlappingUnavailableRanges() {
        Duration eventDuration = Duration.ofHours(1);

        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")));

        UnavailableTimeRanges unavailableTimeRanges = UnavailableTimeRanges.of(new TimeRange(
            Instant.parse("2026-02-24T09:30:00Z"),
            Instant.parse("2026-02-24T10:30:00Z")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            unavailableTimeRanges);

        Set<AvailabilitySlot> slots = testee.computeSlots(request);

        // Slots intersecting [09:30-10:30] must be excluded; 10:30 remains valid.
        assertThat(slots).contains(new AvailabilitySlot(Instant.parse("2026-02-24T10:30:00Z"), eventDuration))
            .doesNotContain(new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration))
            .doesNotContain(new AvailabilitySlot(Instant.parse("2026-02-24T09:30:00Z"), eventDuration));
    }

    @Test
    void shouldIgnoreUnavailableRangesOutsideRequestWindow() {
        Duration eventDuration = Duration.ofHours(1);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T12:00:00Z[UTC]")));

        UnavailableTimeRanges unavailableTimeRanges = UnavailableTimeRanges.of(
            new TimeRange(Instant.parse("2026-02-24T07:00:00Z"), Instant.parse("2026-02-24T08:00:00Z")),
            new TimeRange(Instant.parse("2026-02-24T12:00:00Z"), Instant.parse("2026-02-24T13:00:00Z")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T09:00:00Z"),
            Instant.parse("2026-02-24T12:00:00Z"),
            availabilityRules,
            unavailableTimeRanges);

        // Busy ranges fully outside [request.start, request.end) should have no effect.
        assertThat(testee.computeSlots(request)).containsExactlyInAnyOrder(
            new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T10:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T11:00:00Z"), eventDuration));
    }

    @Test
    void shouldIgnoreUnavailableRangesOutsideAvailability() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")));

        UnavailableTimeRanges unavailableTimeRanges = UnavailableTimeRanges.of(
            new TimeRange(Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T11:00:00Z")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            unavailableTimeRanges);

        // Busy range intersects request window but not actual availability.
        assertThat(testee.computeSlots(request)).containsExactlyInAnyOrder(
            new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T09:30:00Z"), eventDuration));
    }

    @Test
    void shouldKeepSlotWhenUnavailableTouchesPreviousSlotEnd() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]")));

        UnavailableTimeRanges unavailableTimeRanges = UnavailableTimeRanges.of(
            new TimeRange(Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T10:30:00Z")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            unavailableTimeRanges);

        // 10:00-10:30 removes slot starting 10:00, but slot starting 10:30 remains valid.
        assertThat(testee.computeSlots(request)).containsExactlyInAnyOrder(
            new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T09:30:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T10:30:00Z"), eventDuration));
    }

    @Test
    void shouldIncludeSlotStartingAtRangeStartAndExcludeSlotStartingAtRangeEnd() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T09:30:00Z"),
            Instant.parse("2026-02-24T10:00:00Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        // [start, end) means 09:30 is valid, but 10:00 is excluded.
        assertThat(testee.computeSlots(request))
            .containsExactlyInAnyOrder(new AvailabilitySlot(Instant.parse("2026-02-24T09:30:00Z"), eventDuration));
    }

    @Test
    void shouldGenerateSlotsAcrossMultipleDays() {
        Duration eventDuration = Duration.ofHours(2);
        AvailabilityRules availabilityRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("11:00"), UTC),
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.parse("10:00"), LocalTime.parse("12:00"), UTC),
            new WeeklyAvailabilityRule(DayOfWeek.WEDNESDAY, LocalTime.parse("14:00"), LocalTime.parse("16:00"), UTC));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-25T23:59:59Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        // One 2-hour slot is expected for each day (Mon/Tue/Wed).
        assertThat(testee.computeSlots(request))
            .containsExactlyInAnyOrder(
                new AvailabilitySlot(Instant.parse("2026-02-23T09:00:00Z"), eventDuration),
                new AvailabilitySlot(Instant.parse("2026-02-24T10:00:00Z"), eventDuration),
                new AvailabilitySlot(Instant.parse("2026-02-25T14:00:00Z"), eventDuration));
    }

    @Test
    void shouldReturnEmptyWhenEventDurationExceedsAvailability() {
        Duration eventDuration = Duration.ofHours(3);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T11:00:00Z[UTC]")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        // Availability is only 2 hours (09:00-11:00), shorter than the 3-hour event duration.
        assertThat(testee.computeSlots(request)).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenUnavailableCoversAvailability() {
        Duration eventDuration = Duration.ofHours(1);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")));
        UnavailableTimeRanges unavailableTimeRanges = UnavailableTimeRanges.of(new TimeRange(
            Instant.parse("2026-02-24T09:00:00Z"),
            Instant.parse("2026-02-24T10:00:00Z")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-25T00:00:00Z"),
            availabilityRules,
            unavailableTimeRanges);

        // Unavailable fully covers availability, so no slot can be generated.
        assertThat(testee.computeSlots(request)).isEmpty();
    }

    @Test
    void shouldUseEventDurationAsSlotStep() {
        Duration eventDuration = Duration.ofMinutes(20);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T10:00:00Z[UTC]")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(
            eventDuration,
            Instant.parse("2026-02-24T09:00:30Z"),
            Instant.parse("2026-02-24T10:00:00Z"),
            availabilityRules,
            EMPTY_UNAVAILABLE_TIME_RANGES);

        Set<AvailabilitySlot> slots = testee.computeSlots(request);

        // Slot starts advance by eventDuration (20m): 09:01 then 09:21.
        assertThat(slots).hasSize(2)
            .containsExactlyInAnyOrder(
            new AvailabilitySlot(Instant.parse("2026-02-24T09:01:00Z"), eventDuration),
            new AvailabilitySlot(Instant.parse("2026-02-24T09:21:00Z"), eventDuration));
    }

    @Test
    void shouldSkipTrailingStartWhenRemainingTimeIsTooShort() {
        Duration eventDuration = Duration.ofMinutes(30);
        AvailabilityRules availabilityRules = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2026-02-24T09:00:00Z[UTC]"),
            ZonedDateTime.parse("2026-02-24T10:35:00Z[UTC]")));

        ComputeSlotsRequest request = new ComputeSlotsRequest(eventDuration,
            Instant.parse("2026-02-24T09:00:00Z"), Instant.parse("2026-02-24T10:35:00Z"),
            availabilityRules, EMPTY_UNAVAILABLE_TIME_RANGES);

        // 10:30 cannot be a start because 10:30 + 30m exceeds 10:35.
        assertThat(testee.computeSlots(request))
            .containsExactlyInAnyOrder(
                new AvailabilitySlot(Instant.parse("2026-02-24T09:00:00Z"), eventDuration),
                new AvailabilitySlot(Instant.parse("2026-02-24T09:30:00Z"), eventDuration),
                new AvailabilitySlot(Instant.parse("2026-02-24T10:00:00Z"), eventDuration));
    }
}
