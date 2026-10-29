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
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.UserContactResolver;
import com.linagora.calendar.storage.UserContactResolver.UserContact;
import com.linagora.calendar.storage.UserNameResolver;
import com.linagora.calendar.storage.UserNameResolver.UserNames;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

class TWPUserCreationHandlerTest {

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

    private TWPUserCreationHandler testee;
    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private OpenPaaSDomain testDomain;
    private Domain searchDisabledDomain;
    private MutableUserContactResolver userContactResolver;

    @BeforeEach
    void setupEach() throws Exception {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        searchDisabledDomain = randomDomain();
        DomainSettingsResolver domainSettingsResolver = new DomainSettingsResolver(
            new MemoryDomainSettingsDAO(), Set.of(searchDisabledDomain), Set.of(), new MapConfiguration(Map.of()));
        userContactResolver = new MutableUserContactResolver();
        testee = new TWPUserCreationHandler(new SaaSUserProvisioner(userDAO, domainDAO, cardDavClient, domainSettingsResolver,
            new UserNameResolver.Noop(), userContactResolver));
        testDomain = domainDAO.add(randomDomain()).block();
    }

    @Test
    void shouldAddCardBuiltFromDirectoryWithoutRegisteringUser() {
        // Given a member whose directory entry has names, a telephone number and a workspace host
        Username alice = aliceOf(testDomain);
        userContactResolver.register(alice, aliceContact().build());

        // When the user.created event of that member is handled
        handleUserCreated(alice);

        // Then the domain member card carries the mail and every directory detail, and no calendar user is registered
        assertThat(readDomainMemberCard(alice))
            .usingRecursiveComparison()
            .ignoringFields("uid")
            .isEqualTo(aliceCard(alice));
        assertThat(userDAO.retrieve(alice).blockOptional()).isEmpty();
    }

    @Test
    void shouldAddCardWhenB2CEventLacksOrganizationFields() {
        // Given a B2C member known to the directory
        Username alice = aliceOf(testDomain);
        userContactResolver.register(alice, aliceContact().build());

        // When a user.created event without organizationId, domain, organizationDomain and mobile is handled
        handle("""
            {
                "twakeId": "alice",
                "internalEmail": "%s",
                "workplaceFqdn": "alice.example.com"
            }
            """.formatted(alice.asString()));

        // Then the domain member card carries the mail and every directory detail
        assertThat(readDomainMemberCard(alice))
            .usingRecursiveComparison()
            .ignoringFields("uid")
            .isEqualTo(aliceCard(alice));
    }

    @Test
    void shouldNotRewriteCardWhenEventReplayed() {
        // Given a domain member card written upon a user.created event, plus a note the side service never writes
        Username alice = aliceOf(testDomain);
        userContactResolver.register(alice, aliceContact().build());
        handleUserCreated(alice);
        AddressBookContact stored = readDomainMemberCard(alice);
        String cardWithNote = stored.toVcardString().replace("END:VCARD", "NOTE:written before\r\nEND:VCARD");
        cardDavClient.upsertContactDomainMembers(testDomain.id(), stored.vcardUid(), cardWithNote.getBytes(StandardCharsets.UTF_8)).block();

        // When the same event is delivered again
        handleUserCreated(alice);

        // Then the card is not written again, the note being still there
        assertThat(readDomainMemberCardAsString(alice)).contains("NOTE:written before");
    }

    @Test
    void shouldUpdateCardOfSameEmail() {
        // Given a domain member card of the member written by the LDAP sync, named after the LDAP uid and lacking the directory details
        Username alice = aliceOf(testDomain);
        userContactResolver.register(alice, aliceContact().build());
        AddressBookContact ldapCard = AddressBookContact.builder().uid("alice-ldap").mail(asMailAddress(alice)).build();
        cardDavClient.upsertContactDomainMembers(testDomain.id(), "alice-ldap", ldapCard.toVcardBytes()).block();

        // When the user.created event of that member is handled
        handleUserCreated(alice);

        // Then that card, keeping its name and uid, carries the directory details, and no second card of the member is added
        assertThat(findDomainMemberCards(alice))
            .containsExactly(new DomainMemberCard("alice-ldap", AddressBookContact.builder()
                .uid("alice-ldap")
                .userContact(aliceContact().build())
                .mail(asMailAddress(alice))
                .build()));
    }

    @Test
    void shouldAddMailOnlyCardWhenMemberUnknownToDirectory() {
        // Given a member the directory does not know
        Username alice = aliceOf(testDomain);

        // When the user.created event of that member is handled
        handleUserCreated(alice);

        // Then the domain member card holds the mail only
        assertThat(readDomainMemberCard(alice))
            .usingRecursiveComparison()
            .ignoringFields("uid")
            .isEqualTo(AddressBookContact.builder()
                .mail(asMailAddress(alice))
                .displayName(alice.asString())
                .build());
    }

    @Test
    void shouldFailWithoutWritingCardWhenDirectoryFails() {
        // Given a member, and a failing directory
        Username alice = aliceOf(testDomain);
        userContactResolver.fail(new RuntimeException("LDAP is down"));

        // When the user.created event of that member is handled
        // Then the handling fails, so that the event goes to the dead letter queue, and no card is written
        assertThatThrownBy(() -> handleUserCreated(alice))
            .hasMessageContaining("LDAP is down");
        assertThat(findDomainMemberCards(alice)).isEmpty();
    }

    @Test
    void shouldNotAddCardWhenDomainDisablesUserSearch() {
        // Given a member of a domain whose user search is disabled
        OpenPaaSDomain domain = domainDAO.add(searchDisabledDomain).block();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        Username alice = aliceOf(domain);
        userContactResolver.register(alice, aliceContact().build());

        // When the user.created event of that member is handled
        handleUserCreated(alice);

        // Then no card is added to the address book of that domain
        assertThat(cardDavClient.findContactsDomainMembersByEmail(domain.id(), asMailAddress(alice)).collectList().block())
            .isEmpty();
    }

    @Test
    void shouldFailWhenInternalEmailMissing() {
        // Given a user.created event without internalEmail
        String event = """
            {
                "twakeId": "alice",
                "workplaceFqdn": "alice.example.com"
            }
            """;

        // When the event is handled
        // Then the handling fails, so that the event goes to the dead letter queue
        assertThatThrownBy(() -> handle(event))
            .hasMessageContaining("internalEmail");
    }

    private static Domain randomDomain() {
        return Domain.of("test-domain-" + UUID.randomUUID() + ".tld");
    }

    private static Username aliceOf(OpenPaaSDomain domain) {
        return Username.of("alice@" + domain.domain().asString());
    }

    private static UserContact.Builder aliceContact() {
        return UserContact.builder()
            .names(Optional.of(new UserNames("Alice", "Martin")))
            .givenName(Optional.of("Alice"))
            .familyName(Optional.of("Martin"))
            .displayName(Optional.of("Alice Martin"))
            .telephoneNumber(Optional.of("+33600000000"))
            .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"));
    }

    private static AddressBookContact aliceCard(Username alice) {
        return AddressBookContact.builder()
            .mail(asMailAddress(alice))
            .givenName("Alice")
            .familyName("Martin")
            .displayName("Alice Martin")
            .telephoneNumber("+33600000000")
            .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"))
            .build();
    }

    private static String userCreated(Username username) {
        return """
            {
                "twakeId": "alice",
                "internalEmail": "%s",
                "workplaceFqdn": "alice.example.com",
                "organizationId": "org1234",
                "domain": "%s",
                "organizationDomain": "%s",
                "mobile": "+33600000000"
            }
            """.formatted(username.asString(), username.getDomainPart().orElseThrow().asString(),
            username.getDomainPart().orElseThrow().asString());
    }

    private void handleUserCreated(Username username) {
        handle(userCreated(username));
    }

    private void handle(String event) {
        testee.handleMessage(event.getBytes(StandardCharsets.UTF_8)).block();
    }

    private String readDomainMemberCardAsString(Username username) {
        String vcardUid = AddressBookContact.computeUid(Optional.empty(), Optional.of(asMailAddress(username))).orElseThrow();
        return cardDavClient.retrieveContactDomainMembers(testDomain.id(), vcardUid)
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .blockOptional()
            .orElseThrow();
    }

    private AddressBookContact readDomainMemberCard(Username username) {
        return AddressBookContact.parse(readDomainMemberCardAsString(username).getBytes(StandardCharsets.UTF_8)).getFirst();
    }

    private List<DomainMemberCard> findDomainMemberCards(Username username) {
        return cardDavClient.findContactsDomainMembersByEmail(testDomain.id(), asMailAddress(username))
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
