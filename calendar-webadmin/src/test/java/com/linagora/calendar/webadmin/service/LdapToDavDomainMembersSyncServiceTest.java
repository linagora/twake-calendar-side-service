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
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.TechnicalTokenService;
import com.linagora.calendar.storage.ldap.LdapDomainMember;
import com.linagora.calendar.storage.ldap.LdapDomainMemberProvider;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.UpdateResult;
import com.mongodb.reactivestreams.client.MongoClients;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.ContactUpdateContext;

import reactor.core.publisher.Flux;

public class LdapToDavDomainMembersSyncServiceTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);


    private LdapToDavDomainMembersSyncService testee;
    private LdapDomainMemberProvider ldapDomainMemberProvider;
    private CardDavClient cardDavClient;
    private static MongoDBOpenPaaSDomainDAO mongoDBOpenPaaSDomainDAO;
    private OpenPaaSDomain openPaaSDomain;

    @BeforeAll
    static void setUp() {
        mongoDBOpenPaaSDomainDAO = Optional.of(MongoClients.create(sabreDavExtension.dockerSabreDavSetup().getMongoDbIpAddress().toString()))
            .map(mongoClient -> mongoClient.getDatabase(DATABASE))
            .map(MongoDBOpenPaaSDomainDAO::new)
            .get();
    }

    @BeforeEach
    void setup() throws SSLException {
        TechnicalTokenService technicalTokenService = new TechnicalTokenService.Impl("technicalTokenSecret", Duration.ofSeconds(120));
        CardDavClient cardDavClientActual = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), technicalTokenService);
        cardDavClient = spy(cardDavClientActual);

        ldapDomainMemberProvider = mock(LdapDomainMemberProvider.class);
        testee = new LdapToDavDomainMembersSyncService(ldapDomainMemberProvider, cardDavClient, mongoDBOpenPaaSDomainDAO);
        openPaaSDomain = createNewDomainMemberAddressBook();
    }

    private OpenPaaSDomain createNewDomainMemberAddressBook() {
        OpenPaaSDomain newDomain = mongoDBOpenPaaSDomainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        cardDavClient.createDomainMembersAddressBook(newDomain.id()).block();
        return newDomain;
    }

    @Test
    void shouldCreateDavContactWhenItDoesNotExist() {
        LdapDomainMember ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        when(ldapDomainMemberProvider.domainMembers(openPaaSDomain.domain()))
            .thenReturn(Flux.just(ldap));

        UpdateResult updateResult = testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();

        assertThat(updateResult.addedCount()).isEqualTo(1);
        assertThat(listContactDomainMembersAsVcard(openPaaSDomain))
            .containsIgnoringNewLines("""
                UID:uid123
                FN:Nguyen Van A
                N:Nguyen;Van A;;;
                EMAIL:user1@example.com
                TEL;TYPE=work:123""".trim());
    }


    @Test
    void shouldNotUpdateDavWhenLdapDataIsUnchanged() {
        LdapDomainMember ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        preloadDavData(ldap);

        UpdateResult updateResult = testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();

        // Assert no changes in the second sync
        assertSoftly(softly -> {
            softly.assertThat(updateResult.addedCount()).isEqualTo(0);
            softly.assertThat(updateResult.updatedCount()).isEqualTo(0);
            softly.assertThat(updateResult.hasFailures()).isFalse();
        });

        verify(cardDavClient, never()).upsertContactDomainMembers(any(), any(), any());
        verify(cardDavClient, never()).deleteContactDomainMembers(any(), any());
    }

    @Test
    void shouldUpdateDavWhenLdapDataIsChanged() {
        LdapDomainMember initial = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        preloadDavData(initial);

        // Change the telephone number
        LdapDomainMember updated = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "456");
        when(ldapDomainMemberProvider.domainMembers(openPaaSDomain.domain()))
            .thenReturn(Flux.just(updated));
        UpdateResult result = testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();

        assertThat(result.updatedCount()).isEqualTo(1);

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain))
            .containsIgnoringNewLines("""
                EMAIL:user1@example.com
                TEL;TYPE=work:456""".trim())
            .doesNotContain("TEL;TYPE=work:123");
    }

    @Test
    void shouldDeleteDavContactWhenLdapNoLongerContainsIt() {
        LdapDomainMember member = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        preloadDavData(member);

        when(ldapDomainMemberProvider.domainMembers(openPaaSDomain.domain()))
            .thenReturn(Flux.empty());
        UpdateResult result = testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();

        assertThat(result.deletedCount()).isEqualTo(1);
        verify(cardDavClient).deleteContactDomainMembers(eq(openPaaSDomain.id()), any());

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain))
            .doesNotContain("user1@example.com");
    }

    @Test
    void shouldHandleAddUpdateDeleteAndNoOpOnSecondSync() {
        // Step 1: Preload existing DAV contacts
        LdapDomainMember existing1 = ldapMember("uid1", "a@example.com", "Nguyen", "Van A", "Nguyen Van A", "111");
        LdapDomainMember existing2 = ldapMember("uid2", "b@example.com", "Le", "Thi B", "Le Thi B", "222");
        LdapDomainMember existing3 = ldapMember("uid99", "toremove@example.com", "Le", "Thi B", "Le Thi B", "999");
        preloadDavData(existing1, existing2, existing3);

        // Step 2: Prepare LDAP state with 2 new, 2 updated, and 1 retained (existing1, existing2 modified)
        LdapDomainMember updated1 = ldapMember("uid1", "a@example.com", "Nguyen", "Van A", "Nguyen Van A", "999");
        LdapDomainMember updated2 = ldapMember("uid2", "b@example.com", "Le", "Thi B", "Le Thi B", "888");
        LdapDomainMember added1 = ldapMember("uid3", "c@example.com", "Tran", "C", "Tran C", "333");
        LdapDomainMember added2 = ldapMember("uid4", "d@example.com", "Pham", "D", "Pham D", "444");

        when(ldapDomainMemberProvider.domainMembers(openPaaSDomain.domain()))
            .thenReturn(Flux.just(updated1, updated2, added1, added2));

        // Step 3: First sync — expect 2 add, 2 update, 1 delete
        UpdateResult firstResult = testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();
        assertSoftly(softly -> {
            softly.assertThat(firstResult.addedCount()).isEqualTo(2);
            softly.assertThat(firstResult.updatedCount()).isEqualTo(2);
            softly.assertThat(firstResult.deletedCount()).isEqualTo(1);
            softly.assertThat(firstResult.hasFailures()).isFalse();
        });

        // Step 4: Second sync — no changes expected
        clearInvocations(cardDavClient);
        UpdateResult secondResult = testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();

        assertSoftly(softly -> {
            softly.assertThat(secondResult.addedCount()).isEqualTo(0);
            softly.assertThat(secondResult.updatedCount()).isEqualTo(0);
            softly.assertThat(secondResult.deletedCount()).isEqualTo(0);
            softly.assertThat(secondResult.hasFailures()).isFalse();
        });

        verify(cardDavClient, never()).upsertContactDomainMembers(any(), any(), any());
        verify(cardDavClient, never()).deleteContactDomainMembers(any(), any());

        List<AddressBookContact> latestDomainMembersContact = cardDavClient.listContactDomainMembers(openPaaSDomain.id())
            .map(AddressBookContact::parse)
            .block();

        assertThat(latestDomainMembersContact)
            .containsExactlyInAnyOrder(
                AddressBookContact.builder()
                    .uid("uid1")
                    .familyName("Nguyen")
                    .givenName("Van A")
                    .displayName("Nguyen Van A")
                    .mail("a@example.com")
                    .telephoneNumber("999")
                    .build(),
                AddressBookContact.builder()
                    .uid("uid2")
                    .familyName("Le")
                    .givenName("Thi B")
                    .displayName("Le Thi B")
                    .mail("b@example.com")
                    .telephoneNumber("888")
                    .build(),
                AddressBookContact.builder()
                    .uid("uid3")
                    .familyName("Tran")
                    .givenName("C")
                    .displayName("Tran C")
                    .mail("c@example.com")
                    .telephoneNumber("333")
                    .build(),
                AddressBookContact.builder()
                    .uid("uid4")
                    .familyName("Pham")
                    .givenName("D")
                    .displayName("Pham D")
                    .mail("d@example.com")
                    .telephoneNumber("444")
                    .build());
    }

    private LdapDomainMember ldapMember(String uid, String mail, String sn, String givenName, String displayName, String tel) {
        return LdapDomainMember.builder()
            .uid(uid)
            .cn(displayName)
            .sn(sn)
            .givenName(givenName)
            .mail(Throwing.supplier(() -> new MailAddress(mail)).get())
            .telephoneNumber(tel)
            .displayName(displayName)
            .build();
    }

    private String listContactDomainMembersAsVcard(OpenPaaSDomain domain) {
        return cardDavClient.listContactDomainMembers(domain.id())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

    private void preloadDavData(LdapDomainMember... members) {
        when(ldapDomainMemberProvider.domainMembers(openPaaSDomain.domain()))
            .thenReturn(Flux.fromArray(members));
        testee.syncDomainMembers(openPaaSDomain, new ContactUpdateContext()).block();
        clearInvocations(cardDavClient); // Reset interaction tracking
    }


}
