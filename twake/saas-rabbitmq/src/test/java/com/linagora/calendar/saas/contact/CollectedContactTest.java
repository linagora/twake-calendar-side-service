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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;

import ezvcard.Ezvcard;
import ezvcard.VCard;

class CollectedContactTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "\"name\":{\"@type\":\"Name\",\"foo\":\"x\"}",
        "\"speakToAs\":{\"@type\":\"SpeakToAs\",\"foo\":\"x\"}",
        "\"organizations\":{\"o\":{\"@type\":\"Organization\",\"foo\":\"x\"}}"
    })
    void aFieldHoldingOnlyAnUnknownPropertyShouldNotCostTheContact(String field) throws Exception {
        CollectedContact incoming = CollectedContact.parse((ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}},
             %s}
            """.formatted(field)));

        CollectedContact stored = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(stored.uid()).isEqualTo(incoming.uid());
        assertThat(stored.hasEmailAddress()).isTrue();
    }

    @Test
    void oneUnreadableEntryShouldNotCostTheOtherEntriesOfAMap() throws Exception {
        CollectedContact incoming = CollectedContact.parse((ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}},
             "organizations": {"o": {"@type": "Organization", "foo": "x"}, "p": {"@type": "Organization", "name": "Linagora"}}}
            """));

        assertThat(CollectedContact.parse(incoming.toVCardAsBytes()).toJson()).contains("Linagora");
    }

    @Test
    void readableUnknownPropertiesShouldBeKept() throws Exception {
        CollectedContact incoming = CollectedContact.parse((ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}},
             "name": {"@type": "Name", "full": "Bob", "foo": "x"}}
            """));

        assertThat(CollectedContact.parse(incoming.toVCardAsBytes()).toJson()).contains("Bob");
    }

    @Test
    void storedCardHoldingAnUnreadableJSPropShouldBeReadable() {
        byte[] vCard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:a
            FN;DERIVED=true:a
            EMAIL:alice@example.com
            JSPROP;JSPTR=name/foo;VALUE=text:"x"
            END:VCARD
            """.getBytes(StandardCharsets.UTF_8);

        CollectedContact result = CollectedContact.parse(vCard);

        assertThat(result.hasEmailAddress()).isTrue();
    }

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
    void contactWithoutNameShouldNotGetItsUidAsFormattedName() throws Exception {
        CollectedContact result = CollectedContact.parse(contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"main": {"@type": "EmailAddress", "address": "someone@example.com"}}}
            """));

        VCard vCard = Ezvcard.parse(new String(result.toVCardAsBytes(), StandardCharsets.UTF_8)).first();

        assertThat(vCard.getFormattedName().getValue()).isEmpty();
        assertThat(vCard.getFormattedName().getParameter("DERIVED")).isNull();
        assertThat(new String(result.toVCardAsBytes(), StandardCharsets.UTF_8)).doesNotContain("FN;DERIVED=true:" + result.uid().value());
    }

    @Test
    void contactWithoutNameShouldBeReadableBackWithoutUpdate() throws Exception {
        CollectedContact incoming = CollectedContact.parse(contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"main": {"@type": "EmailAddress", "address": "someone@example.com"}}}
            """));

        CollectedContact stored = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(stored.uid()).isEqualTo(incoming.uid());
        assertThat(stored.hasEmailAddress()).isTrue();
        assertThat(new CollectedContactUpdateCalculator().calculate(stored, incoming)).isEmpty();
    }

    @Test
    void contactNamedLikeItsUidShouldKeepItsName() throws Exception {
        CollectedContact result = CollectedContact.parse(contact("""
            {"@type": "Card", "version": "2.0", "uid": "bob",
             "name": {"@type": "Name", "full": "bob"}}
            """));

        assertThat(Ezvcard.parse(new String(result.toVCardAsBytes(), StandardCharsets.UTF_8)).first().getFormattedName().getValue())
            .isEqualTo("bob");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", ".."})
    void collectedContactShouldMapADotSegmentUidToAResourceNameThatMakesAUri(String uid) throws Exception {
        ObjectNode contact = (ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "%s",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}}}
            """.formatted(uid));

        CollectedContact result = CollectedContact.parse(contact);
        AddressBookURL collected = new AddressBookURL(new OpenPaaSId("65f1c0de0123456789abcdef"), "collected");

        assertThat(result.uid().value()).isEqualTo(uid);
        assertThat(result.resourceName().value()).isEqualTo(CollectedContact.sha1(uid));
        assertThat(collected.vcardUri(result.resourceName().value()).toString()).endsWith("/" + CollectedContact.sha1(uid) + ".vcf");
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

    @ParameterizedTest
    @ValueSource(strings = {"a\\rb", "\\ra", "a\\r\\nb", "a\\u0000b", "a\\u0001b", "a\\u007fb", "a\\u0085b", "a\\nb", "a\\tb", "\\ud83d", "a\\ud83db", "\\ude00a"})
    void uidThatAVCardCannotCarryShouldGiveWayToAGeneratedUid(String jsonEscapedUid) throws Exception {
        ObjectNode contact = (ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "%s",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}}}
            """.formatted(jsonEscapedUid));

        CollectedContact incoming = CollectedContact.parse(contact);
        CollectedContact stored = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(incoming.uid().value()).isEqualTo(CollectedContact.sha1("alice@example.com"));
        assertThat(stored.uid()).isEqualTo(incoming.uid());
        assertThat(stored.resourceName()).isEqualTo(incoming.resourceName());
    }

    @Test
    void uidThatAVCardCannotCarryShouldNotMakeACardWithoutIdentityReadable() throws Exception {
        ObjectNode contact = (ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "a\\rb"}
            """);

        assertThatThrownBy(() -> CollectedContact.parse(contact))
            .isInstanceOf(CollectedContactException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u00e9", "\\u65e5\\u672c", "\\ud83d\\ude00", "a b", " a", "a;b", "a,b", "a\\\\b", "a\\u00a0b", "a\\u2028b"})
    void otherUidsShouldBeStableThroughTheVCard(String jsonEscapedUid) throws Exception {
        ObjectNode contact = (ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "%s",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}}}
            """.formatted(jsonEscapedUid));

        CollectedContact incoming = CollectedContact.parse(contact);

        assertThat(incoming.uid().value()).isNotEqualTo(CollectedContact.sha1("alice@example.com"));
        assertThat(CollectedContact.parse(incoming.toVCardAsBytes()).uid()).isEqualTo(incoming.uid());
    }

    @Test
    void storedCardHoldingAControlCharacterInItsUidShouldKeepIt() {
        byte[] vCard = """
            BEGIN:VCARD\r
            VERSION:4.0\r
            UID:a\\nb\r
            FN:Alice\r
            EMAIL:alice@example.com\r
            END:VCARD\r
            """.getBytes(StandardCharsets.UTF_8);

        assertThat(CollectedContact.parse(vCard).uid().value()).isEqualTo("a\nb");
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

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void vCardWrittenFromAnOutOfRangePrefShouldBeReadableBack(int pref) throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com", "pref": %d}}}
            """.formatted(pref));

        CollectedContact incoming = CollectedContact.parse(contact);
        CollectedContact stored = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(stored.uid()).isEqualTo(incoming.uid());
        assertThat(new CollectedContactUpdateCalculator().calculate(stored, incoming)).isEmpty();
    }

    @Test
    void outOfRangePrefsShouldBeClampedOnEveryProperty() throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com", "pref": 0}},
             "phones": {"p": {"@type": "Phone", "number": "+33123456789", "pref": 101}},
             "onlineServices": {"m": {"@type": "OnlineService", "service": "matrix", "user": "@alice:example.com", "pref": 500}}}
            """);

        assertThat(CollectedContact.parse(contact).toVCardAsBytes()).asString(StandardCharsets.UTF_8)
            .contains("EMAIL;PREF=1", "PREF=100")
            .doesNotContain("PREF=0", "PREF=101", "PREF=500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"work_1", "wérk", "a.b", "a b", ""})
    void vCardOfALabelledEntryWithUnsafeIdShouldBeReadableBack(String id) throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"%1$s": {"@type": "EmailAddress", "address": "bob@example.com", "label": "Work"}},
             "onlineServices": {"%1$s": {"@type": "OnlineService", "service": "matrix", "user": "@bob:example.com", "label": "Chat"}}}
            """.formatted(id));

        CollectedContact incoming = CollectedContact.parse(contact);
        CollectedContact reread = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(reread.uid()).isEqualTo(incoming.uid());
        assertThat(reread.hasEmailAddress()).isTrue();
        assertThat(reread.hasMatrixId()).isTrue();
    }

    @Test
    void safeIdsShouldBeKept() throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"work-1": {"@type": "EmailAddress", "address": "bob@example.com", "label": "Work"}}}
            """);

        assertThat(CollectedContact.parse(contact).toVCardAsBytes()).asString(StandardCharsets.UTF_8)
            .contains("G-work-1.EMAIL");
    }

    @Test
    void unsafeIdsShouldBeRenamedDeterministically() throws Exception {
        String json = """
            {"@type": "Card", "version": "2.0",
             "emails": {"work_1": {"@type": "EmailAddress", "address": "bob@example.com", "label": "Work"}}}
            """;

        assertThat(CollectedContact.parse(contact(json)).toJson())
            .isEqualTo(CollectedContact.parse(contact(json)).toJson())
            .doesNotContain("work_1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPreserveBothEmailsWhenAnIdMatchesTheHashOfAnotherId(boolean reverseOrder) throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid", "emails": {}}
            """);
        ObjectNode emails = (ObjectNode) input.get("emails");
        // SHA-1 of "work_1": this is also a valid, independent JSContact id.
        String hashedId = "df48c7e96343e2216562834e1856020763c00e54";
        ObjectNode firstEmail = contact("""
            {"@type": "EmailAddress", "address": "first@example.com"}
            """);
        ObjectNode secondEmail = contact("""
            {"@type": "EmailAddress", "address": "second@example.com"}
            """);
        if (reverseOrder) {
            emails.set(hashedId, secondEmail);
            emails.set("work_1", firstEmail);
        } else {
            emails.set("work_1", firstEmail);
            emails.set(hashedId, secondEmail);
        }

        CollectedContact result = CollectedContact.parse(input);

        assertThatJson(result.toJson()).inPath("emails").isObject().hasSize(2);
        assertThat(Ezvcard.parse(new String(result.toVCardAsBytes(), StandardCharsets.UTF_8)).first().getEmails())
            .extracting(email -> email.getValue())
            .containsExactlyInAnyOrder("first@example.com", "second@example.com");
    }

    private CollectedContact contactWithVCardProps(String vCardProps) throws Exception {
        return CollectedContact.parse((ObjectNode) OBJECT_MAPPER.readTree("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}},
             "vCardProps": %s}
            """.formatted(vCardProps)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // names
        "[\"x-a_b\", {}, \"text\", \"c\"]", "[\"x-ak`\", {}, \"text\", \"c\"]", "[\"x a\", {}, \"text\", \"c\"]", "[\"x,a\", {}, \"text\", \"c\"]",
        "[\"x=a\", {}, \"text\", \"c\"]", "[\"x-\\u00e9\", {}, \"text\", \"c\"]", "[\"x-a\\u0000\", {}, \"text\", \"c\"]", "[\"\", {}, \"text\", \"c\"]",
        "[null, {}, \"text\", \"c\"]", "[1, {}, \"text\", \"c\"]",
        // parameters
        "[\"x-a\", {\"a_b\": \"c\"}, \"text\", \"c\"]", "[\"x-a\", {\"a b\": \"c\"}, \"text\", \"c\"]", "[\"x-a\", {\"pref\": \"200\"}, \"text\", \"c\"]", "[\"x-a\", {\"group\": \"g_1\"}, \"text\", \"c\"]", "[\"x-a\", {\"a\": null}, \"text\", \"c\"]",
        "[\"x-a\", {\"a\": [\"x\", \"y\"]}, \"text\", \"c\"]", "[\"x-a\", null, \"text\", \"c\"]", "[\"x-a\", [], \"text\", \"c\"]",
        // value types and values
        "[\"x-a\", {}, \"te xt\", \"c\"]", "[\"x-a\", {}, \"\", \"c\"]", "[\"x-a\", {}, null, \"c\"]", "[\"x-a\", {}, 1, \"c\"]",
        "[\"x-a\", {}, \"text\", null]", "[\"x-a\", {}, \"text\", [\"a\", \"b\"]]", "[\"x-a\", {}, \"text\", {\"a\": 1}]",
        // shapes
        "[]", "[1]", "[\"x-a\"]", "[\"x-a\", {}]", "[\"x-a\", {}, \"text\"]", "\"str\"", "1", "null", "{}",
        // the structure of the card
        "[\"begin\", {}, \"text\", \"vcard\"]", "[\"END\", {}, \"text\", \"vcard\"]", "[\"uid\", {}, \"text\", \"other\"]", "[\"version\", {}, \"text\", \"3.0\"]"
    })
    void vCardPropsAVCardCannotCarryShouldBeDroppedWithoutCostingTheContact(String property) throws Exception {
        CollectedContact incoming = contactWithVCardProps("[" + property + ", [\"x-kept\", {}, \"text\", \"kept\"]]");
        String vCard = new String(incoming.toVCardAsBytes(), StandardCharsets.UTF_8);
        CollectedContact stored = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(vCard).contains("X-KEPT", "VERSION:4.0").doesNotContain("X-A", "OTHER", "other", "VCARD;", "VALUE=text:vcard");
        assertThat(stored.uid()).isEqualTo(incoming.uid());
        assertThat(stored.hasEmailAddress()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[\"x-test\", {}, \"text\", \"c\"]", "[\"X-TEST-2\", {\"x-a\": \"b\", \"x-c-d\": \"e f\"}, \"text\", \"c\"]", "[\"x-test\", {\"a\": 1}, \"integer\", 1]",
        "[\"x-test\", {\"x-a\": \"x\\ny\"}, \"text\", \"c\"]", "[\"x-test\", {\"x-a\": \"x\\\"y\"}, \"text\", \"c\"]",
        "[\"x-test\", {\"group\": \"g-1\"}, \"text\", \"c\"]", "[\"x-test\", {}, \"unknown\", \"c\"]", "[\"x-test\", {}, \"text\", \"a\\nb\"]", "[\"x-test\", {}, \"text\", \"\"]"
    })
    void validVCardPropsShouldBeKept(String property) throws Exception {
        CollectedContact incoming = contactWithVCardProps("[" + property + "]");

        assertThat(new String(incoming.toVCardAsBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)).contains("x-test");
        assertThat(CollectedContact.parse(incoming.toVCardAsBytes()).uid()).isEqualTo(incoming.uid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\": \"b\"}", "{\"type\": \"home\"}", "{\"a\": \"x\\ny\"}", "{\"a\": \"x\\\"y\"}", "{\"x-label\": \"Line 1\\nLine 2\"}"})
    void vCardPropsWithATextParameterShouldKeepTheirValueAndBeReadableBack(String parameters) throws Exception {
        CollectedContact incoming = contactWithVCardProps("[[\"x-test\", %s, \"text\", \"kept\"]]".formatted(parameters));

        CollectedContact stored = CollectedContact.parse(incoming.toVCardAsBytes());

        assertThat(stored.uid()).isEqualTo(incoming.uid());
        assertThat(stored.hasEmailAddress()).isTrue();
        assertThat(stored.toJson()).contains("x-test", "kept");
    }

    @Test
    void storedCardHoldingATextParameterOnAnExtensionShouldBeReadable() throws Exception {
        byte[] vCard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:contact-uid
            FN:Bob
            EMAIL:bob@example.com
            SOCIALPROFILE;SERVICE-TYPE=matrix;VALUE=text:@bob:example.com
            X-TEST;X-LABEL=hello;VALUE=text:kept
            END:VCARD
            """.getBytes(StandardCharsets.UTF_8);

        CollectedContact result = CollectedContact.parse(vCard);

        assertThat(result.hasEmailAddress()).isTrue();
        assertThat(result.hasMatrixId()).isTrue();
        assertThat(result.toJson()).contains("kept", "@bob:example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[[\"x-a_b\", {}, \"text\", \"c\"]]", "[[]]"})
    void contactLeftWithoutVCardPropsShouldSerializeToValidJson(String vCardProps) throws Exception {
        assertThatCode(() -> OBJECT_MAPPER.readTree(contactWithVCardProps(vCardProps).toJson()))
            .doesNotThrowAnyException();
    }

    @Test
    void version4VCardPropsShouldBeKept() throws Exception {
        CollectedContact incoming = contactWithVCardProps("[[\"version\", {}, \"text\", \"4.0\"]]");

        assertThat(new String(incoming.toVCardAsBytes(), StandardCharsets.UTF_8)).contains("VERSION:4.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"x\"", "1", "null", "{}", "[]"})
    void vCardPropsThatAreNotAListShouldNotCostTheContact(String vCardProps) throws Exception {
        CollectedContact incoming = contactWithVCardProps(vCardProps);

        assertThat(CollectedContact.parse(incoming.toVCardAsBytes()).hasEmailAddress()).isTrue();
    }

    @Test
    void shouldKeepTitleReferenceToAnExistingOrganization() throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid",
             "organizations": {"work_1": {"@type": "Organization", "name": "Acme"}},
             "titles": {"title1": {"@type": "Title", "kind": "title", "name": "Engineer", "organizationId": "work_1"}}}
            """);

        ObjectNode result = contact(CollectedContact.parse(input).toJson());

        String organizationId = result.at("/titles/title1/organizationId").asText();
        assertThatJson(result).inPath("organizations").isObject().containsKey(organizationId);
    }

    @Test
    void shouldKeepTitleAndOrganizationInTheSameVCardGroup() throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid",
             "organizations": {"work_1": {"@type": "Organization", "name": "Acme"}},
             "titles": {"title1": {"@type": "Title", "kind": "title", "name": "Engineer", "organizationId": "work_1"}}}
            """);

        byte[] vCard = CollectedContact.parse(input).toVCardAsBytes();
        VCard result = Ezvcard.parse(new String(vCard, StandardCharsets.UTF_8)).first();

        assertThat(result.getTitles().getFirst().getGroup())
            .isNotNull()
            .isEqualTo(result.getOrganizations().getFirst().getGroup());
    }

    @Test
    void shouldKeepLocalizedOrganizationNameInTheVCard() throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid", "language": "en",
             "organizations": {"work_1": {"@type": "Organization", "name": "Acme"}},
             "localizations": {"fr": {"organizations/work_1/name": "Acme France"}}}
            """);

        assertThat(organizationNames(CollectedContact.parse(input).toVCardAsBytes()))
            .contains("Acme France");
    }

    @Test
    void shouldResolveEscapedLocalizationPointerOfARenamedId() throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid", "language": "en",
             "organizations": {"a/b": {"@type": "Organization", "name": "Acme"}},
             "localizations": {"fr": {"organizations/a~1b/name": "Acme France"}}}
            """);

        assertThat(organizationNames(CollectedContact.parse(input).toVCardAsBytes()))
            .contains("Acme France");
    }

    // Parsed rather than matched as text: long vCard lines are folded, and hashed ids make them long.
    private static List<String> organizationNames(byte[] vCard) {
        return Ezvcard.parse(new String(vCard, StandardCharsets.UTF_8)).first()
            .getOrganizations().stream()
            .flatMap(organization -> organization.getValues().stream())
            .toList();
    }

    @Test
    void shouldRenameOrganizationReferenceOfALocalizedTitle() throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid", "language": "en",
             "organizations": {"work_1": {"@type": "Organization", "name": "Acme"}},
             "titles": {"title_1": {"@type": "Title", "kind": "title", "name": "Engineer", "organizationId": "work_1"}},
             "localizations": {"fr": {
               "titles/title_1": {"@type": "Title", "kind": "title", "name": "Ingenieur", "organizationId": "work_1"},
               "titles/other_1/organizationId": "work_1"}}}
            """);

        ObjectNode result = contact(CollectedContact.parse(input).toJson());

        assertThat(result.toString()).doesNotContain("work_1", "title_1", "other_1");
        assertThat(CollectedContact.parse(CollectedContact.parse(input).toVCardAsBytes()).uid().value())
            .isEqualTo("contact-uid");
    }

    @Test
    void shouldKeepPatchesOfOtherPropertiesUntouched() throws Exception {
        ObjectNode input = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid", "language": "en",
             "name": {"@type": "Name", "full": "Bob"},
             "localizations": {"fr": {"name/full": "Robert"}}}
            """);

        ObjectNode result = contact(CollectedContact.parse(input).toJson());

        assertThat(result.at("/localizations/fr/name~1full").asText()).isEqualTo("Robert");
    }

    @Test
    void parsingShouldNotMutateTheIncomingJSContact() throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com", "pref": 0}}}
            """);

        CollectedContact.parse(contact);

        assertThat(contact.at("/emails/e/pref").asInt()).isZero();
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

    @ParameterizedTest
    @ValueSource(strings = {"a b@c", "x?y", "x#y", "%2F..%2Fx", "../x", "a/b", "{uid}", "50%"})
    void collectedContactShouldMapAnyUidToASingleSafeResourceName(String uid) throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0", "uid": "%s",
             "emails": {"e": {"@type": "EmailAddress", "address": "alice@example.com"}}}
            """.formatted(uid));

        CollectedContact result = CollectedContact.parse(contact);
        AddressBookURL collected = new AddressBookURL(new OpenPaaSId("65f1c0de0123456789abcdef"), "collected");

        URI uri = collected.vcardUri(result.resourceName().value());
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isNull();
        String name = StringUtils.removeStart(uri.getRawPath(), collected.asUri().toASCIIString() + "/");
        assertThat(URLDecoder.decode(name, StandardCharsets.UTF_8)).doesNotContain("/", "\\");
        assertThat(CollectedContact.parse(result.toVCardAsBytes()).uid().value()).isEqualTo(uid);
    }

    @Test
    void resourceNameShouldBeTheUidWhenSafe() throws Exception {
        ObjectNode contact = contact("""
            {"@type": "Card", "version": "2.0", "uid": "contact-uid@example.com"}
            """);

        assertThat(CollectedContact.parse(contact).resourceName().value()).isEqualTo("contact-uid@example.com");
    }

    @Test
    void resourceNameShouldBeStableForUnsafeUid() throws Exception {
        String json = """
            {"@type": "Card", "version": "2.0", "uid": "a b@c"}
            """;

        assertThat(CollectedContact.parse(contact(json)).resourceName())
            .isEqualTo(CollectedContact.parse(contact(json)).resourceName());
    }

    @Test
    void parseShouldReadBackACardWithANameOnlyExtensionAndATextExtensionParameter() throws Exception {
        CollectedContact contact = CollectedContact.parse(contact("""
            {
              "@type": "Card",
              "version": "2.0",
              "name": { "foo": "bar" },
              "phones": { "p": { "@type": "Phone", "number": "+33 1 23 45 67 89" } },
              "vCardProps": [["x-test", { "x-label": "L" }, "text", "value"]]
            }
            """));

        assertThatCode(() -> CollectedContact.parse(contact.toVCardAsBytes()))
            .doesNotThrowAnyException();
        assertThat(CollectedContact.parse(contact.toVCardAsBytes()).uid()).isEqualTo(contact.uid());
    }

    private ObjectNode contact(String value) throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(value);
    }
}
