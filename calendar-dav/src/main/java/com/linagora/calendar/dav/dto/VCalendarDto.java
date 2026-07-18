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

import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;

public record VCalendarDto(JsonNode value) {
    private static final String VEVENT = "vevent";
    private static final String STATUS = "status";
    private static final String CANCELLED = "CANCELLED";

    public static VCalendarDto from(CalendarReportJsonResponse reportResponse) {
        JsonNode davItem = reportResponse.firstDavItemNode();
        JsonNode data = davItem.path("data");
        if (data.isMissingNode() || !data.isArray()) {
            throw new IllegalStateException("Missing or invalid 'data' field in 'dav:item'");
        }

        return new VCalendarDto(data);
    }

    public boolean isCancelled() {
        return hasCancelledStatus(value(), false);
    }

    private static boolean hasCancelledStatus(JsonNode node, boolean inVEvent) {
        if (!node.isArray()) {
            return false;
        }

        Optional<String> name = children(node)
            .findFirst()
            .map(JsonNode::asText);
        boolean currentNodeIsVEvent = name.filter(value -> Strings.CI.equals(value, VEVENT)).isPresent();
        boolean currentNodeIsCancelledStatus = inVEvent
            && name.filter(value -> Strings.CI.equals(value, STATUS)).isPresent()
            && children(node).anyMatch(child -> child.isTextual() && Strings.CI.equals(child.asText(), CANCELLED));

        return currentNodeIsCancelledStatus
            || children(node).anyMatch(child -> hasCancelledStatus(child, inVEvent || currentNodeIsVEvent));
    }

    private static Stream<JsonNode> children(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false);
    }
}
