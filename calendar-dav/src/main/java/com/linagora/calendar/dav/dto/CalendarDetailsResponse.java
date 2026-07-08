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

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import com.linagora.calendar.dav.DavClientException;

public record CalendarDetailsResponse(List<CalendarDetailsResponse.CalendarInvite> invites) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static CalendarDetailsResponse parse(byte[] payload) {
        try {
            JsonNode inviteNode = OBJECT_MAPPER.readTree(payload).path("invite");
            if (!inviteNode.isArray()) {
                return new CalendarDetailsResponse(List.of());
            }

            List<CalendarInvite> invites = Streams.stream(inviteNode.elements())
                .filter(node -> StringUtils.isNotBlank(node.path("href").asText(null)))
                .map(CalendarDetailsResponse::parseInvite)
                .toList();

            return new CalendarDetailsResponse(invites);
        } catch (Exception e) {
            throw new DavClientException("Failed to parse calendar details response", e);
        }
    }

    private static CalendarInvite parseInvite(JsonNode node) {
        Optional<String> principal = Optional.ofNullable(node.path("principal").asText(null))
            .filter(StringUtils::isNotBlank);
        Optional<Integer> access = Optional.ofNullable(node.get("access"))
            .filter(JsonNode::isInt)
            .map(JsonNode::asInt);
        return new CalendarInvite(node.path("href").asText(), principal, access);
    }

    public record CalendarInvite(String href,
                                 Optional<String> principal,
                                 Optional<Integer> access) {
        public CalendarInvite {
            Preconditions.checkArgument(StringUtils.isNotBlank(href), "'href' field is required");
        }
    }
}
