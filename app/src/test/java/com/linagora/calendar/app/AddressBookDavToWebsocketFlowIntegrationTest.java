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

package com.linagora.calendar.app;

import static com.linagora.calendar.app.TestFixture.awaitMessage;
import static com.linagora.calendar.app.TestFixture.connectWebSocket;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.AddressBookPropertiesUpdate;
import com.linagora.calendar.dav.CardDavClient.AddressBookSharee;
import com.linagora.calendar.dav.CardDavClient.MirrorAddressBooks;
import com.linagora.calendar.dav.CardDavClient.NewAddressBook;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import okhttp3.WebSocket;

class AddressBookDavToWebsocketFlowIntegrationTest {
    private static final String PASSWORD = "secret";
    private static final String MAILTO_PREFIX = "mailto:";
    private static final int ACCESS_NOACCESS = 4;
    private static final List<String> BUILT_IN_ADDRESS_BOOK_IDS = List.of("contacts", "collected", "domain-members");

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @Order(2)
    @RegisterExtension
    static DockerRedisExtension dockerExtension = new DockerRedisExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .enableRedis(),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        new AbstractModule() {
            @Provides
            @Singleton
            public RedisConfiguration redisConfiguration() {
                return StandaloneRedisConfiguration.from(dockerExtension.redisURI().toString());
            }
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private final List<WebSocket> webSockets = new ArrayList<>();
    private CardDavClient cardDavClient;
    private DavTestHelper davTestHelper;
    private OpenPaaSUser alice;
    private OpenPaaSUser bob;
    private OpenPaaSUser cedric;
    private AddressBookURL source;
    private int restApiPort;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        alice = sabreDavExtension.newTestUser(Optional.of("Alice"));
        bob = sabreDavExtension.newTestUser(Optional.of("Bob"));
        cedric = sabreDavExtension.newTestUser(Optional.of("Cedric"));
        CalendarDataProbe probe = server.getProbe(CalendarDataProbe.class);
        probe.addDomain(alice.username().getDomainPart().orElseThrow());
        for (OpenPaaSUser user : List.of(alice, bob, cedric)) {
            probe.addUserToRepository(user.username(), PASSWORD);
        }
        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        source = new AddressBookURL(alice.id(), "collected");
    }

    @AfterEach
    void tearDown() {
        webSockets.forEach(webSocket -> webSocket.close(1000, "test finished"));
    }

    @Test
    void delegationShouldNotifyOwnerAndSharee() {
        // Alice owns the source and grants Bob READ access.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);

        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();
        AddressBookURL bobCopy = findCopy(bob);

        String sourceUpdate = awaitMessage(aliceMessagesQueue,
            message -> message.contains("updated") && message.contains(source.asUri().toString()));
        assertThatJson(sourceUpdate)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{source}"]
                    }
                }
                """.replace("{source}", source.asUri().toString()));

        String delegatedNotification = awaitMessage(bobMessagesQueue,
            message -> message.contains("delegated") && message.contains(bobCopy.asUri().toString()));
        assertThatJson(delegatedNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "delegated": ["{copy}"]
                    }
                }
                """.replace("{copy}", bobCopy.asUri().toString()));
    }

    @Test
    void adminDelegationShouldNotifyOriginalOwnerAndNewSharee() {
        // Roles: Alice owns the source; Bob is its ADMIN delegate; Cedric receives the new copy.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL bobCopy = delegateToBob(DavRight.ADMINISTRATION, aliceMessagesQueue, bobMessagesQueue);
        BlockingQueue<String> cedricMessagesQueue = connectUser(cedric);

        // Bob grants Cedric READ through his delegated copy. No client registers a book URI.
        cardDavClient.updateAddressBookShares(bob.username(), bobCopy,
            List.of(new AddressBookSharee(MAILTO_PREFIX + cedric.username().asString(), DavRight.READ.access())))
            .block();
        AddressBookURL cedricCopy = findCopy(cedric);

        String sourceUpdate = awaitMessage(aliceMessagesQueue,
            message -> message.contains("updated") && message.contains(source.asUri().toString()));
        assertThatJson(sourceUpdate)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{source}"]
                    }
                }
                """.replace("{source}", source.asUri().toString()));

        String delegatedNotification = awaitMessage(cedricMessagesQueue, message -> message.contains("delegated"));
        assertThatJson(delegatedNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "delegated": ["{copy}"]
                    }
                }
                """.replace("{copy}", cedricCopy.asUri().toString()));

        // Bob is the actor, but neither changed path belongs to his personal list.
        Awaitility.await()
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(bobMessagesQueue)
                .noneMatch(message -> message.contains(source.asUri().toString())
                    || message.contains(cedricCopy.asUri().toString())));
    }

    @Test
    void rightsChangeShouldNotifySourceOwnerAndExistingDelegate() {
        // Roles: Alice owns the source; Bob already has READ; Alice changes Bob's right to READ_WRITE.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL bobCopy = delegateToBob(DavRight.READ, aliceMessagesQueue, bobMessagesQueue);

        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ_WRITE.access())))
            .block();

        String sourceUpdate = awaitMessage(aliceMessagesQueue,
            message -> message.contains("updated") && message.contains(source.asUri().toString()));
        assertThatJson(sourceUpdate)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{source}"]
                    }
                }
                """.replace("{source}", source.asUri().toString()));

        String copyUpdate = awaitMessage(bobMessagesQueue, message -> message.contains("updated"));
        assertThatJson(copyUpdate)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{copy}"]
                    }
                }
                """.replace("{copy}", bobCopy.asUri().toString()));
    }

    @Test
    void deletingSourceAddressBookShouldNotifyOwnerAndSharee() {
        // Use a personal book because built-in books cannot be deleted.
        source = new AddressBookURL(alice.id(), UUID.randomUUID().toString());
        cardDavClient.createUserAddressBook(alice.username(), alice.id(),
            new NewAddressBook(source.addressBookId(), "Team", "Contacts"))
            .block();
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL bobCopy = delegateToBob(DavRight.READ, aliceMessagesQueue, bobMessagesQueue);

        cardDavClient.deleteUserAddressBook(alice.username(), source).block();

        String sourceDeletion = awaitMessage(aliceMessagesQueue,
            message -> message.contains("deleted") && message.contains(source.asUri().toString()));
        assertThatJson(sourceDeletion)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "deleted": ["{source}"]
                    }
                }
                """.replace("{source}", source.asUri().toString()));

        String copyDeletion = awaitMessage(bobMessagesQueue,
            message -> message.contains("deleted") && message.contains(bobCopy.asUri().toString()));
        assertThatJson(copyDeletion)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "deleted": ["{copy}"]
                    }
                }
                """.replace("{copy}", bobCopy.asUri().toString()));
    }

    @Test
    void revocationShouldNotifyOwnerAndRemoveShareeCopy() {
        // GIVEN: Alice owns the book and Bob has a READ delegation in his list.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL bobCopy = delegateToBob(DavRight.READ, aliceMessagesQueue, bobMessagesQueue);

        // WHEN: Alice revokes Bob's delegation.
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), ACCESS_NOACCESS)))
            .block();

        // THEN: Alice receives updated for the source and Bob receives deleted for his removed copy.
        String sourceUpdate = awaitMessage(aliceMessagesQueue,
            message -> message.contains("updated") && message.contains(source.asUri().toString()));
        assertThatJson(sourceUpdate)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{source}"]
                    }
                }
                """.replace("{source}", source.asUri().toString()));

        String copyDeletion = awaitMessage(bobMessagesQueue,
            message -> message.contains("deleted") && message.contains(bobCopy.asUri().toString()));
        assertThatJson(copyDeletion)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "deleted": ["{copy}"]
                    }
                }
                """.replace("{copy}", bobCopy.asUri().toString()));
        assertThat(cardDavClient.listUserAddressBookUrls(bob.username(), bob.id(), MirrorAddressBooks.INCLUDE)
                .collectList()
                .block())
            .doesNotContain(bobCopy);
    }

    @Test
    void revocationAfterUnsubscribingShouldOnlyNotifyOwner() {
        // GIVEN: Bob already unsubscribed and received deleted for his copy; his READ rights still exist.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL bobCopy = delegateToBob(DavRight.READ, aliceMessagesQueue, bobMessagesQueue);
        cardDavClient.deleteUserAddressBook(bob.username(), bobCopy).block();
        awaitMessage(bobMessagesQueue,
            message -> message.contains("deleted") && message.contains(bobCopy.asUri().toString()));

        // WHEN: Alice revokes Bob's remaining delegation rights.
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), ACCESS_NOACCESS)))
            .block();

        // THEN: Alice receives updated; Bob receives no further notification for the old URI.
        String sourceUpdate = awaitMessage(aliceMessagesQueue,
            message -> message.contains("updated") && message.contains(source.asUri().toString()));
        assertThatJson(sourceUpdate)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{source}"]
                    }
                }
                """.replace("{source}", source.asUri().toString()));

        Awaitility.await()
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(bobMessagesQueue)
                .noneMatch(message -> message.contains(bobCopy.asUri().toString())));
        assertThat(cardDavClient.listUserAddressBookUrls(bob.username(), bob.id(), MirrorAddressBooks.INCLUDE)
                .collectList()
                .block())
            .doesNotContain(bobCopy);
    }

    @Test
    void ownerShouldReceiveBookCreationRenameAndDeletionWithoutRegisteringBook() {
        // Roles: Alice owns a new book; Bob is an unrelated user and must not receive Alice's changes.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL book = new AddressBookURL(alice.id(), UUID.randomUUID().toString());

        cardDavClient.createUserAddressBook(alice.username(), alice.id(),
            new NewAddressBook(book.addressBookId(), "Team", "Contacts"))
            .block();

        String createdNotification = awaitMessage(aliceMessagesQueue,
            message -> message.contains("created") && message.contains(book.asUri().toString()));
        assertThatJson(createdNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "created": ["{book}"]
                    }
                }
                """.replace("{book}", book.asUri().toString()));

        cardDavClient.updateAddressBookProperties(alice.username(), book,
            new AddressBookPropertiesUpdate(Optional.of("Renamed"), Optional.empty()))
            .block();

        String updatedNotification = awaitMessage(aliceMessagesQueue,
            message -> message.contains("updated") && message.contains(book.asUri().toString()));
        assertThatJson(updatedNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{book}"]
                    }
                }
                """.replace("{book}", book.asUri().toString()));

        cardDavClient.deleteUserAddressBook(alice.username(), book).block();

        String deletedNotification = awaitMessage(aliceMessagesQueue,
            message -> message.contains("deleted") && message.contains(book.asUri().toString()));
        assertThatJson(deletedNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "deleted": ["{book}"]
                    }
                }
                """.replace("{book}", book.asUri().toString()));

        Awaitility.await()
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(bobMessagesQueue)
                .noneMatch(message -> message.contains(book.asUri().toString())));
    }

    @Test
    void publicSubscriptionShouldNotifySubscriber() {
        // Roles: Alice publishes her source; Bob subscribes himself without receiving delegation.
        cardDavClient.updateAddressBookPublicRight(alice.username(), source, true).block();
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        String subscriptionId = UUID.randomUUID().toString();
        AddressBookURL subscription = new AddressBookURL(bob.id(), subscriptionId);

        davTestHelper.createAddressBookSubscription(bob, subscriptionId, "Alice Public", source).block();

        String subscribedNotification = awaitMessage(bobMessagesQueue, message -> message.contains("subscribed"));
        assertThatJson(subscribedNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "subscribed": ["{copy}"]
                    }
                }
                """.replace("{copy}", subscription.asUri().toString()));
    }

    @Test
    void resubscribingToDelegatedAddressBookShouldNotifyShareeOfNewCopyUri() {
        // Bob unsubscribes, then resubscribes under a new URI while retaining Alice's READ delegation.
        BlockingQueue<String> aliceMessagesQueue = connectUser(alice);
        BlockingQueue<String> bobMessagesQueue = connectUser(bob);
        AddressBookURL bobCopy = delegateToBob(DavRight.READ, aliceMessagesQueue, bobMessagesQueue);
        cardDavClient.deleteUserAddressBook(bob.username(), bobCopy).block();

        String copyDeletion = awaitMessage(bobMessagesQueue,
            message -> message.contains("deleted") && message.contains(bobCopy.asUri().toString()));
        assertThatJson(copyDeletion)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "deleted": ["{copy}"]
                    }
                }
                """.replace("{copy}", bobCopy.asUri().toString()));
        AddressBookURL restoredCopy = new AddressBookURL(bob.id(), UUID.randomUUID().toString());

        davTestHelper.createAddressBookSubscription(bob, restoredCopy.addressBookId(), "Restored", source).block();

        String restoredNotification = awaitMessage(bobMessagesQueue, message -> message.contains("delegated"));
        assertThatJson(restoredNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "delegated": ["{copy}"]
                    }
                }
                """.replace("{copy}", restoredCopy.asUri().toString()));
    }

    @Test
    void addressBookListChangeShouldReachBothConnectionsOfSameUser() {
        // GIVEN: Alice opens two sessions, representing two browser tabs or devices.
        BlockingQueue<String> firstSessionMessages = connectUser(alice);
        BlockingQueue<String> secondSessionMessages = connectUser(alice);
        AddressBookURL book = new AddressBookURL(alice.id(), UUID.randomUUID().toString());

        // WHEN: Alice creates a book without registering its URI on either session.
        cardDavClient.createUserAddressBook(alice.username(), alice.id(),
            new NewAddressBook(book.addressBookId(), "Team", "Contacts"))
            .block();

        // THEN: Both sessions receive the same list notification.
        for (BlockingQueue<String> messages : List.of(firstSessionMessages, secondSessionMessages)) {
            String notification = awaitMessage(messages,
                message -> message.contains("created") && message.contains(book.asUri().toString()));
            assertThatJson(notification)
                .isEqualTo("""
                    {
                        "addressBookList": {
                            "created": ["{book}"]
                        }
                    }
                    """.replace("{book}", book.asUri().toString()));
        }
    }

    @Test
    void registeredAddressBookShouldReceiveListChangesAndContactSyncTokensOnSameConnection() {
        // GIVEN: Alice registers a personal book for contacts while retaining the default list subscription.
        AddressBookURL book = new AddressBookURL(alice.id(), UUID.randomUUID().toString());
        cardDavClient.createUserAddressBook(alice.username(), alice.id(),
            new NewAddressBook(book.addressBookId(), "Team", "Contacts"))
            .block();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket webSocket = connectUser(alice, messages);
        String bookUri = book.asUri().toString();
        webSocket.send("""
            {
                "register": ["{book}"]
            }
            """.replace("{book}", bookUri));
        awaitMessage(messages, message -> message.contains("\"registered\"") && message.contains(bookUri));
        String vcardUid = UUID.randomUUID().toString();
        byte[] contact = """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Alice Contact
            END:VCARD
            """.replace("{uid}", vcardUid).getBytes(StandardCharsets.UTF_8);

        // WHEN: Alice renames the book and creates a contact in it.
        cardDavClient.updateAddressBookProperties(alice.username(), book,
            new AddressBookPropertiesUpdate(Optional.of("Renamed"), Optional.empty()))
            .block();
        cardDavClient.upsertContact(alice.username(), book, vcardUid, contact).block();
        SyncToken syncToken = cardDavClient.retrieveSyncToken(alice.username(), book).block();

        // THEN: The same connection receives both payload types with their respective data.
        String listNotification = awaitMessage(messages,
            message -> message.contains("\"addressBookList\"")
                && message.contains("\"updated\"") && message.contains(bookUri));
        assertThatJson(listNotification)
            .isEqualTo("""
                {
                    "addressBookList": {
                        "updated": ["{book}"]
                    }
                }
                """.replace("{book}", bookUri));

        String contactNotification = awaitMessage(messages,
            message -> message.contains(bookUri) && message.contains("\"syncToken\":\"" + syncToken.value() + "\""));
        assertThatJson(contactNotification)
            .isEqualTo("""
                {
                    "{book}": {
                        "syncToken": "{syncToken}"
                    }
                }
                """.replace("{book}", bookUri).replace("{syncToken}", syncToken.value()));
    }

    private BlockingQueue<String> connectUser(OpenPaaSUser user) {
        BlockingQueue<String> messagesQueue = new LinkedBlockingQueue<>();
        connectUser(user, messagesQueue);
        return messagesQueue;
    }

    private WebSocket connectUser(OpenPaaSUser user, BlockingQueue<String> messagesQueue) {
        String ticket = given()
            .auth().preemptive().basic(user.username().asString(), PASSWORD)
        .when()
            .post("http://localhost:" + restApiPort + "/ws/ticket")
        .then()
            .statusCode(SC_OK)
            .extract().path("value");
        // Default list notifications need no explicit address book registration.
        WebSocket webSocket = connectWebSocket(restApiPort, ticket, messagesQueue);
        webSockets.add(webSocket);
        return webSocket;
    }

    private AddressBookURL delegateToBob(DavRight right, BlockingQueue<String> aliceMessagesQueue, BlockingQueue<String> bobMessagesQueue) {
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), right.access())))
            .block();
        AddressBookURL bobCopy = findCopy(bob);
        // Consume both initial grant messages before testing a later update/revoke/share action.
        awaitMessage(aliceMessagesQueue, message -> message.contains("updated") && message.contains(source.asUri().toString()));
        awaitMessage(bobMessagesQueue, message -> message.contains("delegated") && message.contains(bobCopy.asUri().toString()));
        return bobCopy;
    }

    private AddressBookURL findCopy(OpenPaaSUser user) {
        return cardDavClient.listUserAddressBookUrls(user.username(), user.id(), MirrorAddressBooks.INCLUDE)
            .filter(url -> !BUILT_IN_ADDRESS_BOOK_IDS.contains(url.addressBookId()))
            .next().block();
    }
}
