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
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class CollectedContactConverterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CollectedContactConverter testee = new CollectedContactConverter();

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
        CollectedContactConverter.ConvertedContact result = testee.convert(contact);

        // Then the vCard keeps the UID and contact data
        assertThat(result.uid().value()).isEqualTo("contact-uid");
        assertThat(new String(result.vcard(), StandardCharsets.UTF_8))
            .contains("BEGIN:VCARD", "VERSION:4.0", "UID:contact-uid", "FN:Bob", "END:VCARD");
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
        CollectedContactConverter.ConvertedContact result = testee.convert(contact);

        // Then a UID generated from the email is included in the vCard
        assertThat(result.uid().value()).isEqualTo("a460e37bf4d8e893f8fd39536997d5da8d21eebe");
        assertThat(new String(result.vcard(), StandardCharsets.UTF_8))
            .contains("UID:a460e37bf4d8e893f8fd39536997d5da8d21eebe");
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

        // When converting it
        // Then the conversion fails with the invalid data in the error message
        assertThatThrownBy(() -> testee.convert(contact))
            .isInstanceOf(CollectedContactConversionException.class)
            .hasMessageContaining("Unable to convert collected JSContact to vCard")
            .hasMessageContaining("\"emails\" : [ ]");
    }

    @Test
    void shouldMergeExistingEmailWithIncomingMatrixId() throws Exception {
        // Given an existing contact with an email and an incoming contact with its equivalent Matrix ID
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "@type": "Name", "full": "Old name" },
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "@type": "Name", "full": "New name" },
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then both identities are preserved with the incoming data
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("FN:New name",
                    "EMAIL;PROP-ID=main:bob@example.com",
                    "SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:example.com")
                .doesNotContain("FN:Old name"));
    }

    @Test
    void shouldMergeExistingMatrixIdWithIncomingEmail() throws Exception {
        // Given an existing contact with a Matrix ID and an incoming contact with its equivalent email
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then both identities are preserved
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("EMAIL;PROP-ID=main:bob@example.com",
                    "SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:example.com"));
    }

    @Test
    void shouldPreserveCustomVCardPropertiesWhenMergingComplementaryIdentity() throws Exception {
        // Given an existing vCard with an email and a custom property
        byte[] existingVCard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:a460e37bf4d8e893f8fd39536997d5da8d21eebe
            FN:Bob
            EMAIL:bob@example.com
            X-CUSTOM-PROPERTY:custom-value
            END:VCARD
            """.getBytes(StandardCharsets.UTF_8);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);

        // When updating it with an equivalent Matrix ID
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then the custom property and both identities are preserved in vCard 4.0
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("VERSION:4.0", "EMAIL;PROP-ID=EMAIL-1:bob@example.com", "X-CUSTOM-PROPERTY:custom-value",
                    "SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:example.com")
                .doesNotContain("VERSION:3.0"));
    }

    @Test
    void shouldReplaceEmailsInsteadOfMergingWhenBothContactsContainEmail() throws Exception {
        // Given an existing contact and an incoming contact with the same UID-generating email
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "common": { "@type": "EmailAddress", "address": "abc@example.com" },
                "old": { "@type": "EmailAddress", "address": "xyz@example.com" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "common": { "@type": "EmailAddress", "address": "abc@example.com" },
                "new": { "@type": "EmailAddress", "address": "klm@example.com" }
              }
            }
            """);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then incoming emails replace the existing email list
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("EMAIL;PROP-ID=common:abc@example.com", "EMAIL;PROP-ID=new:klm@example.com")
                .doesNotContain("xyz@example.com"));
    }

    @Test
    void shouldReplaceOnlineServicesInsteadOfMergingWhenBothContactsContainMatrixId() throws Exception {
        // Given an existing contact and an incoming contact with the same Matrix ID
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" },
                "old": { "@type": "OnlineService", "service": "old-service", "user": "old-user" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then incoming online services replace the existing services
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:example.com")
                .doesNotContain("old-service", "old-user"));
    }

    @Test
    void shouldNotMergeExistingDataWhenIncomingContactAlreadyContainsEmailAndMatrixId() throws Exception {
        // Given an existing email contact and an incoming contact with both identities
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "@type": "Name", "full": "Name to remove" },
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              },
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then the incoming contact replaces existing data
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("EMAIL;PROP-ID=main:bob@example.com",
                    "SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:example.com")
                .doesNotContain("Name to remove"));
    }

    @Test
    void shouldReturnEmptyWhenContactIsSemanticallyUnchanged() throws Exception {
        // Given an existing vCard with different line endings from its incoming JSContact
        ObjectNode contact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "@type": "Name", "full": "Bob" },
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);
        byte[] existingVCardWithDifferentLineEndings = asString(testee.convert(contact).vcard())
            .replace("\r\n", "\n")
            .getBytes(StandardCharsets.UTF_8);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCardWithDifferentLineEndings, contact);

        // Then no update is required
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSameMatrixIdIsReceivedAfterMerge() throws Exception {
        // Given an email contact already merged with an equivalent Matrix ID
        byte[] emailVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);
        ObjectNode matrixContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);
        byte[] mergedVCard = testee.convertForUpdate(emailVCard, matrixContact)
            .orElseThrow()
            .vcard();

        // When receiving the same Matrix ID again
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(mergedVCard, matrixContact);

        // Then no update is required and both identities remain in the existing contact
        assertThat(result).isEmpty();
        assertThat(asString(mergedVCard))
            .contains("EMAIL;PROP-ID=main:bob@example.com",
                "SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:example.com");
    }

    @Test
    void shouldReplaceMergedContactWhenIncomingContactContainsEmailOnly() throws Exception {
        // Given an existing contact with both an email and an equivalent Matrix ID
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              },
              "onlineServices": {
                "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:example.com" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);

        // When updating it with an email-only contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then the incoming contact replaces the Matrix ID
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("EMAIL;PROP-ID=main:bob@example.com")
                .doesNotContain("SOCIALPROFILE", "@bob:example.com"));
    }

    @Test
    void shouldReturnConvertedContactWhenSameEmailContactChanged() throws Exception {
        // Given an existing contact and an incoming contact with the same email but a changed name
        byte[] existingVCard = convertedVCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "@type": "Name", "full": "Old name" },
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);

        ObjectNode incomingContact = contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "@type": "Name", "full": "New name" },
              "emails": {
                "main": { "@type": "EmailAddress", "address": "bob@example.com" }
              }
            }
            """);

        // When updating the existing contact
        Optional<CollectedContactConverter.ConvertedContact> result = testee.convertForUpdate(existingVCard, incomingContact);

        // Then the converted contact contains the new name
        assertThat(result)
            .hasValueSatisfying(convertedContact -> assertThat(asString(convertedContact.vcard()))
                .contains("FN:New name")
                .doesNotContain("FN:Old name"));
    }

    private byte[] convertedVCard(String contact) throws Exception {
        return testee.convert(contact(contact)).vcard();
    }

    private String asString(byte[] vCard) {
        return new String(vCard, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace("\n ", "");
    }

    private ObjectNode contact(String value) throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(value);
    }
}
