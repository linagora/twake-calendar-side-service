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

import static com.linagora.calendar.amqp.TestFixture.awaitAtMost;
import static com.linagora.calendar.amqp.TestFixture.extractSubject;
import static com.linagora.calendar.amqp.TestFixture.toMessage;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.util.Port;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.AddressBookPropertiesUpdate;
import com.linagora.calendar.dav.CardDavClient.AddressBookSharee;
import com.linagora.calendar.dav.CardDavClient.MirrorAddressBooks;
import com.linagora.calendar.dav.CardDavClient.NewAddressBook;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.rabbitmq.client.Channel;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

public class AddressBookDelegatedNotificationConsumerTest {
    private static final int ACCESS_NOACCESS = 4;
    private static final String MAILTO_PREFIX = "mailto:";
    private static final String SENDER_ADDRESS = "no-reply@twake.app";
    private static final String CONTACTS_URL = "https://contacts.example.org/";
    private static final String DEFAULT_ADDRESS_BOOK_DAV_NAME = "#default";
    private static final List<String> BUILT_IN_ADDRESS_BOOK_IDS = List.of("contacts", "collected", "domain-members");

    private static final ConditionFactory negativeAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(200))
        .pollDelay(Duration.ZERO)
        .await()
        .during(Duration.ofSeconds(2));

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    private static final SettingsBasedResolver settingsResolver = mock(SettingsBasedResolver.class);
    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;
    private static Channel channel;

    private CardDavClient cardDavClient;
    private AddressBookURL source;
    private AddressBookDelegatedNotificationConsumer consumer;

    private OpenPaaSUser bob;
    private OpenPaaSUser alice;

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
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    public void setUp() throws Exception {
        cardDavClient = new CardDavClient(
            sabreDavExtension.dockerSabreDavSetup().davConfiguration(),
            TECHNICAL_TOKEN_SERVICE_TESTING);
        bob = sabreDavExtension.newTestUser(Optional.of("Bob"));
        alice = sabreDavExtension.newTestUser(Optional.of("Alice"));
        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(LANGUAGE_IDENTIFIER, Locale.ENGLISH))));
        setupConsumer();
        source = new AddressBookURL(alice.id(), UUID.randomUUID().toString());
        cardDavClient.createUserAddressBook(alice.username(), alice.id(),
            new NewAddressBook(source.addressBookId(), "Alice Team", "Shared contacts"))
            .block();
    }

    @AfterEach
    void afterEach() {
        if (consumer != null) {
            consumer.close();
        }

        Mockito.reset(settingsResolver);
    }

    private void setupConsumer() throws Exception {
        MailSenderConfiguration mailSenderConfiguration = new MailSenderConfiguration(
            "localhost", Port.of(mockSmtpExtension.getMockSmtp().getSmtpPort()),
            "localhost", Optional.empty(), Optional.empty(),
            false, false, false);

        MailSender.Factory mailSenderFactory = new MailSender.Factory.Default(
            mailSenderConfiguration, EventEmailFilter.acceptAll());
        FileSystemImpl fileSystem = FileSystemImpl.forTesting();
        Path templateDirectory = Path.of("..").toAbsolutePath().resolve("app/src/main/resources/templates");

        MailTemplateConfiguration mailTemplateConfig = new MailTemplateConfiguration(
            "file://" + templateDirectory.toAbsolutePath(),
            MaybeSender.getMailSender(SENDER_ADDRESS));

        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        MessageGenerator.Factory messageFactory = MessageGenerator.factory(
            mailTemplateConfig, fileSystem, openPaaSUserDAO);

        DelegatedAddressBookNotificationHandler notificationHandler = new DelegatedAddressBookNotificationHandler(
            openPaaSUserDAO, cardDavClient, settingsResolver, mailSenderFactory, mailTemplateConfig, messageFactory,
            EventEmailFilter.acceptAll(), URI.create(CONTACTS_URL).toURL(), new byte[0]);

        consumer = new AddressBookDelegatedNotificationConsumer(
            channelPool, QueueArguments.Builder::new, notificationHandler);
        consumer.init();
    }

    private JsonPath smtpMails() {
        return given(mockSMTPRequestSpecification()).get("/smtpMails").jsonPath();
    }

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    static Stream<Arguments> newDelegationCases() {
        return Stream.of(
            // No built-in ID means Alice's custom source, tested with each supported access level.
            Arguments.of(Optional.empty(), DavRight.READ, "Alice Team"),
            Arguments.of(Optional.empty(), DavRight.READ_WRITE, "Alice Team"),
            Arguments.of(Optional.empty(), DavRight.ADMINISTRATION, "Alice Team"),
            // Built-in books have no display name, so the email uses the readable default name.
            Arguments.of(Optional.of("contacts"), DavRight.READ, "{owner}'s address book"),
            Arguments.of(Optional.of("collected"), DavRight.READ, "{owner}'s address book"));
    }

    @ParameterizedTest(name = "built-in book={0}, access={1}")
    @MethodSource("newDelegationCases")
    void shouldEmailNewDelegate(Optional<String> builtInAddressBookId, DavRight right, String expectedAddressBookName) throws Exception {
        // Roles: Alice owns the custom or built-in source; Bob receives a new delegation.
        AddressBookURL delegatedSource = builtInAddressBookId
            .map(addressBookId -> new AddressBookURL(alice.id(), addressBookId))
            .orElse(source);
        cardDavClient.updateAddressBookShares(alice.username(), delegatedSource,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), right.access())))
            .block();

        // Bob receives exactly one email naming Alice and the custom or translated default book name.
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        JsonPath mails = smtpMails();
        assertThat(mails.getString("[0].from")).isEqualTo(SENDER_ADDRESS);
        assertThat(mails.getList("[0].recipients.address", String.class))
            .containsExactly(bob.username().asString());
        assertThat(extractSubject(mails.getString("[0].message")))
            .isEqualTo("{owner} shared the “{addressBookName}” address book with you"
                .replace("{addressBookName}", expectedAddressBookName)
                .replace("{owner}", alice.fullName()));

        Message message = toMessage(mails.getString("[0].message"));
        Multipart mixed = (Multipart) message.getBody();
        assertThat(mixed.getBodyParts()).extracting(Entity::getMimeType)
            .containsExactly("multipart/alternative");
        Multipart alternative = (Multipart) mixed.getBodyParts().getFirst().getBody();
        assertThat(alternative.getBodyParts()).extracting(Entity::getMimeType)
            .containsExactly("text/plain", "multipart/related");
        Multipart related = (Multipart) alternative.getBodyParts().get(1).getBody();
        assertThat(related.getBodyParts()).extracting(Entity::getMimeType)
            .containsExactly("text/html", "image/png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", DEFAULT_ADDRESS_BOOK_DAV_NAME})
    void shouldUseDefaultDisplayNameWhenAddressBookNameIsBlankOrDefault(String addressBookName) throws Exception {
        // Create legacy data directly in DAV: property updates reject blank names.
        source = new AddressBookURL(alice.id(), UUID.randomUUID().toString());
        cardDavClient.createUserAddressBook(alice.username(), alice.id(),
            new NewAddressBook(source.addressBookId(), addressBookName, "Shared contacts"))
            .block();
        assertThat(cardDavClient.fetchAddressBookMetadata(alice.username(), source).block().path("dav:name").asText())
            .isEqualTo(addressBookName);
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();

        // Bob sees the translated default name rather than a blank name or the internal marker.
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        assertThat(smtpMails().getList("[0].recipients.address", String.class))
            .containsExactly(bob.username().asString());
        assertThat(extractSubject(smtpMails().getString("[0].message")))
            .isEqualTo("{owner} shared the “{owner}'s address book” address book with you"
                .replace("{owner}", alice.fullName()));
    }

    @Test
    void shouldLocalizeDefaultAddressBookNameUsingRecipientLocale() throws Exception {
        // Roles: Alice owns the source named with the internal marker; Bob is a French-speaking sharee.
        when(settingsResolver.resolveOrDefault(bob.username()))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(LANGUAGE_IDENTIFIER, Locale.FRENCH))));
        cardDavClient.updateAddressBookProperties(alice.username(), source,
            new AddressBookPropertiesUpdate(Optional.of(DEFAULT_ADDRESS_BOOK_DAV_NAME), Optional.empty()))
            .block();
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();

        // Both the subject and its fallback address book name use Bob's locale.
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        assertThat(smtpMails().getList("[0].recipients.address", String.class))
            .containsExactly(bob.username().asString());
        assertThat(extractSubject(smtpMails().getString("[0].message")))
            .isEqualTo("{owner} a partagé le carnet d’adresses « Carnet d’adresses de {owner} » avec vous"
                .replace("{owner}", alice.fullName()));
    }

    @Test
    void shouldUseRecipientLocale() throws Exception {
        // Roles: Alice owns the source; Bob is a French-speaking sharee.
        when(settingsResolver.resolveOrDefault(bob.username()))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(LANGUAGE_IDENTIFIER, Locale.FRENCH))));

        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        assertThat(extractSubject(smtpMails().getString("[0].message")))
            .isEqualTo("{owner} a partagé le carnet d’adresses « Alice Team » avec vous"
                .replace("{owner}", alice.fullName()));
    }

    @Test
    void adminDelegateShouldEmailNewShareeWithOriginalOwnerName() throws Exception {
        // Roles: Alice owns the source; Bob is its ADMIN delegate; Cedric is the new sharee.
        OpenPaaSUser cedric = sabreDavExtension.newTestUser(Optional.of("Cedric"));
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.ADMINISTRATION.access())))
            .block();
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        mockSmtpExtension.clear();
        AddressBookURL bobCopy = cardDavClient.listUserAddressBookUrls(
                bob.username(), bob.id(), MirrorAddressBooks.INCLUDE)
            .filter(url -> !BUILT_IN_ADDRESS_BOOK_IDS.contains(url.addressBookId()))
            .next()
            .block();

        // When Bob grants Cedric READ through his copy of Alice's source.
        cardDavClient.updateAddressBookShares(bob.username(), bobCopy,
            List.of(new AddressBookSharee(MAILTO_PREFIX + cedric.username().asString(), DavRight.READ.access())))
            .block();

        // Then only Cedric receives the new email, identifying Alice as the source owner.
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        assertThat(smtpMails().getList("[0].recipients.address", String.class))
            .containsExactly(cedric.username().asString());
        assertThat(extractSubject(smtpMails().getString("[0].message")))
            .isEqualTo("{owner} shared the “Alice Team” address book with you"
                .replace("{owner}", alice.fullName()));
    }

    @Test
    void shouldNotEmailPublicSubscriber() {
        // Roles: Alice owns a public source; Bob subscribes himself, without receiving a delegation.
        cardDavClient.updateAddressBookPublicRight(alice.username(), source, true).block();
        davTestHelper.createAddressBookSubscription(bob, UUID.randomUUID().toString(), "Alice Public", source).block();

        // The same created exchange carries this subscription, but it must not trigger delegation email.
        negativeAwait.untilAsserted(() -> assertThat(smtpMails().getList("")).isEmpty());
    }

    @Test
    void restoringOwnDelegatedCopyShouldNotSendAnotherEmail() {
        // Roles: Alice owns the source; Bob already has READ delegation and received its email.
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
        AddressBookURL bobCopy = cardDavClient.listUserAddressBookUrls(
                bob.username(), bob.id(), MirrorAddressBooks.INCLUDE)
            .filter(url -> !BUILT_IN_ADDRESS_BOOK_IDS.contains(url.addressBookId()))
            .next()
            .block();

        // When Bob unsubscribes and restores his copy himself, no new delegation is granted.
        cardDavClient.deleteUserAddressBook(bob.username(), bobCopy).block();
        davTestHelper.createAddressBookSubscription(bob, UUID.randomUUID().toString(), "Restored copy", source).block();

        // A created event for the restored copy must not send Bob a second invitation email.
        negativeAwait.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
    }

    @Test
    void shouldNotEmailWhenRightsChangeOrDelegationIsRevoked() {
        // Roles: Alice owns the source; Bob already received the email for his READ delegation.
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();
        awaitAtMost.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));

        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ_WRITE.access())))
            .block();
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), ACCESS_NOACCESS)))
            .block();

        // Match calendar behavior: no additional email on an update or revocation.
        negativeAwait.untilAsserted(() -> assertThat(smtpMails().getList("")).hasSize(1));
    }

    @Test
    void invalidPayloadShouldReachDeadLetterQueueWithoutStoppingConsumer() throws Exception {
        // Roles: Alice owns the source; Bob receives a real delegation after an invalid AMQP message.
        // The invalid message is dead-lettered without preventing Bob from receiving his email.
        channel.basicPublish("", AddressBookDelegatedNotificationConsumer.QUEUE, null,
            "not json".getBytes(StandardCharsets.UTF_8));
        cardDavClient.updateAddressBookShares(alice.username(), source,
            List.of(new AddressBookSharee(MAILTO_PREFIX + bob.username().asString(), DavRight.READ.access())))
            .block();

        awaitAtMost.untilAsserted(() -> {
            assertThat(channel.messageCount(AddressBookDelegatedNotificationConsumer.DEAD_LETTER_QUEUE)).isEqualTo(1);
            assertThat(smtpMails().getList("")).hasSize(1);
        });
    }
}
