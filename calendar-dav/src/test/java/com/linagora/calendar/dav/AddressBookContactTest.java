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

package com.linagora.calendar.dav;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableListMultimap;

public class AddressBookContactTest {

    @Nested
    class Parser {

        @Test
        void shouldParseSingleContact() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:1234
                FN:John Doe Mr
                N:Doe;John;;;
                EMAIL:john.doe@example.com
                TEL;TYPE=work:+123456789
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);

            assertThat(contacts.getFirst())
                .isEqualTo(AddressBookContact.builder()
                    .uid("1234")
                    .familyName("Doe")
                    .givenName("John")
                    .displayName("John Doe Mr")
                    .mail("john.doe@example.com")
                    .telephoneNumber("+123456789")
                    .build());
        }

        @Test
        void shouldHandleMissingOptionalFields() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                FN:Unknown
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst())
                .isEqualTo(AddressBookContact.builder()
                    .displayName("Unknown")
                    .build());
        }

        @Test
        void shouldHandleEmptyFields() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:
                FN:
                N:;;;;
                EMAIL:
                TEL:
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst())
                .isEqualTo(AddressBookContact.builder().build());
        }

        @Test
        void shouldHandleUnicodeCharacters() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:αβγδ
                FN:Đặng Văn Hùng
                N:Văn Đặng;Hùng;;;
                EMAIL:hung@example.com
                TEL:+84 123 456 789
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst())
                .isEqualTo(AddressBookContact.builder()
                    .uid("αβγδ")
                    .familyName("Văn Đặng")
                    .givenName("Hùng")
                    .displayName("Đặng Văn Hùng")
                    .mail("hung@example.com")
                    .telephoneNumber("+84 123 456 789")
                    .build());
        }

        @Test
        void shouldParseFieldsRegardlessOfOrder() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                TEL:+111111111
                EMAIL:foo@bar.com
                FN:Foo Bar
                UID:uuid-0001
                N:Bar;Foo;;;
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst())
                .isEqualTo(AddressBookContact.builder()
                    .uid("uuid-0001")
                    .familyName("Bar")
                    .givenName("Foo")
                    .displayName("Foo Bar")
                    .mail("foo@bar.com")
                    .telephoneNumber("+111111111")
                    .build());
        }

        @Test
        void shouldHandleTelWithParams() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:tel-test
                FN:Alice Smith
                N:Smith;Alice;;;
                TEL;TYPE=cell,voice;VALUE=uri:tel:+1-555-123-4567
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst().telephoneNumber())
                .isEqualTo(Optional.of("+1-555-123-4567"));
        }

        @Test
        void shouldHandleTelWithUriFormat() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:weird-tel
                FN:Phone Format
                N:Format;Phone;;;
                TEL;VALUE=uri:tel:+1-234-567-8910
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst().telephoneNumber())
                .isEqualTo(Optional.of("+1-234-567-8910"));
        }

        @Test
        void shouldHandleMissingGivenName() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:no-given
                FN:Mr. LastOnly
                N:LastOnly;;;;
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst().givenName())
                .isEmpty();
        }

        @Test
        void shouldHandleMissingFamilyName() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:no-family
                FN:Only Given
                N:;Only;;;
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst().familyName()).isEmpty();
        }

        @Test
        void shouldHandleMissingStructuredName() {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:no-n
                FN:Anonymous
                EMAIL:anon@example.com
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            AddressBookContact contact = contacts.getFirst();

            assertThat(contacts.getFirst())
                .isEqualTo(AddressBookContact.builder()
                    .uid("no-n")
                    .displayName("Anonymous")
                    .mail("anon@example.com")
                    .build());
        }

        @Test
        void shouldHandleEmailWithTypes() throws AddressException {
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                EMAIL;TYPE=home:home@example.com
                EMAIL;TYPE=work:work@example.com
                END:VCARD
                """;

            byte[] data = vcard.getBytes(StandardCharsets.UTF_8);
            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(1);
            assertThat(contacts.getFirst().mail())
                .isEqualTo(Optional.of(new MailAddress("home@example.com")));
        }

        @Test
        void shouldReturnEmptyListOnInvalidVcard() {
            String invalidVcard = "INVALID:VCARD\nNO:VERSION\nEND:VCARD";
            byte[] data = invalidVcard.getBytes(StandardCharsets.UTF_8);

            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).isEmpty();
        }

        @Test
        void shouldParseMultipleContacts() {
            String multiVcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:1
                FN:Alice
                N:Alice;Smith;;;
                EMAIL:alice@example.com
                END:VCARD
                BEGIN:VCARD
                VERSION:4.0
                UID:2
                FN:Bob
                N:Bob;Jones;;;
                EMAIL:bob@example.com
                TEL;TYPE=work:+12345
                END:VCARD
                """;

            byte[] data = multiVcard.getBytes(StandardCharsets.UTF_8);

            List<AddressBookContact> contacts = AddressBookContact.parse(data);

            assertThat(contacts).hasSize(2);

            assertSoftly(softly -> {
                softly.assertThat(contacts.get(0).uid()).contains("1");
                softly.assertThat(contacts.get(0).mail().map(MailAddress::asString)).contains("alice@example.com");

                softly.assertThat(contacts.get(1).uid()).contains("2");
                softly.assertThat(contacts.get(1).telephoneNumber()).contains("+12345");
            });
        }

        @Test
        void shouldParseExtraProperties() {
            // Given a card carrying the workplace of the member as an extended property
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:1234
                EMAIL:alice@example.com
                X-TWAKE-WORKPLACE-FQDN:alice.example.com
                END:VCARD
                """;

            // When the card is parsed
            List<AddressBookContact> contacts = AddressBookContact.parse(vcard.getBytes(StandardCharsets.UTF_8));

            // Then the extended property is read along the standard ones
            assertThat(contacts.getFirst().extraProperties())
                .isEqualTo(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"));
        }

        @Test
        void shouldParseEveryValueOfRepeatedExtraProperty() {
            // Given a card carrying the same extended property twice
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:1234
                X-INFO:abcdef
                X-INFO:R&D
                END:VCARD
                """;

            // When the card is parsed
            List<AddressBookContact> contacts = AddressBookContact.parse(vcard.getBytes(StandardCharsets.UTF_8));

            // Then both values are kept, in the card order
            assertThat(contacts.getFirst().extraProperties())
                .isEqualTo(ImmutableListMultimap.of("X-INFO", "abcdef", "X-INFO", "R&D"));
        }

        @Test
        void shouldParseExtraPropertyNamesInUpperCase() {
            // Given a card whose extended property name is written in lower case
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:1234
                x-twake-workplace-fqdn:alice.example.com
                END:VCARD
                """;

            // When the card is parsed
            List<AddressBookContact> contacts = AddressBookContact.parse(vcard.getBytes(StandardCharsets.UTF_8));

            // Then the property name is normalized to upper case
            assertThat(contacts.getFirst().extraProperties())
                .isEqualTo(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"));
        }

        @Test
        void shouldIgnoreUnknownPropertiesThatAreNotExtended() {
            // Given a card carrying a property ez-vcard does not know, without the X- prefix
            String vcard = """
                BEGIN:VCARD
                VERSION:4.0
                UID:1234
                FOO:bar
                END:VCARD
                """;

            // When the card is parsed
            List<AddressBookContact> contacts = AddressBookContact.parse(vcard.getBytes(StandardCharsets.UTF_8));

            // Then that property is not taken as an extra property
            assertThat(contacts.getFirst().extraProperties()).isEqualTo(ImmutableListMultimap.of());
        }
    }

    @Nested
    class ToVcard {

        @Test
        void shouldBuildVcardWithAllFields() {
            AddressBookContact card = AddressBookContact.builder()
                .uid("u-123")
                .familyName("Doe")
                .givenName("John")
                .displayName("John Doe")
                .mail("john@example.com")
                .telephoneNumber("+12345678")
                .build();

            String vcard = card.toVcardString();
            assertThat(vcard)
                .isEqualToIgnoringNewLines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:u-123
                    FN:John Doe
                    N:Doe;John;;;
                    EMAIL:john@example.com
                    TEL;TYPE=work:+12345678
                    END:VCARD""".trim());
        }

        @Test
        void shouldGenerateRandomUidIfMissing() {
            AddressBookContact card = AddressBookContact.builder()
                .familyName("Nguyen")
                .givenName("Tuan")
                .displayName("Tuan Nguyen")
                .mail("tuan@example.com")
                .build();

            String vcard = card.toVcardString();

            assertSoftly(softly -> {
                softly.assertThat(vcard).contains("FN:Tuan Nguyen");
                softly.assertThat(vcard).contains("UID:");
                softly.assertThat(vcard).matches("(?s).*UID:[a-zA-Z0-9\\-]+.*");
            });
        }

        @Test
        void shouldUseEmptyFormattedNameIfNoDisplayNameOrEmail() {
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-blank")
                .familyName("Vo")
                .givenName("Minh")
                .build();

            String vcard = card.toVcardString();

            assertThat(vcard).isEqualToIgnoringNewLines("""
                BEGIN:VCARD
                VERSION:4.0
                UID:uid-blank
                N:Vo;Minh;;;
                END:VCARD""".trim());
        }

        @Test
        void shouldHandleNoStructuredName() {
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-no-name")
                .displayName("Only Display")
                .mail("display@example.com")
                .build();

            String vcard = card.toVcardString();

            assertThat(vcard).isEqualToIgnoringNewLines("""
                BEGIN:VCARD
                VERSION:4.0
                UID:uid-no-name
                FN:Only Display
                EMAIL:display@example.com
                END:VCARD""".trim());
        }

        @Test
        void shouldHandleNoEmailOrPhone() {
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-abc")
                .familyName("Tran")
                .givenName("Nam")
                .displayName("Nam Tran")
                .build();

            String vcard = card.toVcardString();

            assertThat(vcard).isEqualToIgnoringNewLines("""
                BEGIN:VCARD
                VERSION:4.0
                UID:uid-abc
                FN:Nam Tran
                N:Tran;Nam;;;
                END:VCARD""".trim());
        }

        @Test
        void shouldTrimWhitespaceInFields() {
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-trim")
                .givenName("  Le  ")
                .familyName("   Mai   ")
                .displayName("  Mai Le  ")
                .mail("mai@example.com")
                .telephoneNumber("  +84991234567  ")
                .build();

            String vcard = card.toVcardString();

            assertThat(vcard).isEqualToIgnoringNewLines("""
                BEGIN:VCARD
                VERSION:4.0
                UID:uid-trim
                FN:Mai Le
                N:Mai;Le;;;
                EMAIL:mai@example.com
                TEL;TYPE=work:+84991234567
                END:VCARD""".trim());
        }

        @Test
        void shouldBuildVcardWithExtraProperties() {
            // Given a contact carrying the workplace of the member
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-fqdn")
                .mail("alice@example.com")
                .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"))
                .build();

            // When the card is written
            String vcard = card.toVcardString();

            // Then the workplace is written as an extended property
            assertThat(vcard).isEqualToIgnoringNewLines("""
                BEGIN:VCARD
                VERSION:4.0
                UID:uid-fqdn
                EMAIL:alice@example.com
                X-TWAKE-WORKPLACE-FQDN:alice.example.com
                END:VCARD""".trim());
        }

        @Test
        void shouldRoundTripExtraProperties() {
            // Given a contact carrying the workplace of the member
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-fqdn")
                .mail("alice@example.com")
                .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"))
                .build();

            // When the card is written then parsed back
            List<AddressBookContact> contacts = AddressBookContact.parse(card.toVcardBytes());

            // Then the parsed contact equals the original one
            assertThat(contacts).containsExactly(card);
        }

        @Test
        void shouldRoundTripRepeatedExtraProperty() {
            // Given a contact carrying two values for the same extended property
            AddressBookContact card = AddressBookContact.builder()
                .uid("uid-info")
                .mail("alice@example.com")
                .extraProperties(ImmutableListMultimap.of("X-INFO", "abcdef", "X-INFO", "R&D"))
                .build();

            // When the card is written then parsed back
            List<AddressBookContact> contacts = AddressBookContact.parse(card.toVcardBytes());

            // Then both values are written as two properties and read back in the same order
            assertThat(card.toVcardString()).contains("X-INFO:abcdef\r\nX-INFO:R&D\r\n");
            assertThat(contacts).containsExactly(card);
        }
    }

    @Nested
    class HasSameContentAs {
        private final AddressBookContact contact = AddressBookContact.builder()
            .uid("uid-1")
            .familyName("Martin")
            .givenName("Alice")
            .displayName("Alice Martin")
            .mail("alice@example.com")
            .telephoneNumber("+33600000000")
            .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"))
            .build();

        private AddressBookContact.Builder copy() {
            return AddressBookContact.builder()
                .uid(contact.uid())
                .familyName(contact.familyName())
                .givenName(contact.givenName())
                .displayName(contact.displayName())
                .mail(contact.mail())
                .telephoneNumber(contact.telephoneNumber())
                .extraProperties(contact.extraProperties());
        }

        @Test
        void shouldIgnoreUid() {
            // Given the same contact stored under another uid
            AddressBookContact other = copy().uid("uid-2").build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then they hold the same content
            assertThat(sameContent).isTrue();
        }

        @Test
        void shouldCompareFamilyName() {
            // Given the same contact with another family name
            AddressBookContact other = copy().familyName("Durand").build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then the content differs
            assertThat(sameContent).isFalse();
        }

        @Test
        void shouldCompareGivenName() {
            // Given the same contact with another given name
            AddressBookContact other = copy().givenName("Alicia").build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then the content differs
            assertThat(sameContent).isFalse();
        }

        @Test
        void shouldCompareDisplayName() {
            // Given the same contact with another display name
            AddressBookContact other = copy().displayName("A. Martin").build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then the content differs
            assertThat(sameContent).isFalse();
        }

        @Test
        void shouldCompareMail() {
            // Given the same contact with another mail
            AddressBookContact other = copy().mail("alice.martin@example.com").build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then the content differs
            assertThat(sameContent).isFalse();
        }

        @Test
        void shouldCompareTelephoneNumber() {
            // Given the same contact with another telephone number
            AddressBookContact other = copy().telephoneNumber("+33611111111").build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then the content differs
            assertThat(sameContent).isFalse();
        }

        @Test
        void shouldCompareExtraProperties() {
            // Given the same contact whose workplace is not known yet
            AddressBookContact other = copy().extraProperties(ImmutableListMultimap.of()).build();

            // When both contacts are compared
            boolean sameContent = contact.hasSameContentAs(other);

            // Then the content differs
            assertThat(sameContent).isFalse();
        }
    }
}
