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

import java.net.URI;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;

public record CalendarReportJsonResponse(JsonNode value) {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static CalendarReportJsonResponse from(String json) throws JsonProcessingException {
        return new CalendarReportJsonResponse(mapper.readTree(json));
    }

    public CalendarReportJsonResponse {
        Preconditions.checkNotNull(value, "value must not be null");
    }

    public List<JsonNode> davItems() {
        JsonNode embedded = value().path("_embedded");
        if (embedded.isMissingNode()) {
            throw new IllegalStateException("Missing '_embedded' field in response");
        }
        JsonNode davItems = embedded.path("dav:item");
        if (!davItems.isArray()) {
            throw new IllegalStateException("Expected 'dav:item' array in response");
        }
        return Streams.stream(davItems).toList();
    }

    public JsonNode firstDavItemNode() {
        List<JsonNode> items = davItems();
        if (items.isEmpty()) {
            throw new IllegalStateException("Expected non-empty 'dav:item' array in response");
        }
        return items.getFirst();
    }

    public URI calendarHref() {
        return calendarHref(firstDavItemNode());
    }

    public static URI calendarHref(JsonNode davItem) {
        JsonNode hrefNode = davItem.path("_links").path("self").path("href");

        if (hrefNode.isMissingNode() || !hrefNode.isTextual()) {
            throw new IllegalStateException("Missing or invalid 'href' field in response");
        }
        return URI.create(hrefNode.asText());
    }
}
