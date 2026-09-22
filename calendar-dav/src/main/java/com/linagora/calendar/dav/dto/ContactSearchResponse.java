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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Streams;
import com.linagora.calendar.dav.DavClientException;

public record ContactSearchResponse(List<JsonNode> items) {
    public static class InvalidContactSearchResponseException extends DavClientException {
        public InvalidContactSearchResponseException() {
            super("Invalid DAV contact search response");
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static ContactSearchResponse parse(String payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode entries = root == null ? null : root.path("_embedded").path("dav:item");
            if (entries == null || !entries.isArray()) {
                throw new IllegalArgumentException("Missing DAV items");
            }
            for (JsonNode item : entries) {
                if (!item.isObject() || !item.path("_links").path("self").path("href").isTextual()
                    || !item.path("etag").isTextual() || !item.path("data").isArray()) {
                    throw new IllegalArgumentException("Invalid DAV item");
                }
            }
            return new ContactSearchResponse(Streams.stream(entries.elements()).toList());
        } catch (Exception e) {
            // Parser exceptions can contain contact data, so do not retain their messages or causes.
            throw new InvalidContactSearchResponseException();
        }
    }
}
