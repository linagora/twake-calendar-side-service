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

import java.time.Instant;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

public record AvailabilityRules(List<AvailabilityRule> values) {

    public static AvailabilityRules of(AvailabilityRule... availabilityRules) {
        return new AvailabilityRules(List.of(availabilityRules));
    }

    public AvailabilityRules {
        Preconditions.checkArgument(values != null && !values.isEmpty(), "'values' must not be null or empty");
    }

    // Expands rules into ranges, merges overlaps, and clips the result to [start, end).
    public RangeSet<Instant> toRangeSet(Instant start, Instant end) {
        validateTimeRange(start, end);

        RangeSet<Instant> availabilityRanges = TreeRangeSet.create(values.stream()
            .flatMap(rule -> rule.availabilityRanges(start, end).stream())
            .toList());

        return availabilityRanges.subRangeSet(Range.closedOpen(start, end));
    }
}
