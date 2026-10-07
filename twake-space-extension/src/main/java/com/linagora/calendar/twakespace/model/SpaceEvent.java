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

package com.linagora.calendar.twakespace.model;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpaceEvent(OrganizationId organizationId, String organizationDomain, @JsonProperty(required = true) SpaceId id, String name,
                         List<Member> members, @JsonProperty(required = true) Instant timestamp) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(@JsonProperty(required = true) String email, String role) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public static SpaceEvent deserialize(byte[] body) {
        try {
            return OBJECT_MAPPER.readValue(body, SpaceEvent.class);
        } catch (IOException e) {
            throw new UnprocessableSpaceEventException("Unable to deserialize space event", e);
        }
    }

    // Milliseconds, as MongoDB stores them: a stored space then equals the one merged in memory.
    public SpaceEvent {
        members = List.copyOf(Objects.requireNonNullElse(members, List.of()));
        timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MILLIS);
    }
}
