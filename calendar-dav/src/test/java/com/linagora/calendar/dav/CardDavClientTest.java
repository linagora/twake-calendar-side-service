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

import static com.linagora.calendar.dav.SabreDavProvisioningService.DATABASE;
import static com.linagora.calendar.dav.SabreDavProvisioningService.DOMAIN;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TechnicalTokenService;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class CardDavClientTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static DavTestHelper davTestHelper;

    private CardDavClient testee;
    private MongoDBOpenPaaSDomainDAO mongoDBOpenPaaSDomainDAO;
    private OpenPaaSUser user;

    @BeforeAll
    static void setUp() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
    }

    @BeforeEach
    void setupEach() throws Exception {
        TechnicalTokenService technicalTokenService = new TechnicalTokenService.Impl("technicalTokenSecret", Duration.ofSeconds(120));
        mongoDBOpenPaaSDomainDAO = mongoDBOpenPaaSDomainDAO();
        testee = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), technicalTokenService);
        user = sabreDavExtension.newTestUser();
    }

    @Test
    void createContactShouldSucceed() {
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();

        String actual = new String(testee.exportContact(user.username(), user.id(), addressBook).block(), StandardCharsets.UTF_8);

        assertThat(actual).contains("EMAIL;TYPE=Work:john.doe@example.com");
    }

    @Test
    void createContactShouldThrowWhenInvalidAddressBook() {
        String addressBook = "invalid";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        assertThatThrownBy(() ->
            testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block()
        ).isInstanceOf(DavClientException.class);
    }

    @Test
    void exportContactShouldReturnMultipleContacts() {
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcardUid2 = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);
        String vcard2 = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe 2
            EMAIL;TYPE=Work:john.doe2@example.com
            END:VCARD
            """.formatted(vcardUid2);

        testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();
        testee.createContact(user.username(), user.id(), addressBook, vcardUid2, vcard2.getBytes(StandardCharsets.UTF_8)).block();

        String actual = new String(testee.exportContact(user.username(), user.id(), addressBook).block(), StandardCharsets.UTF_8);

        assertThat(actual).contains("EMAIL;TYPE=Work:john.doe@example.com");
        assertThat(actual).contains("EMAIL;TYPE=Work:john.doe2@example.com");
    }

    @Test
    void exportContactShouldReturnEmptyWhenNoContact() {
        String addressBook = "collected";

        Optional<byte[]> actual = testee.exportContact(user.username(), user.id(), addressBook).blockOptional();

        assertThat(actual).isEmpty();
    }

    @Test
    void exportContactShouldThrowErrorWhenAddressBookNotFound() {
        String addressBook = "notfound";

        assertThatThrownBy(() ->
            testee.exportContact(user.username(), user.id(), addressBook).block()
        ).isInstanceOf(DavClientException.class);
    }

    @Test
    void createDomainMembersAddressBookShouldNotThrowWhenCreatedFirstTime() {
        OpenPaaSDomain domain = mongoDBOpenPaaSDomainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        assertThatCode(() -> testee.createDomainMembersAddressBook(domain.id()).block())
            .doesNotThrowAnyException();
    }

    @Test
    void regularUserShouldSeeDomainMembersAddressBook() {
        OpenPaaSDomain domain = mongoDBOpenPaaSDomainDAO.retrieve(Domain.of(DOMAIN)).block();

        testee.createDomainMembersAddressBook(domain.id()).block();

        String addressBooksJson = davTestHelper.listAddressBooks(user, domain.id()).block();

        assertThatJson(addressBooksJson)
            .withOptions(IGNORING_ARRAY_ORDER)
            .inPath("_embedded.dav:addressbook")
            .isArray()
            .contains(json("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/{domain_id}/domain-members.json"
                        }
                    },
                    "dav:name": "Domain Members",
                    "carddav:description": "Address book contains all domain members",
                    "dav:acl": [
                        "{DAV:}read"
                    ],
                    "dav:share-access": 1,
                    "openpaas:subscription-type": null,
                    "type": "group",
                    "state": "",
                    "numberOfContacts": null,
                    "acl": [
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{user_id}",
                            "protected": true
                        }
                    ],
                    "dav:group": "principals/domains/{domain_id}"
                }""".replace("{domain_id}", domain.id().value())
                .replace("{user_id}", user.id().value())));
    }

    @Test
    void createDomainMembersAddressBookShouldNotThrowWhenAlreadyExists() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();

        assertThatCode(() -> testee.createDomainMembersAddressBook(domain.id())
            .block())
            .doesNotThrowAnyException();
    }

    @Test
    void upsertContactDomainMembersShouldCreateContactIfNotExists() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        testee.upsertContactDomainMembers(domain.id(), vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();

        assertThat(listContactDomainMembersAsVcard(domain))
            .containsIgnoringNewLines("""
                UID:%s
                FN:John Doe
                EMAIL;TYPE=Work:john.doe@example.com""".trim().formatted(vcardUid));
    }

    @Test
    void upsertContactDomainMembersShouldUpdateContactWhenAlreadyExists() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        String vcardUid = UUID.randomUUID().toString();

        String originalVcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        testee.upsertContactDomainMembers(domain.id(), vcardUid, originalVcard.getBytes(StandardCharsets.UTF_8)).block();

        String updatedVcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Smith
            EMAIL;TYPE=Work:john.smith@example.com
            END:VCARD
            """.formatted(vcardUid);

        testee.upsertContactDomainMembers(domain.id(), vcardUid, updatedVcard.getBytes(StandardCharsets.UTF_8)).block();

        assertThat(listContactDomainMembersAsVcard(domain))
            .containsIgnoringNewLines("""
                UID:%s
                FN:John Smith
                EMAIL;TYPE=Work:john.smith@example.com
                """.trim().formatted(vcardUid))
            .doesNotContain("FN:John Doe")
            .doesNotContain("EMAIL;TYPE=Work:john.doe@example.com");
    }

    @Test
    void upsertContactDomainMembersShouldCreateMultipleContactsWithDifferentUids() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();

        String vcardUid1 = UUID.randomUUID().toString();
        String vcard1 = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Alice Smith
            EMAIL;TYPE=Work:alice@example.com
            END:VCARD
            """.formatted(vcardUid1);

        String vcardUid2 = UUID.randomUUID().toString();
        String vcard2 = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Bob Johnson
            EMAIL;TYPE=Home:bob@example.com
            END:VCARD
            """.formatted(vcardUid2);

        testee.upsertContactDomainMembers(domain.id(), vcardUid1, vcard1.getBytes(StandardCharsets.UTF_8)).block();
        testee.upsertContactDomainMembers(domain.id(), vcardUid2, vcard2.getBytes(StandardCharsets.UTF_8)).block();

        assertThat(listContactDomainMembersAsVcard(domain))
            .containsIgnoringNewLines("""
                UID:%s
                FN:Alice Smith
                EMAIL;TYPE=Work:alice@example.com
                """.trim().formatted(vcardUid1))
            .containsIgnoringNewLines("""
                UID:%s
                FN:Bob Johnson
                EMAIL;TYPE=Home:bob@example.com
                """.trim().formatted(vcardUid2));
    }

    @Test
    void upsertContactDomainMembersShouldNotAffectOtherContacts() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        // Insert contact A
        String uidA = UUID.randomUUID().toString();
        String vcardA = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Alice
            EMAIL:alice@example.com
            END:VCARD
            """.formatted(uidA);
        testee.upsertContactDomainMembers(domain.id(), uidA, vcardA.getBytes(StandardCharsets.UTF_8)).block();

        // Insert contact B
        String uidB = UUID.randomUUID().toString();
        String vcardB = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Bob
            EMAIL:bob@example.com
            END:VCARD
            """.formatted(uidB);
        testee.upsertContactDomainMembers(domain.id(), uidB, vcardB.getBytes(StandardCharsets.UTF_8)).block();

        // Update contact A
        String updatedVcardA = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Alice Updated
            EMAIL:alice.updated@example.com
            END:VCARD
            """.formatted(uidA);
        testee.upsertContactDomainMembers(domain.id(), uidA, updatedVcardA.getBytes(StandardCharsets.UTF_8)).block();

        // Assert contact list contains updated A and unchanged B
        assertThat(listContactDomainMembersAsVcard(domain))
            .containsIgnoringNewLines("""
                UID:%s
                FN:Alice Updated
                EMAIL:alice.updated@example.com
                """.trim().formatted(uidA))
            .containsIgnoringNewLines("""
                UID:%s
                FN:Bob
                EMAIL:bob@example.com
                """.trim().formatted(uidB));
    }

    @Test
    void upsertContactDomainMembersShouldIsolateContactsBetweenDifferentDomains() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        OpenPaaSDomain anotherDomain = createNewDomainMemberAddressBook();
        testee.createDomainMembersAddressBook(anotherDomain.id()).block();

        // Insert contact into domain A
        String uidA = UUID.randomUUID().toString();
        String vcardA = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Alice
            EMAIL:alice@domain-a.com
            END:VCARD
            """.formatted(uidA);
        testee.upsertContactDomainMembers(domain.id(), uidA, vcardA.getBytes(StandardCharsets.UTF_8)).block();

        // Insert contact into domain B
        String uidB = UUID.randomUUID().toString();
        String vcardB = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:Bob
            EMAIL:bob@domain-b.com
            END:VCARD
            """.formatted(uidB);
        testee.upsertContactDomainMembers(anotherDomain.id(), uidB, vcardB.getBytes(StandardCharsets.UTF_8)).block();

        // Assert domain A contains only contact A
        String resultA = listContactDomainMembersAsVcard(domain);
        assertThat(resultA)
            .containsIgnoringNewLines("UID:%s".formatted(uidA))
            .doesNotContain("bob@domain-b.com");

        // Assert domain B contains only contact B
        String resultB = listContactDomainMembersAsVcard(anotherDomain);
        assertThat(resultB)
            .containsIgnoringNewLines("UID:%s".formatted(uidB))
            .doesNotContain("alice@domain-a.com");
    }

    @Test
    void upsertContactDomainMembersShouldThrowWhenVcardInvalid() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        String vcardUid = UUID.randomUUID().toString();

        // Missing BEGIN:VCARD
        String invalidVcard = """
            VERSION:3.0
            UID:%s
            FN:Invalid Contact
            EMAIL:invalid@example.com
            END:VCARD
            """.formatted(vcardUid);

        assertThatThrownBy(() -> testee.upsertContactDomainMembers(domain.id(), vcardUid,
            invalidVcard.getBytes(StandardCharsets.UTF_8)).block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void listContactDomainMembersShouldReturnEmptyVcardWhenNoContactsExist() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        assertThat(testee.listContactDomainMembers(domain.id()).blockOptional()).isEmpty();
    }

    @Test
    void listContactDomainMembersShouldTriggerCreateDomainMembersAddressBookWhenNotExists() {
        OpenPaaSDomain newDomain = mongoDBOpenPaaSDomainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();

        assertThatCode(() -> testee.listContactDomainMembers(newDomain.id()).block())
            .doesNotThrowAnyException();
        assertThat(testee.listContactDomainMembers(newDomain.id()).blockOptional()).isEmpty();
    }

    @Test
    void deleteContactDomainMembersShouldRemoveSpecifiedContactWhenExists() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();

        testee.createDomainMembersAddressBook(domain.id()).block();

        String uid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:John
            EMAIL:john@example.com
            END:VCARD
            """.formatted(uid);
        testee.upsertContactDomainMembers(domain.id(), uid, vcard.getBytes(StandardCharsets.UTF_8)).block();

        testee.deleteContactDomainMembers(domain.id(), uid).block();

        String result = listContactDomainMembersAsVcard(domain);
        assertThat(result).doesNotContain("UID:" + uid);
    }

    @Test
    void deleteContactDomainMembersShouldNotThrowWhenContactDoesNotExist() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        String c = UUID.randomUUID().toString();
        assertThatCode(() -> testee.deleteContactDomainMembers(domain.id(), UUID.randomUUID().toString()).block())
            .doesNotThrowAnyException();
    }

    @Test
    void deleteContactDomainMembersShouldNotAffectOtherContactsInSameDomain() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        testee.createDomainMembersAddressBook(domain.id()).block();

        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String vcard1 = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Alice
            END:VCARD
            """.formatted(uid1);
        String vcard2 = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Bob
            END:VCARD
            """.formatted(uid2);
        testee.upsertContactDomainMembers(domain.id(), uid1, vcard1.getBytes(StandardCharsets.UTF_8)).block();
        testee.upsertContactDomainMembers(domain.id(), uid2, vcard2.getBytes(StandardCharsets.UTF_8)).block();

        testee.deleteContactDomainMembers(domain.id(), uid1).block();

        String result = listContactDomainMembersAsVcard(domain);
        assertThat(result).contains("UID:" + uid2);
        assertThat(result).doesNotContain("UID:" + uid1);
    }

    @Test
    void deleteContactDomainMembersShouldNotAffectOtherDomains() {
        OpenPaaSDomain domain = createNewDomainMemberAddressBook();
        OpenPaaSDomain anotherDomain = createNewDomainMemberAddressBook();

        String sharedUid = UUID.randomUUID().toString();

        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Same UID
            END:VCARD
            """.formatted(sharedUid);
        testee.upsertContactDomainMembers(domain.id(), sharedUid, vcard.getBytes(StandardCharsets.UTF_8)).block();
        testee.upsertContactDomainMembers(anotherDomain.id(), sharedUid, vcard.getBytes(StandardCharsets.UTF_8)).block();

        testee.deleteContactDomainMembers(domain.id(), sharedUid).block();

        String resultDomainA = listContactDomainMembersAsVcard(domain);
        assertThat(resultDomainA).doesNotContain("UID:" + sharedUid);

        String resultDomainB = listContactDomainMembersAsVcard(anotherDomain);
        assertThat(resultDomainB).contains("UID:" + sharedUid);
    }

    @Test
    void deleteContactDomainMembersShouldBeNoOpWhenAddressBookNotCreated() {
        OpenPaaSDomain domain = mongoDBOpenPaaSDomainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        assertThatThrownBy(() -> testee.deleteContactDomainMembers(domain.id(), "any-uid").block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void listUserAddressBookIdsShouldReturnCreatedAddressBook() {
        String addressBookId = "testbook";
        String name = "Test Address Book";
        testee.createUserAddressBook(user.username(), user.id(), addressBookId, name).block();

        var addressBooks = testee.listUserAddressBookIds(user.username(), user.id()).collectList().block();
        assertThat(addressBooks)
            .anySatisfy(addressBook -> {
                assertThat(addressBook.value()).isEqualTo(addressBookId);
                assertThat(addressBook.type()).isEqualTo(CardDavClient.AddressBookType.USER);
            });
    }

    @Test
    void listUserAddressBookIdsShouldReturnDefaultAddressBooksWhenNoCustomCreated() {
        var addressBooks = testee.listUserAddressBookIds(user.username(), user.id()).collectList().block();
        assertThat(addressBooks)
            .containsExactlyInAnyOrder(new CardDavClient.AddressBook("collected", CardDavClient.AddressBookType.SYSTEM),
                new CardDavClient.AddressBook("contacts", CardDavClient.AddressBookType.SYSTEM));
    }

    @Test
    void deleteUserAddressBookShouldRemoveAddressBook() {
        String addressBookId = "todelete";
        String name = "To Delete";
        testee.createUserAddressBook(user.username(), user.id(), addressBookId, name).block();
        testee.deleteUserAddressBook(user.username(), user.id(), addressBookId).block();

        assertThat(testee.listUserAddressBookIds(user.username(), user.id()).collectList().block())
            .extracting(CardDavClient.AddressBook::value)
            .doesNotContain(addressBookId);
    }

    @Test
    void deleteUserAddressBookShouldThrowOnError() {
        assertThatThrownBy(() -> testee.deleteUserAddressBook(user.username(), user.id(), "doesnotexist").block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void deleteContactShouldRemoveSpecifiedContactWhenExists() {
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcardUid2 = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:John
            EMAIL:john@example.com
            END:VCARD
            """.formatted(vcardUid);
        String vcard2 = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Jane
            EMAIL:jane@example.com
            END:VCARD
            """.formatted(vcardUid2);
        testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();
        testee.createContact(user.username(), user.id(), addressBook, vcardUid2, vcard2.getBytes(StandardCharsets.UTF_8)).block();
        testee.deleteContact(user.username(), user.id(), addressBook, vcardUid).block();

        String actual = new String(testee.exportContact(user.username(), user.id(), addressBook).block(), StandardCharsets.UTF_8);
        assertThat(actual).doesNotContain("UID:" + vcardUid);
    }

    @Test
    void deleteContactShouldNotAffectOtherContacts() {
        String addressBook = "collected";
        String vcardUid1 = UUID.randomUUID().toString();
        String vcard1 = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Alice
            EMAIL:alice@example.com
            END:VCARD
            """.formatted(vcardUid1);
        String vcardUid2 = UUID.randomUUID().toString();
        String vcard2 = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Bob
            EMAIL:bob@example.com
            END:VCARD
            """.formatted(vcardUid2);
        testee.createContact(user.username(), user.id(), addressBook, vcardUid1, vcard1.getBytes(StandardCharsets.UTF_8)).block();
        testee.createContact(user.username(), user.id(), addressBook, vcardUid2, vcard2.getBytes(StandardCharsets.UTF_8)).block();
        testee.deleteContact(user.username(), user.id(), addressBook, vcardUid1).block();
        String actual = new String(testee.exportContact(user.username(), user.id(), addressBook).block(), StandardCharsets.UTF_8);

        assertThat(actual).contains("UID:" + vcardUid2);
    }

    @Test
    void deleteContactShouldThrowWhenContactDoesNotExist() {
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> testee.deleteContact(user.username(), user.id(), addressBook, vcardUid).block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void deleteContactShouldThrowWhenAddressBookDoesNotExist() {
        String addressBook = "notfound";
        String vcardUid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> testee.deleteContact(user.username(), user.id(), addressBook, vcardUid).block())
            .isInstanceOf(DavClientException.class);
    }

    private OpenPaaSDomain createNewDomainMemberAddressBook() {
        OpenPaaSDomain newDomain = mongoDBOpenPaaSDomainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        testee.createDomainMembersAddressBook(newDomain.id()).block();
        return newDomain;
    }

    private String listContactDomainMembersAsVcard(OpenPaaSDomain domain) {
        return testee.listContactDomainMembers(domain.id())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

    private static MongoDBOpenPaaSDomainDAO mongoDBOpenPaaSDomainDAO() {
        MongoClient mongoClient = MongoClients.create(sabreDavExtension.dockerSabreDavSetup().getMongoDbIpAddress().toString());
        MongoDatabase database = mongoClient.getDatabase(DATABASE);
        return new MongoDBOpenPaaSDomainDAO(database);
    }
}

