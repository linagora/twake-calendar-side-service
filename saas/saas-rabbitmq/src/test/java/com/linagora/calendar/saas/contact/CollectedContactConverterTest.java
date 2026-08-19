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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class CollectedContactConverterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CollectedContactConverter testee = new CollectedContactConverter();

    @Test
    void shouldConvertJSContactToVCardAndKeepUid() throws Exception {
        CollectedContactConverter.ConvertedContact result = testee.convert(contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "name": {
                "@type": "Name",
                "full": "Bob"
              }
            }
            """));

        assertThat(result.uid().value()).isEqualTo("contact-uid");
        assertThat(new String(result.vcard(), StandardCharsets.UTF_8))
            .contains("BEGIN:VCARD", "VERSION:4.0", "UID:contact-uid", "FN:Bob", "END:VCARD");
    }

    @Test
    void shouldIncludeGeneratedUidInVCard() throws Exception {
        CollectedContactConverter.ConvertedContact result = testee.convert(contact("""
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
            """));

        assertThat(result.uid().value()).isEqualTo("a460e37bf4d8e893f8fd39536997d5da8d21eebe");
        assertThat(new String(result.vcard(), StandardCharsets.UTF_8))
            .contains("UID:a460e37bf4d8e893f8fd39536997d5da8d21eebe");
    }

    @Test
    void shouldThrowWhenJSContactIsInvalid() throws Exception {
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "emails": []
            }
            """);

        assertThatThrownBy(() -> testee.convert(contact))
            .isInstanceOf(CollectedContactConversionException.class)
            .hasMessageContaining("Unable to convert collected JSContact to vCard")
            .hasMessageContaining("\"emails\" : [ ]");
    }

    private ObjectNode contact(String value) throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(value);
    }
}
