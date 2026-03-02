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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

public interface AvailableSlotsCalculator {

    Set<AvailabilitySlot> computeSlots(ComputeSlotsRequest request);

    static <T extends Comparable<? super T>> void validateTimeRange(T start, T end) {
        Preconditions.checkNotNull(start, "'start' must not be null");
        Preconditions.checkNotNull(end, "'end' must not be null");
        Preconditions.checkArgument(end.compareTo(start) >= 0, "'end' must not be before 'start'");
    }

    record UnavailableTimeRanges(List<TimeRange> values) {
        public record TimeRange(Instant start, Instant end) {
            public TimeRange {
                validateTimeRange(start, end);
            }
        }

        public static UnavailableTimeRanges of(TimeRange... unavailableTimeRanges) {
            return new UnavailableTimeRanges(List.of(unavailableTimeRanges));
        }

        public UnavailableTimeRanges {
            Preconditions.checkNotNull(values, "'values' must not be null");
        }

        public RangeSet<Instant> toRangeSet() {
            return TreeRangeSet.create(values.stream()
                .map(interval -> Range.closedOpen(interval.start(), interval.end()))
                .toList());
        }
    }

    record ComputeSlotsRequest(Duration eventDuration,
                               Instant start,
                               Instant end,
                               AvailabilityRules availabilityRules,
                               UnavailableTimeRanges unavailableTimeRanges) {
        public ComputeSlotsRequest {
            Preconditions.checkNotNull(eventDuration, "'eventDuration' must not be null");
            Preconditions.checkArgument(!eventDuration.isNegative() && !eventDuration.isZero(), "'eventDuration' must be positive");
            Preconditions.checkNotNull(availabilityRules, "'availabilityRules' must not be null");
            Preconditions.checkNotNull(unavailableTimeRanges, "'unavailableTimeRanges' must not be null");
            validateTimeRange(start, end);
        }
    }

    record AvailabilitySlot(Instant start, Duration duration) {
    }

    class Default implements AvailableSlotsCalculator {
        @Override
        public Set<AvailabilitySlot> computeSlots(ComputeSlotsRequest request) {
            Preconditions.checkNotNull(request, "'request' must not be null");

            Set<Range<Instant>> availabilityRanges = computeAvailabilityRanges(request);

            if (availabilityRanges.isEmpty()) {
                return Set.of();
            }

            return availabilityRanges.stream()
                .flatMap(range -> generateSlotsInAvailabilityRange(range, request.eventDuration()).stream())
                .collect(Collectors.toUnmodifiableSet());
        }

        private List<AvailabilitySlot> generateSlotsInAvailabilityRange(Range<Instant> availabilityRange, Duration slotDuration) {
            Instant firstCandidateStart = roundUpToMinute(availabilityRange.lowerEndpoint());
            Instant availabilityEndExclusive = availabilityRange.upperEndpoint();

            return Stream.iterate(firstCandidateStart,
                    slotStart -> !slotStart.plus(slotDuration).isAfter(availabilityEndExclusive),
                    slotStart -> slotStart.plus(slotDuration))
                .map(slotStart -> new AvailabilitySlot(slotStart, slotDuration))
                .toList();
        }

        private Set<Range<Instant>> computeAvailabilityRanges(ComputeSlotsRequest request) {
            RangeSet<Instant> availabilityRanges = request.availabilityRules().toRangeSet(request.start(), request.end());
            availabilityRanges.removeAll(request.unavailableTimeRanges().toRangeSet());
            return availabilityRanges.asRanges();
        }

        private Instant roundUpToMinute(Instant instant) {
            Instant minuteFloor = instant.truncatedTo(ChronoUnit.MINUTES);
            if (minuteFloor.isBefore(instant)) {
                return minuteFloor.plus(1, ChronoUnit.MINUTES);
            }
            return minuteFloor;
        }
    }
}
