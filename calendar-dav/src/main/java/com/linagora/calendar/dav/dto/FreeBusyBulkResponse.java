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

package com.linagora.calendar.dav.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.Streams;
import com.linagora.calendar.dav.DavClientException;

/**
 * Answer of the bulk free/busy route of the calendar server:
 *
 * <pre>{@code
 * {"start": "...", "end": "...",
 *  "users": [{"id": "<userId>",
 *             "calendars": [{"id": "<calendarId>",
 *                            "busy": [{"uid": "...", "start": "20360126T100000Z", "end": "20360126T103000Z"}]}]}]}
 * }</pre>
 *
 * One request covers every calendar of the listed users, the server keeping only the ones the requester may
 * read the free/busy of - which is why the answer is flattened here: what a booking link needs is when its
 * owner is busy, not in which of their calendars.
 */
public record FreeBusyBulkResponse(List<BusyInterval> busyIntervals) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusyInterval(@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd'T'HHmmss'Z'", timezone = "UTC") Instant start,
                               @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMMdd'T'HHmmss'Z'", timezone = "UTC") Instant end) {

        /**
         * The calendar server resolves the floating dates of an all-day event ({@code VALUE=DATE}) as UTC
         * midnights, which is what makes such an interval recognizable: an all-day event however spans its
         * dates from midnight to midnight in the time zone of whoever owns it. Re-anchors such an interval to
         * the midnights of the given zone, and leaves any other interval untouched.
         */
        public BusyInterval anchorAllDayIn(ZoneId zoneId) {
            if (!spansWholeUtcDays()) {
                return this;
            }
            return new BusyInterval(startOfDayIn(start, zoneId), startOfDayIn(end, zoneId));
        }

        private boolean spansWholeUtcDays() {
            return isUtcMidnight(start) && isUtcMidnight(end) && start.isBefore(end);
        }

        private static boolean isUtcMidnight(Instant instant) {
            return instant.truncatedTo(ChronoUnit.DAYS).equals(instant);
        }

        private static Instant startOfDayIn(Instant utcMidnight, ZoneId zoneId) {
            return LocalDate.ofInstant(utcMidnight, ZoneOffset.UTC)
                .atStartOfDay(zoneId)
                .toInstant();
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final String USERS = "users";
    private static final String CALENDARS = "calendars";
    private static final String BUSY = "busy";
    private static final String START = "start";
    private static final String END = "end";

    public static FreeBusyBulkResponse parse(byte[] payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);

            List<BusyInterval> busyIntervals = Streams.stream(root.path(USERS).elements())
                .flatMap(user -> Streams.stream(user.path(CALENDARS).elements()))
                // 'busy' is iterated rather than indexed: the calendar server happens to serialize it as a
                // JSON object rather than an array when it filtered an event out.
                .flatMap(calendar -> Streams.stream(calendar.path(BUSY).elements()))
                .flatMap(busy -> toBusyInterval(busy).stream())
                .toList();

            return new FreeBusyBulkResponse(busyIntervals);
        } catch (Exception e) {
            throw new DavClientException("Failed to parse bulk free/busy response", e);
        }
    }

    /**
     * An event carrying neither DTEND nor DURATION comes back without an 'end': it occupies no time range,
     * hence nothing to report as busy.
     */
    private static Optional<BusyInterval> toBusyInterval(JsonNode busy) {
        if (!busy.hasNonNull(START) || !busy.hasNonNull(END)) {
            return Optional.empty();
        }

        return Optional.of(OBJECT_MAPPER.convertValue(busy, BusyInterval.class));
    }
}
