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

package com.linagora.calendar.restapi.routes.dto;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.storage.booking.BookingLink;

@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record BookingLinkDTO(@JsonProperty("publicId") String publicId,
                             @JsonProperty("calendarUrl") String calendarUrl,
                             @JsonProperty("durationMinutes") long durationMinutes,
                             @JsonProperty("active") boolean active,
                             @JsonProperty("availabilityRules") Optional<List<AvailabilityRuleDTO>> availabilityRules) {

    public static BookingLinkDTO from(BookingLink bookingLink) {
        Optional<List<AvailabilityRuleDTO>> ruleDTOs = bookingLink.availabilityRules()
            .map(rules -> rules.values().stream()
                .map(AvailabilityRuleDTO::from)
                .toList());

        return new BookingLinkDTO(
            bookingLink.publicId().value().toString(),
            bookingLink.calendarUrl().asUri().toString(),
            bookingLink.duration().toMinutes(),
            bookingLink.active(),
            ruleDTOs);
    }
}
