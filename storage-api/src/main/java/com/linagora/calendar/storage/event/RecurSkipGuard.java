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
import java.time.ZoneOffset;
import java.time.temporal.Temporal;

import net.fortuna.ical4j.model.Recur;

/**
 * Without COUNT, ical4j reaches the start of a period by doubling an int multiplier of the rule's period. It can only
 * get as far as what this int holds: past it, the multiplier overflows to 0 and the search never ends, nor can it be
 * interrupted. Whatever the interval, the last multiplier before the overflow goes at least 2^30 units of the frequency.
 */
final class RecurSkipGuard {
    private static final long MAX_SKIPPED_UNITS = 1L << 30;

    static boolean canSkip(Recur<?> recur, Temporal seed, Temporal periodStart) {
        if (recur.getCount() > 0) {
            return true;
        }
        return Duration.between(instant(seed), instant(periodStart)).getSeconds() / unitSeconds(recur) < MAX_SKIPPED_UNITS;
    }

    private static Instant instant(Temporal temporal) {
        if (temporal instanceof LocalDate date) {
            return date.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.from(temporal);
    }

    private static long unitSeconds(Recur<?> recur) {
        return switch (recur.getFrequency()) {
            case SECONDLY -> 1L;
            case MINUTELY -> Duration.ofMinutes(1).getSeconds();
            case HOURLY -> Duration.ofHours(1).getSeconds();
            case DAILY -> Duration.ofDays(1).getSeconds();
            case WEEKLY -> Duration.ofDays(7).getSeconds();
            case MONTHLY -> Duration.ofDays(28).getSeconds();
            case YEARLY -> Duration.ofDays(365).getSeconds();
        };
    }

    private RecurSkipGuard() {
    }
}
