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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.data.UnfoldingReader;
import net.fortuna.ical4j.model.CalendarDateFormat;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.PeriodList;

public record FreeBusyQueryResponseObject(byte[] icsBody) {
    private static final String FREE_BUSY_PREFIX = "FREEBUSY:";

    public record BusyInterval(Instant start, Instant end) {
        public static BusyInterval from(Period<? extends Temporal> period) {
            return new BusyInterval(temporalToInstant(period.getStart()), temporalToInstant(period.getEnd()));
        }

        private static Instant temporalToInstant(Temporal temporal) {
            return Optional.of(temporal)
                .flatMap(EventParseUtils::temporalToZonedDateTime)
                .map(ZonedDateTime::toInstant)
                .orElseThrow();
        }
    }

    public FreeBusyQueryResponseObject {
        Preconditions.checkArgument(icsBody != null, "icsBody must not be null");
    }

    public List<BusyInterval> busyIntervals() {
        return extractFreeBusyValues()
            .flatMap(this::toPeriods)
            .map(BusyInterval::from)
            .toList();
    }

    private Stream<String> extractFreeBusyValues() {
        // Do not parse FREEBUSY via ical4j FreeBusy#getValue()/getIntervals() here:
        // Bug reported: https://github.com/ical4j/ical4j/issues/845
        // Current ical4j behavior may shift UTC values depending on default timezone.
        return unfoldedLines()
            .map(String::trim)
            .filter(this::isFreeBusyLine)
            .map(this::toFreeBusyValue)
            .filter(StringUtils::isNotBlank);
    }

    private Stream<String> unfoldedLines() {
        try (BufferedReader reader = new BufferedReader(new UnfoldingReader(utf8Reader(), IOUtils.DEFAULT_BUFFER_SIZE, true),
            IOUtils.DEFAULT_BUFFER_SIZE)) {
            return reader.lines().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to unfold ICS body", e);
        }
    }

    private Reader utf8Reader() {
        return new InputStreamReader(new ByteArrayInputStream(icsBody), StandardCharsets.UTF_8);
    }

    private boolean isFreeBusyLine(String line) {
        return Strings.CS.startsWith(line, FREE_BUSY_PREFIX);
    }

    private String toFreeBusyValue(String line) {
        return StringUtils.substringAfter(line, ":");
    }

    private Stream<Period<Temporal>> toPeriods(String freeBusyValue) {
        try {
            return PeriodList.parse(freeBusyValue, CalendarDateFormat.UTC_DATE_TIME_FORMAT)
                .getPeriods()
                .stream();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid FREEBUSY value: " + freeBusyValue, e);
        }
    }

}
