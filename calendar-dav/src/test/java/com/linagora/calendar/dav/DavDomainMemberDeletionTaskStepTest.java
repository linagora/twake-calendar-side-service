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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;

class DavDomainMemberDeletionTaskStepTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private DavDomainMemberDeletionTaskStep testee;
    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSDomainDAO domainDAO;

    @BeforeEach
    void setUp() throws SSLException {
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        domainDAO = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB());
        testee = new DavDomainMemberDeletionTaskStep(cardDavClient, domainDAO);
    }

    @Test
    void deleteUserDataShouldDeleteEveryCardOfTheUserAndKeepOtherMembers() throws Exception {
        // Given Bob has two cards in the domain member address book, written under different names, next to Alice's card
        OpenPaaSDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        MailAddress bob = new MailAddress("bob@" + domain.domain().asString());
        MailAddress alice = new MailAddress("alice@" + domain.domain().asString());
        AddressBookContact aliceContact = AddressBookContact.builder().uid("alice").displayName("Alice").mail(alice).build();
        upsert(domain, "bob-saas", AddressBookContact.builder().uid("bob-saas").displayName("Bob").mail(bob).build());
        upsert(domain, "bob-ldap", AddressBookContact.builder().uid("bob-ldap").displayName("Bob Ldap").mail(bob).build());
        upsert(domain, "alice", aliceContact);

        // When Bob's data is deleted
        testee.deleteUserData(Username.fromMailAddress(bob)).block();

        // Then none of Bob's cards remains, and Alice's card is untouched
        assertThat(findCards(domain, bob)).isEmpty();
        assertThat(findCards(domain, alice)).containsExactly(new DomainMemberCard("alice", aliceContact));
    }

    @Test
    void deleteUserDataShouldSucceedWhenTheDomainHasNoDomainMemberAddressBook() {
        // Given a domain without domain member address book
        OpenPaaSDomain domain = newDomain();

        // When the data of a user of that domain is deleted
        // Then the step succeeds
        assertThatCode(() -> testee.deleteUserData(Username.fromLocalPartWithDomain("bob", domain.domain())).block())
            .doesNotThrowAnyException();
    }

    @Test
    void deleteUserDataShouldSucceedWhenTheDomainIsUnknown() {
        // Given a user whose domain is not registered
        Username username = Username.fromLocalPartWithDomain("bob", Domain.of("unknown-" + UUID.randomUUID() + ".tld"));

        // When the data of that user is deleted
        // Then the step succeeds
        assertThatCode(() -> testee.deleteUserData(username).block())
            .doesNotThrowAnyException();
    }

    private OpenPaaSDomain newDomain() {
        return domainDAO.add(Domain.of("domain-" + UUID.randomUUID() + ".tld")).block();
    }

    private void upsert(OpenPaaSDomain domain, String vcardUid, AddressBookContact contact) {
        cardDavClient.upsertContactDomainMembers(domain.id(), vcardUid, contact.toVcardBytes()).block();
    }

    private List<DomainMemberCard> findCards(OpenPaaSDomain domain, MailAddress mail) {
        return cardDavClient.findContactsDomainMembersByEmail(domain.id(), mail).collectList().block();
    }
}
