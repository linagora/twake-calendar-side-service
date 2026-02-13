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

package com.linagora.calendar.dav;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.Interval;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.TemporalAdapter;

public record FreeBusyQueryResponseObject(byte[] icsBody) {
    private static final Logger LOGGER = LoggerFactory.getLogger(FreeBusyQueryResponseObject.class);
    private static final String FREE_BUSY_PREFIX = "FREEBUSY";

    public record BusyInterval(Instant start, Instant end) {
    }

    public FreeBusyQueryResponseObject {
        Preconditions.checkArgument(icsBody != null, "icsBody must not be null");
    }

    public List<BusyInterval> busyIntervals(Instant from, Instant to) {
        Preconditions.checkArgument(from != null, "from must not be null");
        Preconditions.checkArgument(to != null, "to must not be null");
        Preconditions.checkArgument(from.isBefore(to), "from must be before to");

        Interval queryRange = Interval.of(from, to);

        return unfoldedLines()
            .map(String::trim)
            .filter(line -> Strings.CI.startsWith(line, FREE_BUSY_PREFIX))
            .map(line -> StringUtils.substringAfter(line, ":"))
            .filter(StringUtils::isNotBlank)
            .flatMap(this::toIntervals)
            .flatMap(interval -> clipToRange(interval, queryRange).stream())
            .map(interval -> new BusyInterval(interval.getStart(), interval.getEnd()))
            .toList();
    }

    private Stream<String> unfoldedLines() {
        String unfoldedIcs = new String(icsBody, StandardCharsets.UTF_8)
            .replaceAll("\\r?\\n[ \\t]", "");
        return Arrays.stream(unfoldedIcs.split("\\R"));
    }

    private Stream<Interval> toIntervals(String freeBusyValue) {
        return Arrays.stream(StringUtils.split(freeBusyValue, ','))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .map(this::toInterval)
            .flatMap(Optional::stream);
    }

    private Optional<Interval> toInterval(String period) {
        String[] boundaries = StringUtils.split(period, '/');
        if (boundaries == null || boundaries.length != 2) {
            LOGGER.warn("Invalid FREEBUSY period '{}'", period);
            return Optional.empty();
        }

        return parseDateTime(boundaries[0])
            .flatMap(start -> parsePeriodEnd(boundaries[1], start)
                .filter(start::isBefore)
                .map(end -> Interval.of(start, end)))
            .or(() -> {
                LOGGER.warn("Invalid FREEBUSY period boundaries '{}'", period);
                return Optional.empty();
            });
    }

    private Optional<Instant> parsePeriodEnd(String value, Instant start) {
        return Strings.CS.startsWith(value, "P")
            ? parseDuration(value).map(start::plus)
            : parseDateTime(value);
    }

    private Optional<Duration> parseDuration(String value) {
        try {
            return Optional.of(Duration.parse(value));
        } catch (RuntimeException e) {
            LOGGER.warn("Invalid FREEBUSY duration '{}': {}", value, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Instant> parseDateTime(String value) {
        try {
            Temporal temporal = (Temporal) TemporalAdapter.parse(value).getTemporal();
            return EventParseUtils.temporalToZonedDateTime(temporal)
                .map(ZonedDateTime::toInstant);
        } catch (RuntimeException e) {
            LOGGER.warn("Cannot parse FREEBUSY datetime '{}': {}", value, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Interval> clipToRange(Interval candidate, Interval queryRange) {
        if (!candidate.overlaps(queryRange)) {
            return Optional.empty();
        }
        Interval clipped = candidate.intersection(queryRange);
        if (clipped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(clipped);
    }
}
