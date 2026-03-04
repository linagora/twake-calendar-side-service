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

package com.linagora.calendar.restapi.routes.response;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.storage.booking.BookingLink;

public record BookingLinkSlotsResponse(long durationMinutes,
                                       RangeDTO range,
                                       List<SlotDTO> slots) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static BookingLinkSlotsResponse of(BookingLink bookingLink, Instant from, Instant to, Set<AvailabilitySlot> slots) {
        return new BookingLinkSlotsResponse(bookingLink.duration().toMinutes(), new RangeDTO(from, to),
            slots.stream()
                .map(AvailabilitySlot::start)
                .sorted()
                .map(SlotDTO::new)
                .toList());
    }

    public record RangeDTO(@JsonProperty("from")
                           @JsonFormat(shape = JsonFormat.Shape.STRING) Instant from,
                           @JsonProperty("to")
                           @JsonFormat(shape = JsonFormat.Shape.STRING) Instant to) {
    }

    public record SlotDTO(@JsonProperty("start")
                          @JsonFormat(shape = JsonFormat.Shape.STRING) Instant start) {
    }

    public byte[] jsonAsBytes() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }
}
