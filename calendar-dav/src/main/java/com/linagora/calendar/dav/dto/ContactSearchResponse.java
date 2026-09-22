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
            requireValid(root != null);
            JsonNode entries = root.path("_embedded").path("dav:item");
            requireValid(entries.isArray());
            entries.forEach(ContactSearchResponse::validateItem);
            return new ContactSearchResponse(Streams.stream(entries.elements()).toList());
        } catch (Exception e) {
            // Parser exceptions can contain contact data, so do not retain their messages or causes.
            throw new InvalidContactSearchResponseException();
        }
    }

    private static void validateItem(JsonNode item) {
        requireValid(item.isObject());
        requireValid(item.path("_links").path("self").path("href").isTextual());
        requireValid(item.path("etag").isTextual());
        requireValid(item.path("data").isArray());
    }

    private static void requireValid(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid DAV contact search response");
        }
    }
}
