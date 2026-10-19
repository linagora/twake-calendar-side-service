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

package com.linagora.calendar.amqp;

import static com.linagora.calendar.amqp.TestFixture.RETRY_BACKOFF_CONFIGURATION;
import static com.linagora.calendar.amqp.TestFixture.awaitAtMost;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.SSLException;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.events.EventBus;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.amqp.AddressBookListNotificationConsumer.AddressBookListChangesMessage;
import com.linagora.calendar.amqp.AddressBookListNotificationConsumer.AddressBookListExchange;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.AddressBookPropertiesUpdate;
import com.linagora.calendar.dav.CardDavClient.AddressBookSharee;
import com.linagora.calendar.dav.CardDavClient.MirrorAddressBooks;
import com.linagora.calendar.dav.CardDavClient.NewAddressBook;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookListChangedEvent;
import com.linagora.calendar.storage.AddressBookListChangedEvent.ChangeType;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UsernameRegistrationKey;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.rabbitmq.client.Channel;

import reactor.core.publisher.Mono;

class AddressBookListNotificationConsumerTest {
    private static final List<String> BUILT_IN_ADDRESS_BOOK_IDS = List.of("contacts", "collected", "domain-members");

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;
    private static Channel channel;

    private EventBus eventBus;
    private CardDavClient cardDavClient;
    private AddressBookListNotificationConsumer consumer;
    private AddressBookListNotificationHandler handler;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private OpenPaaSUserDAO userDAO;

    @BeforeAll
    static void beforeAll(DockerSabreDavSetup dockerSabreDavSetup) throws Exception {
        RabbitMQConfiguration rabbitMQConfiguration = dockerSabreDavSetup.rabbitMQConfiguration();
        RabbitMQConnectionFactory connectionFactory = new RabbitMQConnectionFactory(rabbitMQConfiguration);
        connectionPool = new SimpleConnectionPool(connectionFactory,
            SimpleConnectionPool.Configuration.builder()
                .retries(2)
                .initialDelay(Duration.ofMillis(5)));
        channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.builder()
                .retries(2)
                .maxBorrowDelay(Duration.ofMillis(250))
                .maxChannel(10),
            new RecordingMetricFactory(),
            new NoopGaugeRegistry());
        channelPool.start();

        davTestHelper = new DavTestHelper(dockerSabreDavSetup.davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        channel = connectionPool.getResilientConnection().block().createChannel();
    }

    @AfterAll
    static void afterAll() throws Exception {
        channel.close();
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    void setUp() throws SSLException {
        eventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()),
            RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters());
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        handler = new AddressBookListNotificationHandler(eventBus, userDAO, domainDAO, cardDavClient);

        // Isolate deliveries and dead letters left by the previous test before starting the consumer.
        sabreDavExtension.deleteRabbitMQQueues(AddressBookListNotificationConsumer.QUEUE_NAME,
            AddressBookListNotificationConsumer.DEAD_LETTER_QUEUE);
        consumer = new AddressBookListNotificationConsumer(channelPool, QueueArguments.Builder::new, handler);
        consumer.init();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void shouldDispatchCreatedOnlyToAddressBookOwner() {
        // GIVEN: The owner and an unrelated user both listen for list changes.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        Queue<AddressBookListChangedEvent> ownerEvents = registerListener(owner);
        Queue<AddressBookListChangedEvent> otherEvents = registerListener(otherUser);

        // WHEN: The owner creates a personal address book.
        AddressBookURL book = createAddressBook(owner);

        // THEN: Only the owner receives CREATED for the new book.
        assertEventReceived(ownerEvents, owner, book, ChangeType.CREATED);
        awaitAtMost
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(otherEvents)
                .noneMatch(event -> event.addressBookURL().equals(book)));
    }

    @Test
    void shouldDispatchUpdatedWhenAddressBookRenamed() {
        // GIVEN: The owner has a personal address book and listens for list changes.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        Queue<AddressBookListChangedEvent> events = registerListener(owner);

        // WHEN: The owner changes the book display name.
        cardDavClient.updateAddressBookProperties(owner.username(), book,
            new AddressBookPropertiesUpdate(Optional.of("Renamed"), Optional.empty()))
            .block();

        // THEN: The owner receives UPDATED for that book.
        assertEventReceived(events, owner, book, ChangeType.UPDATED);
    }

    @Test
    void shouldDispatchDeletedWhenAddressBookDeleted() {
        // GIVEN: The owner has a personal address book and listens for list changes.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        Queue<AddressBookListChangedEvent> events = registerListener(owner);

        // WHEN: The owner deletes the book.
        cardDavClient.deleteUserAddressBook(owner.username(), book).block();

        // THEN: The owner receives DELETED for the removed book.
        assertEventReceived(events, owner, book, ChangeType.DELETED);
    }

    @Test
    void shouldDispatchSubscribedWhenUserSubscribesToPublicAddressBook() {
        // GIVEN: An owner has a book and another user listens for list changes.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser subscriber = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        Queue<AddressBookListChangedEvent> events = registerListener(subscriber);

        // WHEN: The owner makes the book public and the user subscribes to it.
        AddressBookURL copy = createCopy(owner, book, subscriber, false);

        // THEN: The subscriber receives SUBSCRIBED for their copy.
        assertEventReceived(events, subscriber, copy, ChangeType.SUBSCRIBED);
    }

    @Test
    void shouldDispatchDelegatedToShareeAndUpdatedToOwner() {
        // GIVEN: The owner has a book; both the owner and the future sharee listen for changes.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser sharee = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        Queue<AddressBookListChangedEvent> ownerEvents = registerListener(owner);
        Queue<AddressBookListChangedEvent> shareeEvents = registerListener(sharee);

        // WHEN: The owner grants READ delegation to the sharee.
        AddressBookURL copy = createCopy(owner, book, sharee, true);

        // THEN: The owner receives UPDATED for the source; the sharee receives DELEGATED for their copy.
        assertEventReceived(ownerEvents, owner, book, ChangeType.UPDATED);
        assertEventReceived(shareeEvents, sharee, copy, ChangeType.DELEGATED);
    }

    @Test
    void shouldOnlyNotifyShareeWhenDelegatedCopyRenamed() {
        // GIVEN: Both users received the initial delegation notifications.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser sharee = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        Queue<AddressBookListChangedEvent> ownerEvents = registerListener(owner);
        Queue<AddressBookListChangedEvent> shareeEvents = registerListener(sharee);
        AddressBookURL copy = createCopy(owner, book, sharee, true);
        assertEventReceived(ownerEvents, owner, book, ChangeType.UPDATED);
        assertEventReceived(shareeEvents, sharee, copy, ChangeType.DELEGATED);
        ownerEvents.clear();

        // WHEN: The sharee changes only the display name of their copy.
        cardDavClient.updateAddressBookProperties(sharee.username(), copy,
            new AddressBookPropertiesUpdate(Optional.of("My contacts"), Optional.empty()))
            .block();

        // THEN: The sharee receives UPDATED, while the owner receives no update for the source or copy.
        assertEventReceived(shareeEvents, sharee, copy, ChangeType.UPDATED);
        awaitAtMost
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(ownerEvents)
                .noneMatch(event -> event.changeType() == ChangeType.UPDATED
                    && (event.addressBookURL().equals(book) || event.addressBookURL().equals(copy))));
    }

    @Test
    void shouldOnlyNotifyOwnerWhenRightsChangeAfterShareeUnsubscribes() {
        // GIVEN: The sharee received a READ delegation and then unsubscribed from the copy.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser sharee = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        Queue<AddressBookListChangedEvent> ownerEvents = registerListener(owner);
        Queue<AddressBookListChangedEvent> shareeEvents = registerListener(sharee);
        AddressBookURL copy = createCopy(owner, book, sharee, true);
        assertEventReceived(ownerEvents, owner, book, ChangeType.UPDATED);
        assertEventReceived(shareeEvents, sharee, copy, ChangeType.DELEGATED);
        cardDavClient.deleteUserAddressBook(sharee.username(), copy).block();
        assertEventReceived(shareeEvents, sharee, copy, ChangeType.DELETED);
        ownerEvents.clear();
        shareeEvents.clear();

        // WHEN: The owner changes the retained delegation from READ to READ_WRITE.
        cardDavClient.updateAddressBookShares(owner.username(), book,
            List.of(new AddressBookSharee("mailto:" + sharee.username().asString(), DavRight.READ_WRITE.access())))
            .block();

        // THEN: The owner receives UPDATED, while no new event is sent for the unsubscribed copy.
        assertEventReceived(ownerEvents, owner, book, ChangeType.UPDATED);
        awaitAtMost
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(shareeEvents)
                .noneMatch(event -> event.addressBookURL().equals(copy)));
    }

    @Test
    void shouldDispatchUpdatedWhenSubscriberRenamesPublicCopy() {
        // GIVEN: The user subscribes to a public address book.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser subscriber = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        AddressBookURL copy = createCopy(owner, book, subscriber, false);
        Queue<AddressBookListChangedEvent> events = registerListener(subscriber);

        // WHEN: The user renames their own copy.
        cardDavClient.updateAddressBookProperties(subscriber.username(), copy,
            new AddressBookPropertiesUpdate(Optional.of("My contacts"), Optional.empty()))
            .block();

        // THEN: The subscriber receives UPDATED for their copy.
        assertEventReceived(events, subscriber, copy, ChangeType.UPDATED);
    }

    @ParameterizedTest(name = "delegated copy: {0}")
    @ValueSource(booleans = {false, true})
    void shouldDispatchDeletedWhenUserUnsubscribes(boolean delegated) {
        // GIVEN: The user has either a delegated copy (true) or a public subscription (false).
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser subscriber = sabreDavExtension.newTestUser();
        AddressBookURL book = createAddressBook(owner);
        AddressBookURL copy = createCopy(owner, book, subscriber, delegated);
        Queue<AddressBookListChangedEvent> events = registerListener(subscriber);

        // WHEN: The user unsubscribes by deleting their copy.
        cardDavClient.deleteUserAddressBook(subscriber.username(), copy).block();

        // THEN: The user receives DELETED even though the copy is no longer readable through DAV.
        assertEventReceived(events, subscriber, copy, ChangeType.DELETED);
    }

    @ParameterizedTest
    @CsvSource({
        "ADDRESS_BOOK_CREATED, CREATED",
        "ADDRESS_BOOK_UPDATED, UPDATED",
        "ADDRESS_BOOK_DELETED, DELETED",
        "SUBSCRIPTION_UPDATED, UPDATED",
        "SUBSCRIPTION_DELETED, DELETED"
    })
    void shouldNotifyPathOwnerInsteadOfConnectedUser(AddressBookListExchange exchange, ChangeType expected) throws Exception {
        // GIVEN: The path owner and the actor are different users, both listening for list changes.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser actor = sabreDavExtension.newTestUser();
        Queue<AddressBookListChangedEvent> ownerEvents = registerListener(owner);
        Queue<AddressBookListChangedEvent> actorEvents = registerListener(actor);

        // No book exists at this path: these event types must not depend on a DAV metadata lookup.
        AddressBookURL book = new AddressBookURL(owner.id(), UUID.randomUUID().toString());
        byte[] payload = """
            {
                "path": "{path}",
                "connectedUser": "principals/users/{actorId}"
            }
            """
            .replace("{path}", book.asUri().toString())
            .replace("{actorId}", actor.id().value())
            .getBytes(StandardCharsets.UTF_8);

        // WHEN: A notification names the owner in path and the actor in connectedUser.
        channel.basicPublish(exchange.asString(), EMPTY_ROUTING_KEY, null, payload);

        // THEN: The event goes only to the path owner, even when the book no longer exists.
        assertEventReceived(ownerEvents, owner, book, expected);
        awaitAtMost
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(actorEvents)
                .noneMatch(event -> event.addressBookURL().equals(book)));
    }

    @ParameterizedTest
    @EnumSource(AddressBookListExchange.class)
    void shouldIgnoreDomainOwnedAddressBook(AddressBookListExchange exchange) {
        // GIVEN: The book path belongs to an existing domain, not a user.
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(user.username().getDomainPart().orElseThrow())
            .block();
        AddressBookURL book = new AddressBookURL(domain.id(), "domain-members");

        // WHEN: The handler processes a notification for the domain-members book.
        // THEN: The handler ignores the domain-owned book without failing.
        assertThatCode(() -> handler.handle(exchange,
                new AddressBookListChangesMessage(book.asUri().toString()))
            .block())
            .doesNotThrowAnyException();
    }

    @Test
    void shouldIgnoreSubscriptionCreatedWhenCopyDoesNotExist() {
        // GIVEN: The user exists, but the copy named in the creation notification does not.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        AddressBookURL missingCopy = new AddressBookURL(owner.id(), UUID.randomUUID().toString());
        Queue<AddressBookListChangedEvent> events = registerListener(owner);

        // WHEN: The handler processes the subscription creation and looks up the copy through DAV.
        handler.handle(AddressBookListExchange.SUBSCRIPTION_CREATED,
            new AddressBookListChangesMessage(missingCopy.asUri().toString()))
            .block();

        // THEN: No list event is dispatched for the missing copy.
        assertThat(events).noneMatch(event -> event.addressBookURL().equals(missingCopy));
    }

    @ParameterizedTest
    @EnumSource(value = AddressBookListExchange.class, names = {"ADDRESS_BOOK_DELETED", "SUBSCRIPTION_DELETED"})
    void shouldIgnoreDeletionAfterOwnerWasRemoved(AddressBookListExchange exchange) {
        // GIVEN: The owner has already been deleted from MongoDB before the notification is handled.
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        userDAO.delete(owner.username()).block();
        Queue<AddressBookListChangedEvent> events = registerListener(owner);
        AddressBookURL book = new AddressBookURL(owner.id(), "contacts");

        // WHEN: The handler processes a queued book or subscription deletion.
        // THEN: No exception is raised and no event is dispatched to the deleted owner.
        assertThatCode(() -> handler.handle(exchange,
                new AddressBookListChangesMessage(book.asUri().toString()))
            .block())
            .doesNotThrowAnyException();
        assertThat(events).isEmpty();
    }

    @Test
    void shouldFailWhenUpdateOwnerCannotBeResolved() {
        // GIVEN: The notification refers to an ID that belongs to neither a user nor a domain.
        OpenPaaSId unknownOwnerId = new OpenPaaSId(
            UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        AddressBookURL book = new AddressBookURL(unknownOwnerId, "contacts");

        // WHEN: The handler processes an update for that unknown owner.
        // THEN: The handler reports an error containing the unresolved owner ID.
        assertThatThrownBy(() -> handler.handle(AddressBookListExchange.ADDRESS_BOOK_UPDATED,
                new AddressBookListChangesMessage(book.asUri().toString())).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(unknownOwnerId.value());
    }

    @Test
    void invalidPayloadShouldReachDeadLetterQueueWithoutStoppingConsumer() throws Exception {
        // GIVEN: An invalid JSON message has been published to the address book creation exchange.
        channel.basicPublish(AddressBookListExchange.ADDRESS_BOOK_CREATED.asString(), EMPTY_ROUTING_KEY, null,
            "not json".getBytes(StandardCharsets.UTF_8));
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        Queue<AddressBookListChangedEvent> events = registerListener(owner);

        // WHEN: A real user creates a book after the invalid message.
        AddressBookURL book = createAddressBook(owner);

        // THEN: The valid creation is delivered and the invalid message reaches the dead-letter queue.
        assertEventReceived(events, owner, book, ChangeType.CREATED);
        awaitAtMost.untilAsserted(() ->
            assertThat(channel.messageCount(AddressBookListNotificationConsumer.DEAD_LETTER_QUEUE))
                .isEqualTo(1));
    }

    private AddressBookURL createAddressBook(OpenPaaSUser owner) {
        AddressBookURL book = new AddressBookURL(owner.id(), UUID.randomUUID().toString());
        cardDavClient.createUserAddressBook(owner.username(), owner.id(),
            new NewAddressBook(book.addressBookId(), "Team", "Contacts"))
            .block();
        return book;
    }

    private AddressBookURL createCopy(OpenPaaSUser owner, AddressBookURL book, OpenPaaSUser subscriber, boolean delegated) {
        if (delegated) {
            cardDavClient.updateAddressBookShares(owner.username(), book,
                List.of(new AddressBookSharee("mailto:" + subscriber.username().asString(), DavRight.READ.access())))
                .block();
            return cardDavClient.listUserAddressBookUrls(subscriber.username(), subscriber.id(), MirrorAddressBooks.INCLUDE)
                .filter(url -> !BUILT_IN_ADDRESS_BOOK_IDS.contains(url.addressBookId()))
                .single()
                .block();
        }

        cardDavClient.updateAddressBookPublicRight(owner.username(), book, true).block();
        AddressBookURL copy = new AddressBookURL(subscriber.id(), UUID.randomUUID().toString());
        davTestHelper.createAddressBookSubscription(subscriber, copy.addressBookId(), "Public contacts", book).block();
        return copy;
    }

    private Queue<AddressBookListChangedEvent> registerListener(OpenPaaSUser user) {
        Queue<AddressBookListChangedEvent> events = new ConcurrentLinkedQueue<>();
        Mono.from(eventBus.register(event -> {
            if (event instanceof AddressBookListChangedEvent change) {
                events.add(change);
            }
        }, new UsernameRegistrationKey(user.username())))
            .block();
        return events;
    }

    private void assertEventReceived(Queue<AddressBookListChangedEvent> events, OpenPaaSUser user,
                                     AddressBookURL book, ChangeType changeType) {
        awaitAtMost.untilAsserted(() -> assertThat(events)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(user.username());
                assertThat(event.addressBookURL()).isEqualTo(book);
                assertThat(event.changeType()).isEqualTo(changeType);
            }));
    }
}
