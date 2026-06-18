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

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.dav.SabreDavProvisioningService.DATABASE;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.UpdateResult;
import com.mongodb.reactivestreams.client.MongoClients;

public class DavDomainMemberUpdateApplierTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private CardDavClient cardDavClient;
    private OpenPaaSDomain openPaaSDomain;
    private DavDomainMemberUpdateApplier testee;
    private static MongoDBOpenPaaSDomainDAO mongoDBOpenPaaSDomainDAO;

    @BeforeAll
    static void setUp() {
        mongoDBOpenPaaSDomainDAO = Optional.of(MongoClients.create(sabreDavExtension.dockerSabreDavSetup().getMongoDbIpAddress().toString()))
            .map(mongoClient -> mongoClient.getDatabase(DATABASE))
            .map(MongoDBOpenPaaSDomainDAO::new)
            .get();
    }

    @BeforeEach
    void setupEach() throws Exception {
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        openPaaSDomain = createNewDomainMemberAddressBook();
        testee = new DavDomainMemberUpdateApplier.Default(cardDavClient, openPaaSDomain.id());
    }

    private OpenPaaSDomain createNewDomainMemberAddressBook() {
        OpenPaaSDomain newDomain = mongoDBOpenPaaSDomainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        cardDavClient.createDomainMembersAddressBook(newDomain.id()).block();
        return newDomain;
    }

    @Test
    void shouldHandleAddNewMembers() {
        AddressBookContact needToAdd = AddressBookContact.builder()
            .uid("uid-99")
            .familyName("Smith")
            .givenName("Anna")
            .mail("anna@example.com")
            .build();

        DomainMemberUpdate domainMemberUpdate = new DomainMemberUpdate(Set.of(needToAdd), Set.of(), Set.of());
        UpdateResult updateResult = testee.apply(domainMemberUpdate).block();

        assertSoftly(softly -> {
            softly.assertThat(updateResult.addedCount()).isEqualTo(1);
            softly.assertThat(updateResult.addFailureCount()).isEqualTo(0);
            softly.assertThat(updateResult.addFailureContacts()).hasSize(0);
        });

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain))
            .contains("UID:uid-99", "FN:Anna Smith", "N:Smith;Anna;;;", "EMAIL:anna@example.com");
    }

    @Test
    void shouldHandleDeleteMembers() {
        String vcardUid = UUID.randomUUID().toString();

        AddressBookContact contact = AddressBookContact.builder()
            .uid(vcardUid)
            .familyName("Doe")
            .givenName("John")
            .mail("john.doe@example.com")
            .build();

        insertContact(openPaaSDomain, contact);

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain))
            .contains("john.doe@example.com");

        DomainMemberUpdate deleteUpdate = new DomainMemberUpdate(Set.of(), Set.of(card(contact)), Set.of());
        UpdateResult deleteResult = testee.apply(deleteUpdate).block();

        assertSoftly(softly -> {
            softly.assertThat(deleteResult.deletedCount()).isEqualTo(1);
            softly.assertThat(deleteResult.deleteFailureCount()).isEqualTo(0);
            softly.assertThat(deleteResult.deleteFailureContacts()).isEmpty();
        });

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain)).doesNotContain("john.doe@example.com");
    }

    @Test
    void shouldHandleUpdateMembers() {
        String vcardUid = UUID.randomUUID().toString();
        AddressBookContact contact = AddressBookContact.builder()
            .uid(vcardUid)
            .familyName("Doe")
            .givenName("Jane")
            .displayName("Jane Doe")
            .mail("jane.doe@example.com")
            .build();

        insertContact(openPaaSDomain, contact);

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain))
            .contains("jane.doe@example.com");

        AddressBookContact updatedContact = AddressBookContact.builder()
            .uid(vcardUid)
            .familyName("Doe")
            .givenName("Janet")
            .displayName("Naruto Uzumaki")
            .mail("jane.doe@example.com")
            .build();

        DomainMemberUpdate update = new DomainMemberUpdate(Set.of(), Set.of(), Set.of(card(updatedContact)));
        UpdateResult updateResult = testee.apply(update).block();

        assertSoftly(softly -> {
            softly.assertThat(updateResult.updatedCount()).isEqualTo(1);
            softly.assertThat(updateResult.updateFailureCount()).isEqualTo(0);
            softly.assertThat(updateResult.updateFailureContacts()).isEmpty();
        });

        String updatedVcard = listContactDomainMembersAsVcard(openPaaSDomain);
        assertThat(updatedVcard)
            .containsIgnoringNewLines("""
                FN:Naruto Uzumaki
                N:Doe;Janet;;;
                EMAIL:jane.doe@example.com""".trim())
            .doesNotContain("FN:Jane Doe");
    }

    @Test
    void shouldHandlePartialAddFailure() {
        AddressBookContact validContact1 = AddressBookContact.builder()
            .uid("uid-1")
            .familyName("Nguyen")
            .givenName("An")
            .mail("an@example.com")
            .build();

        AddressBookContact validContact2 = AddressBookContact.builder()
            .uid("uid-2")
            .familyName("Tran")
            .givenName("Binh")
            .mail("binh@example.com")
            .build();

        AddressBookContact invalidContact = AddressBookContact.builder()
            .uid("uid-3")
            .build();

        DomainMemberUpdate update = new DomainMemberUpdate(Set.of(validContact1, validContact2, invalidContact), Set.of(), Set.of());

        UpdateResult updateResult = testee.apply(update).block();
        assertSoftly(softly -> {
            softly.assertThat(updateResult.addedCount()).isEqualTo(2);
            softly.assertThat(updateResult.addFailureCount()).isEqualTo(1);
            softly.assertThat(updateResult.addFailureContacts()).containsExactly(invalidContact);
        });

        String vcard = listContactDomainMembersAsVcard(openPaaSDomain);
        assertThat(vcard)
            .contains("EMAIL:an@example.com")
            .contains("EMAIL:binh@example.com")
            .doesNotContain("uid-3");
    }

    @Test
    void shouldDeleteMultipleContactsSuccessfully() {
        AddressBookContact contact1 = AddressBookContact.builder()
            .uid("uid-del-1")
            .givenName("Huy")
            .mail("huy@example.com")
            .build();
        insertContact(openPaaSDomain, contact1);

        AddressBookContact contact2 = AddressBookContact.builder()
            .uid("uid-del-2")
            .givenName("Linh")
            .mail("linh@example.com")
            .build();
        insertContact(openPaaSDomain, contact2);

        DomainMemberUpdate update = new DomainMemberUpdate(Set.of(), Set.of(card(contact1), card(contact2)), Set.of());
        UpdateResult updateResult = testee.apply(update).block();

        assertSoftly(softly -> {
            softly.assertThat(updateResult.deletedCount()).isEqualTo(2);
            softly.assertThat(updateResult.deleteFailureCount()).isEqualTo(0);
            softly.assertThat(updateResult.deleteFailureContacts()).isEmpty();
        });

        String remainingVcard = listContactDomainMembersAsVcard(openPaaSDomain);
        assertThat(remainingVcard)
            .doesNotContain("huy@example.com")
            .doesNotContain("linh@example.com");
    }

    @Test
    void shouldHandlePartialUpdateFailure() {
        AddressBookContact original1 = AddressBookContact.builder()
            .uid("uid-upd-1")
            .givenName("Lan")
            .mail("lan@example.com")
            .build();

        AddressBookContact original2 = AddressBookContact.builder()
            .uid("uid-upd-2")
            .givenName("Tung")
            .mail("tung@example.com")
            .build();

        AddressBookContact original3 = AddressBookContact.builder()
            .uid("uid-upd-3")
            .givenName("Nam")
            .mail("nam@example.com")
            .build();

        insertContact(openPaaSDomain, original1);
        insertContact(openPaaSDomain, original2);
        insertContact(openPaaSDomain, original3);

        AddressBookContact updated1 = AddressBookContact.builder()
            .uid("uid-upd-1")
            .givenName("Lan Updated")
            .mail("lan@example.com")
            .build();

        AddressBookContact updated2 = AddressBookContact.builder()
            .uid("uid-upd-2")
            .givenName("Tung Updated")
            .mail("tung@example.com")
            .build();

        AddressBookContact invalidUpdate = AddressBookContact.builder()
            .uid("uid-upd-3")
            .build();

        DomainMemberUpdate update = new DomainMemberUpdate(Set.of(), Set.of(), Set.of(card(updated1), card(updated2), card(invalidUpdate)));
        UpdateResult result = testee.apply(update).block();

        assertSoftly(softly -> {
            softly.assertThat(result.updatedCount()).isEqualTo(2);
            softly.assertThat(result.updateFailureCount()).isEqualTo(1);
            softly.assertThat(result.updateFailureContacts()).containsExactly(invalidUpdate);
        });

        String vcard = listContactDomainMembersAsVcard(openPaaSDomain);
        assertThat(vcard)
            .contains("N:;Lan Updated;")
            .contains("N:;Tung Updated;")
            .contains("N:;Nam;")
            .doesNotContain("N:;Lan;")
            .doesNotContain("N:;Tung;");
    }

    @Test
    void shouldHandleMixedAddUpdateDeleteWithPartialFailures() {
        AddressBookContact toBeDeleted1 = AddressBookContact.builder()
            .uid("uid-del-1")
            .givenName("Del1")
            .mail("del1@example.com")
            .build();

        AddressBookContact toBeDeleted2 = AddressBookContact.builder()
            .uid("uid-del-2")
            .givenName("Del2")
            .mail("del2@example.com")
            .build();

        AddressBookContact toBeUpdatedSuccess = AddressBookContact.builder()
            .uid("uid-upd-1")
            .givenName("UpdSuccess")
            .mail("upd1@example.com")
            .build();

        AddressBookContact toBeUpdatedFail = AddressBookContact.builder()
            .uid("uid-upd-2")
            .givenName("UpdFail")
            .mail("upd2@example.com")
            .build();

        List.of(toBeDeleted1, toBeDeleted2, toBeUpdatedSuccess, toBeUpdatedFail)
            .forEach(addressBookContact ->  insertContact(openPaaSDomain, addressBookContact));

        AddressBookContact addSuccess = AddressBookContact.builder()
            .uid("uid-add-1")
            .givenName("Add1")
            .mail("add1@example.com")
            .build();

        AddressBookContact addFail = AddressBookContact.builder()
            .uid("uid-add-2")
            .build();

        AddressBookContact updatedSuccess = AddressBookContact.builder()
            .uid("uid-upd-1")
            .givenName("Updated Success")
            .mail("upd1@example.com")
            .build();

        AddressBookContact updatedFail = AddressBookContact.builder()
            .uid("uid-upd-2")
            .build();

        DomainMemberUpdate update = new DomainMemberUpdate(
            Set.of(addSuccess, addFail),
            Set.of(card(toBeDeleted1), card(toBeDeleted2)),
            Set.of(card(updatedSuccess), card(updatedFail)));

        UpdateResult result = testee.apply(update).block();

        assertSoftly(softly -> {
            softly.assertThat(result.addedCount()).isEqualTo(1);
            softly.assertThat(result.addFailureCount()).isEqualTo(1);
            softly.assertThat(result.addFailureContacts()).containsExactly(addFail);

            softly.assertThat(result.updatedCount()).isEqualTo(1);
            softly.assertThat(result.updateFailureCount()).isEqualTo(1);
            softly.assertThat(result.updateFailureContacts()).containsExactly(updatedFail);

            softly.assertThat(result.deletedCount()).isEqualTo(2);
            softly.assertThat(result.deleteFailureCount()).isEqualTo(0);
            softly.assertThat(result.deleteFailureContacts()).isEmpty();
        });

        String vcard = listContactDomainMembersAsVcard(openPaaSDomain);
        assertThat(vcard)
            .contains("Add1")
            .contains("Updated Success")
            .doesNotContain("del1@example.com", "del2@example.com")
            .doesNotContain("invalid@@email.com")
            .doesNotContain("uid-add-2");
    }

    @Test
    void shouldDeleteResourceWhoseNameDiffersFromVcardUid() {
        // Reproduce a contact created by another tool: the resource name is NOT the vCard UID.
        cardDavClient.upsertContactDomainMembers(openPaaSDomain.id(), "arbitrary-resource-name",
            ("""
                BEGIN:VCARD
                VERSION:4.0
                UID:internal-uid-not-matching
                FN:Ghost Contact
                EMAIL:ghost@example.com
                END:VCARD
                """).getBytes(StandardCharsets.UTF_8)).block();

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain)).contains("ghost@example.com");

        // Report exposes the real resource name; deleting by it actually removes the contact.
        DomainMemberCard ghost = cardDavClient.reportContactDomainMembers(openPaaSDomain.id())
            .filter(card -> card.contact().mail().map(m -> m.asString().equals("ghost@example.com")).orElse(false))
            .blockFirst();
        assertThat(ghost.resourceName()).isEqualTo("arbitrary-resource-name");

        DomainMemberUpdate update = new DomainMemberUpdate(Set.of(), Set.of(ghost), Set.of());
        UpdateResult result = testee.apply(update).block();

        assertSoftly(softly -> {
            softly.assertThat(result.deletedCount()).isEqualTo(1);
            softly.assertThat(result.deleteFailureCount()).isEqualTo(0);
        });
        assertThat(listContactDomainMembersAsVcard(openPaaSDomain)).doesNotContain("ghost@example.com");
    }

    @Test
    void shouldDeleteStrictlyIdenticalDuplicateResources() {
        // Two resources, different names, identical vCard content (same UID) -> both must be removable.
        byte[] vcard = ("""
            BEGIN:VCARD
            VERSION:4.0
            UID:shared-uid
            FN:Dup Contact
            EMAIL:dup@example.com
            END:VCARD
            """).getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContactDomainMembers(openPaaSDomain.id(), "dup-res-1", vcard).block();
        cardDavClient.upsertContactDomainMembers(openPaaSDomain.id(), "dup-res-2", vcard).block();

        Set<DomainMemberCard> duplicates = cardDavClient.reportContactDomainMembers(openPaaSDomain.id())
            .collect(java.util.stream.Collectors.toSet())
            .block();
        assertThat(duplicates.size()).isEqualTo(2);

        UpdateResult result = testee.apply(new DomainMemberUpdate(Set.of(), duplicates, Set.of())).block();

        assertSoftly(softly -> {
            softly.assertThat(result.deletedCount()).isEqualTo(2);
            softly.assertThat(result.deleteFailureCount()).isEqualTo(0);
        });
        assertThat(listContactDomainMembersAsVcard(openPaaSDomain)).doesNotContain("dup@example.com");
    }

    private String listContactDomainMembersAsVcard(OpenPaaSDomain domain) {
        return cardDavClient.listContactDomainMembers(domain.id())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

    private void insertContact(OpenPaaSDomain domain, AddressBookContact contact) {
        cardDavClient.upsertContactDomainMembers(domain.id(), contact.vcardUid(), contact.toVcardBytes()).block();
    }

    private static DomainMemberCard card(AddressBookContact contact) {
        return new DomainMemberCard(contact.vcardUid(), contact);
    }
}
