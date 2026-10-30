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

package com.linagora.calendar.saas;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableListMultimap;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.MemoryDomainSettingsDAO;
import com.linagora.calendar.storage.MemoryUserConfigurationDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.UserContactResolver;
import com.linagora.calendar.storage.UserContactResolver.UserContact;
import com.linagora.calendar.storage.UserNameResolver.UserNames;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CalendarSettingUpdaterDomainMemberCardTest {

    private static class MutableUserContactResolver implements UserContactResolver {
        private final Map<Username, UserContact> contacts = new ConcurrentHashMap<>();
        private Optional<RuntimeException> failure = Optional.empty();

        void register(Username username, UserContact contact) {
            contacts.put(username, contact);
        }

        void fail(RuntimeException exception) {
            failure = Optional.of(exception);
        }

        @Override
        public Mono<Optional<UserContact>> resolve(Username username) {
            return Mono.defer(() -> failure.<Mono<Optional<UserContact>>>map(Mono::error)
                .orElseGet(() -> Mono.just(Optional.ofNullable(contacts.get(username)))));
        }
    }

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private final SimpleSessionProvider sessionProvider = new SimpleSessionProvider(new RandomMailboxSessionIdGenerator());

    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private UserConfigurationDAO userConfigurationDAO;
    private DomainSettingsResolver domainSettingsResolver;
    private MutableUserContactResolver userContactResolver;
    private OpenPaaSDomain testDomain;
    private Username alice;

    @BeforeEach
    void setupEach() throws Exception {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        userConfigurationDAO = new MemoryUserConfigurationDAO(userDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        domainSettingsResolver = new DomainSettingsResolver(
            new MemoryDomainSettingsDAO(), Set.of(), Set.of(), new MapConfiguration(Map.of()));
        userContactResolver = new MutableUserContactResolver();
        testDomain = domainDAO.add(Domain.of("test-domain-" + UUID.randomUUID() + ".tld")).block();
        alice = Username.of("alice@" + testDomain.domain().asString());
    }

    @Test
    void shouldRewriteCardWhenRenamedInDirectory() {
        // Given a registered user whose domain member card holds the directory details, under the old names
        userContactResolver.register(alice, aliceContact().build());
        userDAO.add(alice, "Alice", "Martin").block();
        updateSettings(testee(cardDavClient), 1);
        assertThat(findDomainMemberCards())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("contact.uid")
            .containsExactly(aliceCard("Martin", "Alice Martin", "+33600000000"));

        // When the user is renamed in the directory and a settings message of that user is received
        userContactResolver.register(alice, aliceContact()
            .names(Optional.of(new UserNames("Alice", "Dupont")))
            .familyName(Optional.of("Dupont"))
            .displayName(Optional.of("Alice Dupont"))
            .build());
        updateSettings(testee(cardDavClient), 2);

        // Then the single card of the user, keeping its name, carries the new names along with the other directory details
        assertThat(findDomainMemberCards())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("contact.uid")
            .containsExactly(aliceCard("Dupont", "Alice Dupont", "+33600000000"));
    }

    @Test
    void shouldRewriteCardWhenOtherDirectoryDetailsChanged() {
        // Given a registered user whose domain member card holds the directory details, with the old telephone number
        userContactResolver.register(alice, aliceContact().build());
        userDAO.add(alice, "Alice", "Martin").block();
        updateSettings(testee(cardDavClient), 1);
        assertThat(findDomainMemberCards())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("contact.uid")
            .containsExactly(aliceCard("Martin", "Alice Martin", "+33600000000"));

        // When only the telephone number of the user changes in the directory and a settings message of that user is received
        userContactResolver.register(alice, aliceContact().telephoneNumber(Optional.of("+33611111111")).build());
        updateSettings(testee(cardDavClient), 2);

        // Then the single card of the user, keeping its name, carries the new telephone number along with the unchanged directory details
        assertThat(findDomainMemberCards())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("contact.uid")
            .containsExactly(aliceCard("Martin", "Alice Martin", "+33611111111"));
    }

    @Test
    void shouldAddCardWhenRegisteredUserHasNone() {
        // Given a registered user known to the directory, without domain member card
        userContactResolver.register(alice, aliceContact().build());
        userDAO.add(alice, "Alice", "Martin").block();

        // When a settings message of that user is received
        updateSettings(testee(cardDavClient), 1);

        // Then the domain member card of the user is added from the directory details
        assertThat(findDomainMemberCards())
            .singleElement()
            .satisfies(card -> assertThat(card.contact().displayName()).contains("Alice Martin"));
    }

    @Test
    void shouldNotRewriteCardWhenUnchanged() {
        // Given a domain member card holding the directory details, plus a note the side service never writes
        userContactResolver.register(alice, aliceContact().build());
        userDAO.add(alice, "Alice", "Martin").block();
        updateSettings(testee(cardDavClient), 1);
        DomainMemberCard stored = findDomainMemberCards().getFirst();
        String cardWithNote = stored.contact().toVcardString().replace("END:VCARD", "NOTE:written before\r\nEND:VCARD");
        cardDavClient.upsertContactDomainMembers(testDomain.id(), stored.vcardUid(), cardWithNote.getBytes(StandardCharsets.UTF_8)).block();

        // When a settings message of that user is received again, the directory being unchanged
        updateSettings(testee(cardDavClient), 2);

        // Then the card is not written again, the note being still there
        String storedCard = cardDavClient.retrieveContactDomainMembers(testDomain.id(), stored.vcardUid())
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .block();
        assertThat(storedCard).contains("NOTE:written before");
    }

    @Test
    void shouldKeepCardWhenDirectoryFails() {
        // Given a domain member card holding the directory details, and a directory now failing
        userContactResolver.register(alice, aliceContact().build());
        userDAO.add(alice, "Alice", "Martin").block();
        updateSettings(testee(cardDavClient), 1);
        userContactResolver.fail(new RuntimeException("LDAP is down"));

        // When a settings message of that user is received
        updateSettings(testee(cardDavClient), 2);

        // Then the card keeps the directory details
        assertThat(findDomainMemberCards())
            .singleElement()
            .satisfies(card -> assertThat(card.contact().displayName()).contains("Alice Martin"));
    }

    @Test
    void shouldFailWithoutPersistingSettingsWhenCardDavFails() throws Exception {
        // Given a registered user known to the directory, and CardDav failing to look up the domain member cards
        userContactResolver.register(alice, aliceContact().build());
        userDAO.add(alice, "Alice", "Martin").block();
        CardDavClient failingCardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING) {
            @Override
            public Flux<DomainMemberCard> findContactsDomainMembersByEmail(OpenPaaSId domainId, MailAddress mail) {
                return Flux.error(new RuntimeException("Sabre is down"));
            }
        };

        // When a settings message of that user is received
        // Then the handling fails, so that the message goes to the dead letter queue
        assertThatThrownBy(() -> updateSettings(testee(failingCardDavClient), 1))
            .hasMessageContaining("Sabre is down");

        // And no setting is persisted
        assertThat(userConfigurationDAO.retrieveConfiguration(sessionProvider.createSession(alice)).collectList().block())
            .isEmpty();
    }

    private CalendarSettingUpdater testee(CardDavClient cardDavClient) {
        SaaSUserProvisioner userProvisioner = new SaaSUserProvisioner(userDAO, domainDAO, cardDavClient, domainSettingsResolver, userContactResolver);
        return new CalendarSettingUpdater(userConfigurationDAO, userDAO, userProvisioner, sessionProvider);
    }

    private void updateSettings(CalendarSettingUpdater testee, long version) {
        testee.updateSettings(new TWPCommonSettingsMessage("source", "nick", "req-1",
            System.currentTimeMillis(), version,
            new TWPCommonSettingsMessage.Payload(alice.asString(), Optional.of("en"), Optional.empty()))).block();
    }

    // The card the directory details of alice are expected to be written to, under the name derived from its mail.
    private DomainMemberCard aliceCard(String familyName, String displayName, String telephoneNumber) {
        String vcardUid = AddressBookContact.computeUid(Optional.empty(), Optional.of(asMailAddress(alice))).orElseThrow();
        return new DomainMemberCard(vcardUid, AddressBookContact.builder()
            .mail(asMailAddress(alice))
            .givenName("Alice")
            .familyName(familyName)
            .displayName(displayName)
            .telephoneNumber(telephoneNumber)
            .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"))
            .build());
    }

        private UserContact.Builder aliceContact() {
        return UserContact.builder()
            .names(Optional.of(new UserNames("Alice", "Martin")))
            .givenName(Optional.of("Alice"))
            .familyName(Optional.of("Martin"))
            .displayName(Optional.of("Alice Martin"))
            .telephoneNumber(Optional.of("+33600000000"))
            .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"));
    }

    private List<DomainMemberCard> findDomainMemberCards() {
        return cardDavClient.findContactsDomainMembersByEmail(testDomain.id(), asMailAddress(alice))
            .collectList()
            .block();
    }

    private static MailAddress asMailAddress(Username username) {
        try {
            return username.asMailAddress();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
