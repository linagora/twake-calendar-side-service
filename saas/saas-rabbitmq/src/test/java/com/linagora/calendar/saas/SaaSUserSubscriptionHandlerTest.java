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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.MemoryDomainSettingsDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.UserContactResolver;
import com.linagora.calendar.storage.UserContactResolver.UserContact;
import com.linagora.calendar.storage.UserNameResolver;
import com.linagora.calendar.storage.UserNameResolver.UserNames;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

class SaaSUserSubscriptionHandlerTest {

    private static class MutableUserContactResolver implements UserContactResolver {
        private final Map<Username, UserContact> contacts = new ConcurrentHashMap<>();
        private final AtomicInteger lookups = new AtomicInteger();
        private Optional<RuntimeException> failure = Optional.empty();

        void register(Username username, UserContact contact) {
            contacts.put(username, contact);
        }

        void unregister(Username username) {
            contacts.remove(username);
        }

        void fail(RuntimeException exception) {
            failure = Optional.of(exception);
        }

        @Override
        public Mono<Optional<UserContact>> resolve(Username username) {
            return Mono.defer(() -> {
                lookups.incrementAndGet();
                return failure.<Mono<Optional<UserContact>>>map(Mono::error)
                    .orElseGet(() -> Mono.just(Optional.ofNullable(contacts.get(username))));
            });
        }
    }

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private SaaSUserSubscriptionHandler testee;
    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private OpenPaaSDomain testDomain;
    private MutableUserContactResolver userContactResolver;

    @BeforeEach
    void setupEach() throws Exception {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        DomainSettingsResolver domainSettingsResolver = new DomainSettingsResolver(
            new MemoryDomainSettingsDAO(), Set.of(Domain.of("nonshared.tld")), Set.of(), new MapConfiguration(Map.of()));
        userContactResolver = new MutableUserContactResolver();
        testee = new SaaSUserSubscriptionHandler(new SaaSUserProvisioner(userDAO, domainDAO, cardDavClient, domainSettingsResolver,
            new UserNameResolver.Noop(), userContactResolver));
        testDomain = createNewDomainWithAddressBook();
    }

    private OpenPaaSDomain createNewDomainWithAddressBook() {
        return domainDAO.add(Domain.of("test-domain-" + UUID.randomUUID() + ".tld")).block();
    }

    @Test
    void shouldRegisterUserAndAddToAddressBookWhenCalendarFeatureEnabled() {
        String userEmail = "bob@" + testDomain.domain().asString();
        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNotNull();
        assertThat(user.username()).isEqualTo(Username.of(userEmail));

        String vcardContent = listContactDomainMembersAsVcard(testDomain);
        assertThat(vcardContent).contains(userEmail);
    }

    @Test
    void shouldNotRegisterUserWhenCalendarFeatureNotPresent() {
        String userEmail = "no-calendar@" + testDomain.domain().asString();
        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "mail": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNull();

        String vcardContent = listContactDomainMembersAsVcard(testDomain);
        assertThat(vcardContent).doesNotContain(userEmail);
    }

    @Test
    void shouldNotFailWhenUserAlreadyExists() {
        String userEmail = "existing@" + testDomain.domain().asString();
        userDAO.add(Username.of(userEmail)).block();

        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNotNull();
    }

    @Test
    void shouldHandleMultipleUsers() {
        String aliceEmail = "alice@" + testDomain.domain().asString();
        String bobEmail = "bob@" + testDomain.domain().asString();

        String json1 = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(aliceEmail);

        String json2 = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": false,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(bobEmail);

        testee.handleMessage(json1.getBytes(StandardCharsets.UTF_8)).block();
        testee.handleMessage(json2.getBytes(StandardCharsets.UTF_8)).block();

        assertThat(userDAO.retrieve(Username.of(aliceEmail)).block()).isNotNull();
        assertThat(userDAO.retrieve(Username.of(bobEmail)).block()).isNotNull();

        String vcardContent = listContactDomainMembersAsVcard(testDomain);
        assertThat(vcardContent).contains(aliceEmail).contains(bobEmail);
    }

    @Test
    void shouldNotAddContactToAddressBookWhenNotSupportSharingForDomain() {
        OpenPaaSDomain nonSharedDomain = domainDAO.add(Domain.of("nonshared.tld")).block();
        cardDavClient.createDomainMembersAddressBook(nonSharedDomain.id()).block();

        String userEmail = "bob@" + nonSharedDomain.domain().asString();
        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNotNull();
        assertThat(user.username()).isEqualTo(Username.of(userEmail));

        String vcardContent = listContactDomainMembersAsVcard(nonSharedDomain);
        assertThat(vcardContent).doesNotContain(userEmail);
    }

    @Test
    void shouldRegisterNamesFromDirectory() {
        // Given a new calendar user known to the directory
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().build());

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the user is registered with the names of the directory
        OpenPaaSUser user = userDAO.retrieve(alice).block();
        assertThat(user.firstname()).isEqualTo("Alice");
        assertThat(user.lastname()).isEqualTo("Martin");
    }

    @Test
    void shouldReimportNamesFromDirectory() {
        // Given a registered user renamed in the directory since
        Username alice = aliceOfTestDomain();
        userDAO.add(alice, "Old", "Name").block();
        userContactResolver.register(alice, aliceContact().build());

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the user names follow the directory
        OpenPaaSUser user = userDAO.retrieve(alice).block();
        assertThat(user.firstname()).isEqualTo("Alice");
        assertThat(user.lastname()).isEqualTo("Martin");
    }

    @Test
    void shouldLookupDirectoryOnce() {
        // Given a new calendar user known to the directory
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().build());

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the directory is queried once, for both the names and the card
        assertThat(userContactResolver.lookups).hasValue(1);
    }

    @Test
    void shouldWriteDirectoryDetailsOnCard() {
        // Given a calendar user whose directory entry has a given name, family name, display name, telephone number
        // and workspace host
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().build());

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the domain member card carries the mail and every directory detail
        assertThat(readDomainMemberCard(alice))
            .usingRecursiveComparison()
            .ignoringFields("uid")
            .isEqualTo(AddressBookContact.builder()
                .mail(asMailAddress(alice))
                .givenName("Alice")
                .familyName("Martin")
                .displayName("Alice Martin")
                .telephoneNumber("+33600000000")
                .extraProperties(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"))
                .build());
    }

    @Test
    void shouldNotWriteWorkplaceFqdnWhenDirectoryHasNone() {
        // Given a calendar user whose workspace does not exist yet
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().extraProperties(ImmutableListMultimap.of()).build());

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the domain member card has no workspace host
        assertThat(readDomainMemberCard(alice).extraProperties()).isEqualTo(ImmutableListMultimap.of());
    }

    @Test
    void shouldNotRewriteCardWhenUnchanged() {
        // Given a domain member card holding the directory details, plus a note the side service never writes
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().build());
        handleCalendarSubscription(alice);
        AddressBookContact stored = readDomainMemberCard(alice);
        String cardWithNote = stored.toVcardString().replace("END:VCARD", "NOTE:written before\r\nEND:VCARD");
        cardDavClient.upsertContactDomainMembers(testDomain.id(), stored.vcardUid(), cardWithNote.getBytes(StandardCharsets.UTF_8)).block();

        // When the subscription of that user is handled again
        handleCalendarSubscription(alice);

        // Then the card is not written again, the note being still there
        assertThat(readDomainMemberCardAsString(alice)).contains("NOTE:written before");
    }

    @Test
    void shouldUpdateCardWhenDirectoryChanged() {
        // Given a domain member card written before the user got a workspace
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().extraProperties(ImmutableListMultimap.of()).build());
        handleCalendarSubscription(alice);

        // When the workspace exists and the subscription of that user is handled again
        userContactResolver.register(alice, aliceContact().build());
        handleCalendarSubscription(alice);

        // Then the card carries the workspace host
        assertThat(readDomainMemberCard(alice).extraProperties())
            .isEqualTo(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"));
    }

    @Test
    void shouldWriteMailOnlyCardWhenUserUnknownToDirectory() {
        // Given a calendar user the directory does not know
        Username alice = aliceOfTestDomain();

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the domain member card holds the mail only
        AddressBookContact card = readDomainMemberCard(alice);
        assertThat(card.mail()).contains(asMailAddress(alice));
        assertThat(card.familyName()).isEmpty();
    }

    @Test
    void shouldNotOverrideCardWhenUserUnknownToDirectory() {
        // Given a domain member card holding the directory details
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().build());
        handleCalendarSubscription(alice);

        // When the directory no longer knows that user and the subscription is handled again
        userContactResolver.unregister(alice);
        handleCalendarSubscription(alice);

        // Then the card keeps the directory details
        assertThat(readDomainMemberCard(alice).displayName()).contains("Alice Martin");
    }

    @Test
    void shouldKeepCardWhenDirectoryFails() {
        // Given a domain member card holding the directory details, and a directory now failing
        Username alice = aliceOfTestDomain();
        userContactResolver.register(alice, aliceContact().build());
        handleCalendarSubscription(alice);
        userContactResolver.fail(new RuntimeException("LDAP is down"));

        // When the subscription of that user is handled again
        handleCalendarSubscription(alice);

        // Then the card keeps the directory details
        assertThat(readDomainMemberCard(alice).displayName()).contains("Alice Martin");
    }

    @Test
    void shouldWriteMailOnlyCardWhenDirectoryFails() {
        // Given a new calendar user, and a failing directory
        Username alice = aliceOfTestDomain();
        userContactResolver.fail(new RuntimeException("LDAP is down"));

        // When the subscription of that user is handled
        handleCalendarSubscription(alice);

        // Then the user is registered, and its domain member card holds the mail only
        assertThat(userDAO.retrieve(alice).block()).isNotNull();
        AddressBookContact card = readDomainMemberCard(alice);
        assertThat(card.mail()).contains(asMailAddress(alice));
        assertThat(card.familyName()).isEmpty();
    }

    private Username aliceOfTestDomain() {
        return Username.of("alice@" + testDomain.domain().asString());
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

    private void handleCalendarSubscription(Username username) {
        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(username.asString());

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();
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

    private static MailAddress asMailAddress(Username username) {
        try {
            return username.asMailAddress();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String listContactDomainMembersAsVcard(OpenPaaSDomain domain) {
        return cardDavClient.listContactDomainMembers(domain.id())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }
}
