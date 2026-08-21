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

package com.linagora.calendar.saas.contact;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Domain;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.TestFixture;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

import net.javacrumbs.jsonunit.core.Option;
import reactor.core.publisher.Mono;

class CommonContactNotificationConsumerTest {
    private static final String COMMON_CONTACT_EXCHANGE = "twake:contacts:common";
    private static final String CONTACT_CREATED_EXCHANGE = "sabre:contact:created";
    private static final String CONTACT_CREATED_DEAD_LETTER_QUEUE = "tcalendar:common-contact:created-dead-letter";
    private static final String ADDRESS_BOOK = "collected";
    private static final String DOMAIN_ADDRESS_BOOK = "dab";
    private static final ConditionFactory AWAIT_AT_MOST = Awaitility.with()
        .pollInterval(Duration.ofMillis(200))
        .pollDelay(Duration.ZERO)
        .await()
        .atMost(30, TimeUnit.SECONDS);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    private static SimpleConnectionPool connectionPool;
    private static ReactorRabbitMQChannelPool channelPool;
    private static Connection connection;
    private static Channel channel;

    private CardDavClient cardDavClient;
    private DavTestHelper davTestHelper;
    private CommonContactNotificationConsumer consumer;
    private CommonContactEventConverter converter;
    private List<JsonNode> commonContactEvents;
    private String queueName;
    private OpenPaaSUser user;
    private OpenPaaSUser delegate;

    @BeforeAll
    static void setUpRabbitMQ() throws Exception {
        RabbitMQConfiguration configuration = sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration();
        connectionPool = new SimpleConnectionPool(new RabbitMQConnectionFactory(configuration),
            SimpleConnectionPool.Configuration.builder().retries(2).initialDelay(Duration.ofMillis(5)));
        channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.builder()
                .retries(2)
                .maxBorrowDelay(Duration.ofMillis(250))
                .maxChannel(10),
            new RecordingMetricFactory(), new NoopGaugeRegistry());
        channelPool.start();
        connection = connectionPool.getResilientConnection().block();
        channel = connection.createChannel();
    }

    @AfterAll
    static void tearDownRabbitMQ() throws Exception {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = sabreDavExtension.davTestHelper();
        user = sabreDavExtension.newTestUser();
        delegate = sabreDavExtension.newTestUser();

        MongoDatabase mongoDatabase = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDatabase);
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(mongoDatabase, domainDAO);
        CommonContactPublisher publisher = new CommonContactPublisher(channelPool,
            new CommonContactPublisherConfiguration(COMMON_CONTACT_EXCHANGE));
        publisher.init();
        converter = new CommonContactEventConverter(userDAO, domainDAO);
        consumer = new CommonContactNotificationConsumer(channelPool, QueueArguments.Builder::new, publisher, converter);
        consumer.init();

        queueName = "tcalendar:common-contact:test:" + UUID.randomUUID();
        channel.queueDeclare(queueName, false, true, true, null);
        channel.queueBind(queueName, COMMON_CONTACT_EXCHANGE, "");
        commonContactEvents = new CopyOnWriteArrayList<>();
        channel.basicConsume(queueName, true,
            (_, delivery) -> commonContactEvents.add(OBJECT_MAPPER.readTree(delivery.getBody())),
            _ -> {
            });
    }

    @AfterEach
    void tearDown() throws Exception {
        consumer.close();
        channel.queueDelete(queueName);
    }

    @Test
    void shouldPublishCreatedContact() {
        // Given a user and an empty personal address book
        // When the user creates a contact through Sabre DAV
        String uid = UUID.randomUUID().toString();
        cardDavClient.createContact(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid),
            """
                BEGIN:VCARD
                VERSION:4.0
                UID:{uid}
                FN:Created contact
                EMAIL:contact@example.com
                END:VCARD
                """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();

        // Then the consumer republishes an ADD event to the common contacts exchange
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/uid").asText().equals(uid))
            .singleElement()
            .satisfies(event -> {
                assertThatJson(event.toString())
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .isEqualTo("""
                        {
                          "audience": { "user": "{user}" },
                          "action": "ADD",
                          "path": "addressbooks/{userId}/collected/{uid}.vcf",
                          "uid": "{uid}",
                          "payload": "${json-unit.ignore}"
                        }
                        """.replace("{user}", user.username().asString())
                        .replace("{userId}", user.id().value())
                        .replace("{uid}", uid));

                assertThatJson(event.toString())
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .as("Supports RFC 9553 JSContact payload")
                    .inPath("payload")
                    .isEqualTo("""
                          {
                            "@type": "Card",
                            "version": "2.0",
                            "uid": "{uid}",
                            "name": { "@type": "Name", "full": "Created contact" },
                            "emails": {
                              "EMAIL-1": { "@type": "EmailAddress", "address": "contact@example.com" }
                            },
                            "vCardProps": [["version", {}, "text", "4.0"]]
                          }
                        """.replace("{uid}", uid));
            }));
    }

    @Test
    void shouldPublishUpdatedContact() {
        // Given an existing contact, and its initial ADD event already consumed from the exchange
        String uid = UUID.randomUUID().toString();
        cardDavClient.createContact(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid),
            """
                BEGIN:VCARD
                VERSION:4.0
                UID:{uid}
                FN:Initial contact
                EMAIL:contact@example.com
                END:VCARD
                """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/uid").asText().equals(uid))
            .hasSize(1));

        // When the user updates that contact through Sabre DAV
        cardDavClient.createContact(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid), """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Updated contact
            EMAIL:contact@example.com
            END:VCARD
            """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();

        // Then the consumer republishes an UPDATE event
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("UPDATE")
                && event.at("/uid").asText().equals(uid))
            .singleElement()
            .satisfies(event -> {
                assertThatJson(event.toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                    {
                      "audience": { "user": "{user}" },
                      "action": "UPDATE",
                      "path": "addressbooks/{userId}/collected/{uid}.vcf",
                      "uid": "{uid}",
                      "payload": {
                        "@type": "Card",
                        "version": "2.0",
                        "uid": "{uid}",
                        "name": { "@type": "Name", "full": "Updated contact" },
                        "emails": {
                          "EMAIL-1": { "@type": "EmailAddress", "address": "contact@example.com" }
                        },
                        "vCardProps": [["version", {}, "text", "4.0"]]
                      }
                    }
                    """.replace("{user}", user.username().asString())
                    .replace("{userId}", user.id().value())
                    .replace("{uid}", uid));
            }));
    }

    @Test
    void shouldPublishDeletedContact() {
        // Given an existing contact, and its initial ADD event already consumed from the exchange
        String uid = UUID.randomUUID().toString();
        cardDavClient.createContact(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid), """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Deleted contact
            EMAIL:contact@example.com
            END:VCARD
            """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/uid").asText().equals(uid))
            .hasSize(1));

        // When the user deletes that contact through Sabre DAV
        cardDavClient.deleteContact(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid)).block();

        // Then the consumer emits one DELETE event for the original contact change
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("DELETE")
                && event.at("/uid").asText().equals(uid))
            .singleElement()
            .satisfies(event -> {
                assertThatJson(event.toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                    {
                      "audience": { "user": "{user}" },
                      "action": "DELETE",
                      "path": "addressbooks/{userId}/collected/{uid}.vcf",
                      "uid": "{uid}",
                      "payload": {
                        "@type": "Card",
                        "version": "2.0",
                        "uid": "{uid}",
                        "name": { "@type": "Name", "full": "Deleted contact" },
                        "emails": {
                          "EMAIL-1": { "@type": "EmailAddress", "address": "contact@example.com" }
                        },
                        "vCardProps": [["version", {}, "text", "4.0"]]
                      }
                    }
                    """.replace("{user}", user.username().asString())
                    .replace("{userId}", user.id().value())
                    .replace("{uid}", uid));
            }));
    }

    @Test
    void shouldUseOwnerAsAudienceForDelegatedUpdate() {
        // Given an owner who shared their address book with a delegate, and an existing contact
        String uid = UUID.randomUUID().toString();
        cardDavClient.updateAddressBookShares(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK),
            List.of(new CardDavClient.AddressBookSharee("mailto:" + delegate.username().asString(), 3))).block();
        cardDavClient.createContact(user.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid), """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Initial contact
            EMAIL:contact@example.com
            END:VCARD
            """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();
        String ownerPath = "addressbooks/" + user.id().value() + "/" + ADDRESS_BOOK + "/" + uid + ".vcf";
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/path").asText().equals(ownerPath)
                && event.at("/uid").asText().equals(uid))
            .hasSize(1));

        // When the delegate updates the owner's contact through Sabre DAV
        cardDavClient.createContact(delegate.username(), new AddressBookURL(user.id(), ADDRESS_BOOK), new ContactUid(uid), """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Updated by delegate
            EMAIL:contact@example.com
            END:VCARD
            """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();

        // Then the event keeps the owner's path and identifies its owner as the audience
        // The subscribed copy has the same action and uid, so path identifies the owner's contact.
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("UPDATE")
                && event.at("/path").asText().equals(ownerPath)
                && event.at("/uid").asText().equals(uid))
            .singleElement()
            .satisfies(event -> {
                assertThatJson(event.toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                    {
                      "audience": { "user": "{owner}" },
                      "action": "UPDATE",
                      "path": "addressbooks/{ownerId}/collected/{uid}.vcf",
                      "uid": "{uid}",
                      "payload": {
                        "@type": "Card",
                        "version": "2.0",
                        "uid": "{uid}",
                        "name": { "@type": "Name", "full": "Updated by delegate" },
                        "emails": {
                          "EMAIL-1": { "@type": "EmailAddress", "address": "contact@example.com" }
                        },
                        "vCardProps": [["version", {}, "text", "4.0"]]
                      }
                    }
                    """.replace("{owner}", user.username().asString())
                    .replace("{ownerId}", user.id().value())
                    .replace("{uid}", uid));
            }));
    }

    @Test
    void shouldPublishDomainAudienceForTechnicalToken() {
        // Given a domain address book modified using a technical token
        OpenPaaSDomain domain = sabreDavExtension.dockerSabreDavSetup().getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of(SabreDavProvisioningService.DOMAIN))
            .block();
        String uid = UUID.randomUUID().toString();

        // When the technical token creates a domain contact through Sabre DAV
        cardDavClient.upsertContactDomainMembers(domain.id(), new ContactUid(uid), """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Technical contact
            EMAIL:contact@example.com
            END:VCARD
            """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();

        // Then the event is published with the domain resolved from its owner
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/uid").asText().equals(uid))
            .singleElement()
            .satisfies(event -> assertThatJson(event.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "audience": { "domain": "{domain}" },
                      "action": "ADD",
                      "path": "addressbooks/{domainId}/domain-members/{uid}.vcf",
                      "uid": "{uid}",
                      "payload": {
                        "@type": "Card",
                        "version": "2.0",
                        "uid": "{uid}",
                        "name": { "@type": "Name", "full": "Technical contact" },
                        "emails": {
                          "EMAIL-1": { "@type": "EmailAddress", "address": "contact@example.com" }
                        },
                        "vCardProps": [["version", {}, "text", "4.0"]]
                      }
                    }
                    """.replace("{domain}", domain.domain().asString())
                    .replace("{domainId}", domain.id().value())
                    .replace("{uid}", uid))));
    }

    @Test
    void shouldPublishDomainAudienceWhenUserActsOnDomainAddressBook() {
        // Given Bob is a domain administrator and the domain address book exists
        OpenPaaSDomain domain = sabreDavExtension.dockerSabreDavSetup().getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of(SabreDavProvisioningService.DOMAIN))
            .block();
        new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB())
            .addAdmins(domain.id(), List.of(user.id()))
            .block();
        davTestHelper.createDomainAddressBook(domain.id()).block();

        // When Bob directly creates a contact in that domain address book
        String uid = UUID.randomUUID().toString();
        cardDavClient.createContact(user.username(), new AddressBookURL(domain.id(), DOMAIN_ADDRESS_BOOK), new ContactUid(uid), """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Contact added by Bob
            EMAIL:contact@example.com
            END:VCARD
            """.replace("{uid}", uid).getBytes(StandardCharsets.UTF_8)).block();

        // Then the event targets the domain rather than Bob as an individual user
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/uid").asText().equals(uid))
            .singleElement()
            .satisfies(event -> assertThatJson(event.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "audience": { "domain": "{domain}" },
                      "action": "ADD",
                      "path": "addressbooks/{domainId}/dab/{uid}.vcf",
                      "uid": "{uid}",
                      "payload": "${json-unit.ignore}"
                    }
                    """.replace("{domain}", domain.domain().asString())
                    .replace("{domainId}", domain.id().value())
                    .replace("{uid}", uid))));
    }

    @Test
    void shouldDeadLetterMalformedContactNotification() throws IOException {
        // Given a malformed vCard received on Sabre's contact-created exchange
        String uid = UUID.randomUUID().toString();
        String notification = """
            {
              "path": "addressbooks/{userId}/collected/{uid}.vcf",
              "owner": "principals/users/{userId}",
              "carddata": "not a vCard"
            }
            """.replace("{userId}", user.id().value())
            .replace("{uid}", uid);

        // When the consumer receives the malformed notification
        publishToSabreContactExchange(notification);

        // Then the source message is dead-lettered instead of being acknowledged
        AWAIT_AT_MOST.untilAsserted(() -> {
            GetResponse deadLetter = channel.basicGet(CONTACT_CREATED_DEAD_LETTER_QUEUE, true);
            assertThat(deadLetter).isNotNull();
            assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8)).isEqualTo(notification);
        });
    }

    @Test
    void shouldDeadLetterContactNotificationWhenUserOwnerCannotBeResolved() throws IOException {
        // Given a valid contact notification whose owner no longer exists
        String uid = UUID.randomUUID().toString();
        String unknownUserId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String notification = """
            {
              "path": "addressbooks/{userId}/collected/{uid}.vcf",
              "owner": "principals/users/{unknownUserId}",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:{uid}\\nFN:Unknown owner\\nEND:VCARD\\n"
            }
            """.replace("{userId}", user.id().value())
            .replace("{unknownUserId}", unknownUserId)
            .replace("{uid}", uid);

        // When the consumer cannot resolve the user owner
        publishToSabreContactExchange(notification);

        // Then the source message is dead-lettered instead of inferring an audience
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(commonContactEvents)
            .filteredOn(event -> event.at("/action").asText().equals("ADD")
                && event.at("/uid").asText().equals(uid))
            .isEmpty());
        AWAIT_AT_MOST.untilAsserted(() -> {
            GetResponse deadLetter = channel.basicGet(CONTACT_CREATED_DEAD_LETTER_QUEUE, true);
            assertThat(deadLetter).isNotNull();
            assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8)).isEqualTo(notification);
        });
    }

    @Test
    void shouldDeadLetterContactNotificationWhenCommonContactPublisherFails() throws IOException {
        // Given the Common Contacts publisher cannot publish the normalized event
        CommonContactPublisher failingPublisher = new CommonContactPublisher(channelPool,
            new CommonContactPublisherConfiguration(COMMON_CONTACT_EXCHANGE)) {
            @Override
            public Mono<Void> publish(CommonContactOutboundEvent event) {
                return Mono.error(new RuntimeException("Publication failure"));
            }
        };
        consumer.close();
        consumer = new CommonContactNotificationConsumer(channelPool, QueueArguments.Builder::new, failingPublisher, converter);
        consumer.init();
        String uid = UUID.randomUUID().toString();
        String notification = """
            {
              "path": "addressbooks/{userId}/collected/{uid}.vcf",
              "owner": "principals/users/{userId}",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:{uid}\\nFN:Publication failure\\nEND:VCARD\\n"
            }
            """.replace("{userId}", user.id().value())
            .replace("{uid}", uid);

        // When the consumer receives a contact notification
        publishToSabreContactExchange(notification);

        // Then it dead-letters the source notification for later investigation
        AWAIT_AT_MOST.untilAsserted(() -> {
            GetResponse deadLetter = channel.basicGet(CONTACT_CREATED_DEAD_LETTER_QUEUE, true);
            assertThat(deadLetter).isNotNull();
            assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8)).isEqualTo(notification);
        });
    }

    private void publishToSabreContactExchange(String notification) throws IOException {
        channel.basicPublish(CONTACT_CREATED_EXCHANGE, "", null, notification.getBytes(StandardCharsets.UTF_8));
    }

}
