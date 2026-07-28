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
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkAlarm;
import com.linagora.calendar.storage.booking.BookingLinkExtraAttendeeUtil;
import com.linagora.calendar.storage.booking.BookingLinkResourceUtil;
import com.linagora.calendar.storage.booking.EventTransparency;
import com.linagora.calendar.storage.booking.EventVisibility;

@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record BookingLinkDTO(@JsonProperty("publicId") String publicId,
                             @JsonProperty("calendarUrl") String calendarUrl,
                             @JsonProperty("durationMinutes") long durationMinutes,
                             @JsonProperty("active") boolean active,
                             @JsonProperty("autoAccept") boolean autoAccept,
                             @JsonProperty("availabilityRules") Optional<List<AvailabilityRuleDTO>> availabilityRules,
                             @JsonProperty("extraAttendees") Optional<JsonNode> extraAttendees,
                             @JsonProperty("name") Optional<String> name,
                             @JsonProperty("description") Optional<String> description,
                             @JsonProperty("color") String color,
                             @JsonProperty("location") Optional<String> location,
                             @JsonProperty("visibility") Optional<String> visibility,
                             @JsonProperty("transparency") Optional<String> transparency,
                             @JsonProperty("resources") Optional<List<String>> resources,
                             @JsonProperty("alarm") Optional<String> alarm) {

    public static BookingLinkDTO from(BookingLink bookingLink) {
        Optional<List<AvailabilityRuleDTO>> ruleDTOs = bookingLink.availabilityRules()
            .map(rules -> rules.values().stream()
                .map(AvailabilityRuleDTO::from)
                .toList());

        Optional<JsonNode> extraAttendees = Optional.of(bookingLink.extraAttendees())
            .filter(attendees -> !attendees.isEmpty())
            .map(BookingLinkExtraAttendeeUtil::serialize);

        Optional<List<String>> resources = Optional.of(bookingLink.resources())
            .filter(list -> !list.isEmpty())
            .map(BookingLinkResourceUtil::serialize);

        return new BookingLinkDTO(
            bookingLink.publicId().value().toString(),
            bookingLink.calendarUrl().asUri().toString(),
            bookingLink.duration().toMinutes(),
            bookingLink.active(),
            bookingLink.autoAccept(),
            ruleDTOs,
            extraAttendees,
            bookingLink.name(),
            bookingLink.description(),
            bookingLink.colorOrDefault(),
            bookingLink.location(),
            bookingLink.visibility().map(EventVisibility::value),
            bookingLink.transparency().map(EventTransparency::value),
            resources,
            bookingLink.alarm().map(BookingLinkAlarm::trigger));
    }
}
