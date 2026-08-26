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

package com.linagora.calendar.saas.contact;

import static com.linagora.calendar.saas.contact.CollectedContact.CollectedContactException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class CollectedContactTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldConvertJSContactToVCardAndKeepUid() throws Exception {
        // Given a JSContact with an explicit UID
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "name": {
                "@type": "Name",
                "full": "Bob"
              }
            }
            """);

        // When converting it to vCard
        CollectedContact result = CollectedContact.parse(contact);

        // Then the vCard keeps the UID and contact data
        assertThat(result.uid().value()).isEqualTo("contact-uid");
        assertThat(result.toVCardAsBytes()).asString(StandardCharsets.UTF_8)
            .contains("BEGIN:VCARD", "VERSION:4.0", "UID:contact-uid", "FN:Bob", "END:VCARD")
            .doesNotContain("PRODID:");
    }

    @Test
    void shouldIncludeGeneratedUidInVCard() throws Exception {
        // Given a JSContact without UID and with an email address
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": {
                "@type": "Name",
                "full": "Bob"
              },
              "emails": {
                "main": {
                  "@type": "EmailAddress",
                  "address": "bob@example.com"
                }
              }
            }
            """);

        // When converting it to vCard
        CollectedContact result = CollectedContact.parse(contact);

        // Then a UID generated from the email is included in the vCard
        assertThat(result.uid().value()).isEqualTo("a460e37bf4d8e893f8fd39536997d5da8d21eebe");
        assertThat(result.toVCardAsBytes()).asString(StandardCharsets.UTF_8)
            .contains("UID:a460e37bf4d8e893f8fd39536997d5da8d21eebe");
    }

    @Test
    void shouldParseVCard() throws Exception {
        byte[] vCard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:contact-uid
            FN:Bob
            EMAIL:bob@example.com
            SOCIALPROFILE;SERVICE-TYPE=matrix;VALUE=text:@bob:example.com
            END:VCARD
            """.getBytes(StandardCharsets.UTF_8);

        CollectedContact result = CollectedContact.parse(vCard);

        assertThat(result.uid().value()).isEqualTo("contact-uid");
        assertThat(result.hasEmailAddress()).isTrue();
        assertThat(result.hasMatrixId()).isTrue();
        assertThat(result.toJson()).contains("Bob", "bob@example.com", "@bob:example.com");
    }

    @Test
    void shouldSerializeStructuredNameAndAddress() throws Exception {
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "name": {
                "@type": "Name",
                "components": [
                  { "@type": "NameComponent", "kind": "given", "value": "Bob" },
                  { "@type": "NameComponent", "kind": "surname", "value": "Doe" }
                ],
                "isOrdered": true,
                "defaultSeparator": " "
              },
              "addresses": {
                "home": {
                  "@type": "Address",
                  "components": [
                    { "@type": "AddressComponent", "kind": "number", "value": "42" },
                    { "@type": "AddressComponent", "kind": "name", "value": "Example Street" },
                    { "@type": "AddressComponent", "kind": "locality", "value": "Paris" },
                    { "@type": "AddressComponent", "kind": "country", "value": "France" }
                  ],
                  "isOrdered": true,
                  "defaultSeparator": " ",
                  "full": "42 Example Street, Paris, France"
                }
              }
            }
            """);

        byte[] result = CollectedContact.parse(contact).toVCardAsBytes();

        assertThat(result).asString(StandardCharsets.UTF_8)
            .contains("N;JSCOMPS=", "Bob", "Doe", "ADR;JSCOMPS=", "42", "Example Street", "Paris", "France");
    }

    @Test
    void shouldApplyCaretEncodingToVCardParameters() throws Exception {
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "vCardProps": [
                ["x-test", {"x-label": "Line 1\\nLine 2"}, "text", "value"]
              ]
            }
            """);

        byte[] result = CollectedContact.parse(contact).toVCardAsBytes();

        assertThat(result).asString(StandardCharsets.UTF_8)
            .contains("X-TEST;X-LABEL=Line 1^nLine 2;VALUE=text:value");
    }

    @Test
    void shouldThrowWhenJSContactIsInvalid() throws Exception {
        // Given an invalid JSContact
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "emails": []
            }
            """);

        // When parsing it
        // Then parsing fails
        assertThatThrownBy(() -> CollectedContact.parse(contact))
            .isInstanceOf(CollectedContactException.class)
            .hasMessage("Unable to parse collected JSContact");
    }

    @Test
    void shouldThrowWhenVCardIsInvalid() {
        assertThatThrownBy(() -> CollectedContact.parse("not-a-vcard".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CollectedContactException.class)
            .hasMessage("Unable to parse collected vCard");
    }

    private ObjectNode contact(String value) throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(value);
    }
}
