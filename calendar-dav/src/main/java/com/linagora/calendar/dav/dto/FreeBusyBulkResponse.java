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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public record BusyInterval(Instant start, Instant end) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ICALENDAR_UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
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

        return Optional.of(new BusyInterval(parseInstant(busy.get(START).asText()), parseInstant(busy.get(END).asText())));
    }

    private static Instant parseInstant(String value) {
        return LocalDateTime.parse(value, ICALENDAR_UTC_FORMAT).toInstant(ZoneOffset.UTC);
    }
}
