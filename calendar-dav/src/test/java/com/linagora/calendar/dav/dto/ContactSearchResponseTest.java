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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.calendar.dav.dto.ContactSearchResponse.InvalidContactSearchResponseException;

class ContactSearchResponseTest {

    @Test
    void shouldPreserveDavResourcesAndTheirOrder() {
        String payload = """
            {
              "_embedded": {
                "dav:item": [
                  {
                    "_links": {
                      "self": { "href": "/addressbooks/alice/contacts/b.vcf" },
                      "alternate": { "href": "/contacts/b" }
                    },
                    "etag": "etag-b",
                    "data": ["vcard", [["uid", {}, "text", "b"], ["fn", {}, "text", "Grepme Bob"]]],
                    "custom": { "nested": true }
                  },
                  {
                    "_links": {
                      "self": { "href": "/addressbooks/alice/contacts/a.vcf" }
                    },
                    "etag": "etag-a",
                    "data": ["vcard", [["uid", {}, "text", "a"]]]
                  }
                ]
              }
            }
            """;

        ContactSearchResponse response = ContactSearchResponse.parse(payload);

        assertThat(response.items()).hasSize(2);
        assertThatJson(response.items().get(0).toString()).isEqualTo("""
            {
              "_links": {
                "self": { "href": "/addressbooks/alice/contacts/b.vcf" },
                "alternate": { "href": "/contacts/b" }
              },
              "etag": "etag-b",
              "data": ["vcard", [["uid", {}, "text", "b"], ["fn", {}, "text", "Grepme Bob"]]],
              "custom": { "nested": true }
            }
            """);
        assertThatJson(response.items().get(1).toString()).inPath("_links.self.href")
            .isEqualTo("/addressbooks/alice/contacts/a.vcf");
    }

    @Test
    void shouldAcceptEmptyDavItemList() {
        ContactSearchResponse response = ContactSearchResponse.parse("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);

        assertThat(response.items()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void shouldRejectInvalidDavResponsesWithoutExposingPayload(String scenario, String payload) {
        assertThatThrownBy(() -> ContactSearchResponse.parse(payload))
            .isExactlyInstanceOf(InvalidContactSearchResponseException.class)
            .hasMessage("Invalid DAV contact search response")
            .hasNoCause();
    }

    private static Stream<Arguments> invalidPayloads() {
        return Stream.of(
            Arguments.of("malformed JSON", "{private-contact-data"),
            Arguments.of("trailing JSON token", """
                {
                  "_embedded": {
                    "dav:item": []
                  }
                }
                { "private-contact-data": true }
                """),
            Arguments.of("null root", "null"),
            Arguments.of("missing embedded items", """
                {
                  "_embedded": {}
                }
                """),
            Arguments.of("items are not an array", """
                {
                  "_embedded": {
                    "dav:item": {}
                  }
                }
                """),
            Arguments.of("null item", """
                {
                  "_embedded": {
                    "dav:item": [null]
                  }
                }
                """),
            Arguments.of("missing href", """
                {
                  "_embedded": {
                    "dav:item": [
                      { "_links": { "self": {} }, "etag": "etag", "data": [] }
                    ]
                  }
                }
                """),
            Arguments.of("non-text etag", """
                {
                  "_embedded": {
                    "dav:item": [
                      { "_links": { "self": { "href": "/private.vcf" } }, "etag": 123, "data": [] }
                    ]
                  }
                }
                """),
            Arguments.of("non-array jCard", """
                {
                  "_embedded": {
                    "dav:item": [
                      { "_links": { "self": { "href": "/private.vcf" } }, "etag": "etag", "data": "private-contact-data" }
                    ]
                  }
                }
                """));
    }
}
