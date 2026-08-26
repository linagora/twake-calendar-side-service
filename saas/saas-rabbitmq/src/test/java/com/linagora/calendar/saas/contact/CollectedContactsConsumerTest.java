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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.hash.Hashing;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

class CollectedContactsConsumerTest {
    private static final String COLLECTED_ADDRESS_BOOK = "collected";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ConditionFactory AWAIT_AT_MOST = Awaitility.with()
        .pollInterval(Duration.ofMillis(200))
        .pollDelay(Duration.ZERO)
        .await()
        .atMost(30, TimeUnit.SECONDS);

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    private static SimpleConnectionPool connectionPool;
    private static ReactorRabbitMQChannelPool channelPool;
    private static Connection connection;
    private static Channel channel;

    private CollectedContactsConsumer testee;
    private CardDavClient cardDavClient;
    private OpenPaaSUser user;

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
        RabbitMQConfiguration rabbitMQConfiguration = sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration();
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        user = sabreDavExtension.newTestUser();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB());
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), domainDAO);
        testee = new CollectedContactsConsumer(channelPool, rabbitMQConfiguration,
            new TWPCommonRabbitMQConfiguration(Optional.empty(), Optional.empty(), true),
            userDAO, cardDavClient, new CollectedContactUpdateCalculator());
        testee.init();
        channel.queuePurge(CollectedContactsConsumer.QUEUE);
        channel.queuePurge(CollectedContactsConsumer.DEAD_LETTER_QUEUE);
    }

    @AfterEach
    void tearDown() {
        testee.close();
    }

    @Test
    void shouldStoreCollectedContactInUserCollectedAddressBook() throws Exception {
        // Given an inbound AMQP message containing one contact collected for a known user
        String message = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "uid": "stable-uid",
                "name": { "@type": "Name", "full": "Collected contact" },
                "emails": {
                  "main": { "@type": "EmailAddress", "address": "contact@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());

        // When an external application publishes it to the collected contacts exchange
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, message.getBytes(StandardCharsets.UTF_8));

        // Then the contact is stored in that user's Collected address book
        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:stable-uid
                    FN:Collected contact
                    EMAIL;PROP-ID=main:contact@example.com
                    END:VCARD
                    """)));
    }

    @Test
    void shouldStoreSeveralCollectedContactsInUserCollectedAddressBook() throws Exception {
        // Given an inbound AMQP message containing two contacts collected for a known user
        String message = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [
                {
                  "@type": "Card",
                  "version": "2.0",
                  "uid": "alice-uid",
                  "name": { "@type": "Name", "full": "Alice" },
                  "emails": {
                    "main": { "@type": "EmailAddress", "address": "alice@example.com" }
                  }
                },
                {
                  "@type": "Card",
                  "version": "2.0",
                  "uid": "bob-uid",
                  "name": { "@type": "Name", "full": "Bob" },
                  "emails": {
                    "main": { "@type": "EmailAddress", "address": "bob@example.com" }
                  }
                }
              ]
            }
            """.replace("{userEmail}", user.username().asString());

        // When an external application publishes it to the collected contacts exchange
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, message.getBytes(StandardCharsets.UTF_8));

        // Then all contacts are stored in that user's Collected address book
        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:alice-uid
                    FN:Alice
                    EMAIL;PROP-ID=main:alice@example.com
                    END:VCARD
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:bob-uid
                    FN:Bob
                    EMAIL;PROP-ID=main:bob@example.com
                    END:VCARD
                    """)));
    }

    @Test
    void shouldStoreContactWithSeveralCommunicationMethods() throws Exception {
        // Given one contact containing two emails, two phone numbers and one Matrix ID
        String message = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "uid": "complex-uid",
                "name": { "@type": "Name", "full": "Complex contact" },
                "emails": {
                  "personal": { "@type": "EmailAddress", "address": "personal@example.com" },
                  "work": { "@type": "EmailAddress", "address": "work@example.com" }
                },
                "phones": {
                  "mobile": { "@type": "Phone", "number": "+33612345678" },
                  "office": { "@type": "Phone", "number": "+33123456789" }
                },
                "onlineServices": {
                  "matrix": {
                    "@type": "OnlineService",
                    "service": "matrix",
                    "user": "@alice:matrix.example.com"
                  }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());

        // When the contact is collected
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, message.getBytes(StandardCharsets.UTF_8));

        // Then every communication method is persisted in the vCard
        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:complex-uid
                    FN:Complex contact
                    TEL;PROP-ID=mobile:+33612345678
                    TEL;PROP-ID=office:+33123456789
                    EMAIL;PROP-ID=personal:personal@example.com
                    EMAIL;PROP-ID=work:work@example.com
                    SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@alice:matrix.e
                     xample.com
                    END:VCARD
                    """)));
    }

    @Test
    void shouldUpdateExistingContactWithSameUid() throws Exception {
        // Given a collected contact already stored with a stable UID
        String initialMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "uid": "stable-uid",
                "name": { "@type": "Name", "full": "Initial name" },
                "emails": {
                  "main": { "@type": "EmailAddress", "address": "contact@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, initialMessage.getBytes(StandardCharsets.UTF_8));

        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:stable-uid
                    FN:Initial name
                    EMAIL;PROP-ID=main:contact@example.com
                    END:VCARD
                    """)));

        // When another message contains the same UID with changed contact data
        String updatedMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "uid": "stable-uid",
                "name": { "@type": "Name", "full": "Updated name" },
                "emails": {
                  "main": { "@type": "EmailAddress", "address": "contact@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, updatedMessage.getBytes(StandardCharsets.UTF_8));

        // Then the existing vCard is updated rather than duplicated
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:stable-uid
                    FN:Updated name
                    EMAIL;PROP-ID=main:contact@example.com
                    END:VCARD
                    """)));
    }

    @Test
    void shouldMergeEmailAndMatrixIdWhenTheyGenerateTheSameUid() throws Exception {
        // Given a collected contact identified by its email
        String emailMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "name": { "@type": "Name", "full": "Bob" },
                "emails": {
                  "email": { "@type": "EmailAddress", "address": "bob@domain.tld" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, emailMessage.getBytes(StandardCharsets.UTF_8));

        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .contains("EMAIL;PROP-ID=email:bob@domain.tld")));

        // When a Matrix ID-only message resolves to the same UID
        String matrixIdMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "onlineServices": {
                  "matrix": { "@type": "OnlineService", "service": "matrix", "user": "@bob:domain.tld" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, matrixIdMessage.getBytes(StandardCharsets.UTF_8));

        // Then both identities are kept on the same collected contact
        String expectedVCard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:{uid}
            FN:Bob
            EMAIL;PROP-ID=email:bob@domain.tld
            SOCIALPROFILE;SERVICE-TYPE=matrix;PROP-ID=matrix;VALUE=text:@bob:domain.tld
            END:VCARD
            """.replace("{uid}", sha1("bob@domain.tld"));
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToIgnoringWhitespace(expectedVCard)));
    }

    @Test
    void shouldReplaceExistingContactWhenTwoMessagesHaveTheSameEmail() throws Exception {
        // Given a collected contact with two email addresses
        String initialMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "name": { "@type": "Name", "full": "Initial name" },
                "emails": {
                  "common": { "@type": "EmailAddress", "address": "abc@example.com" },
                  "old": { "@type": "EmailAddress", "address": "xyz@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, initialMessage.getBytes(StandardCharsets.UTF_8));

        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .contains("EMAIL;PROP-ID=old:xyz@example.com")));

        // When a later message has the same UID-generating email but different contact data
        String updatedMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "name": { "@type": "Name", "full": "Updated name" },
                "emails": {
                  "common": { "@type": "EmailAddress", "address": "abc@example.com" },
                  "new": { "@type": "EmailAddress", "address": "klm@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, updatedMessage.getBytes(StandardCharsets.UTF_8));

        // Then the existing contact is replaced rather than merging both email lists
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .containsOnlyOnce("BEGIN:VCARD")
                .contains("FN:Updated name",
                    "EMAIL;PROP-ID=common:abc@example.com",
                    "EMAIL;PROP-ID=new:klm@example.com")
                .doesNotContain("Initial name", "xyz@example.com")));
    }

    @Test
    void shouldUpdateExistingContactWithoutUidWhenEmailIsUnchanged() throws Exception {
        // Given a collected contact without UID, whose UID is generated from its email
        String initialMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "name": { "@type": "Name", "full": "Initial name" },
                "emails": {
                  "main": { "@type": "EmailAddress", "address": "stable@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, initialMessage.getBytes(StandardCharsets.UTF_8));

        String generatedUid = sha1("stable@example.com");
        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:{uid}
                    FN:Initial name
                    EMAIL;PROP-ID=main:stable@example.com
                    END:VCARD
                    """.replace("{uid}", generatedUid))));

        // When a later message omits UID again but keeps the same email
        String updatedMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "name": { "@type": "Name", "full": "Updated name" },
                "emails": {
                  "main": { "@type": "EmailAddress", "address": "stable@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, updatedMessage.getBytes(StandardCharsets.UTF_8));

        // Then the same generated UID targets and updates the existing vCard
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:{uid}
                    FN:Updated name
                    EMAIL;PROP-ID=main:stable@example.com
                    END:VCARD
                    """.replace("{uid}", generatedUid))));
    }

    @Test
    void shouldIgnoreMessageWhenUserDoesNotExist() throws Exception {
        CollectedContactsDTO message = new CollectedContactsDTO("unknown@example.com", List.of(
            (ObjectNode) OBJECT_MAPPER.readTree("""
                {
                  "@type": "Card",
                  "version": "2.0",
                  "uid": "ignored-uid",
                  "name": { "@type": "Name", "full": "Ignored contact" },
                  "emails": {
                    "main": { "@type": "EmailAddress", "address": "ignored@example.com" }
                  }
                }
                """)));

        testee.handle(message).block();
    }

    @Test
    void shouldDeadLetterMalformedMessage() throws Exception {
        byte[] malformedMessage = "not-json".getBytes(StandardCharsets.UTF_8);

        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, malformedMessage);

        AWAIT_AT_MOST.untilAsserted(() -> {
            GetResponse deadLetter = channel.basicGet(CollectedContactsConsumer.DEAD_LETTER_QUEUE, true);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getBody()).isEqualTo(malformedMessage);
        });
    }

    @Test
    void shouldContinueConsumingAfterMessageFailure() throws Exception {
        // Given a malformed message that fails and is moved to the dead-letter queue
        byte[] malformedMessage = "not-json".getBytes(StandardCharsets.UTF_8);
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, malformedMessage);
        AWAIT_AT_MOST.untilAsserted(() -> {
            GetResponse deadLetter = channel.basicGet(CollectedContactsConsumer.DEAD_LETTER_QUEUE, true);
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.getBody()).isEqualTo(malformedMessage);
        });

        // When a valid message is published afterward
        String validMessage = """
            {
              "userEmail": "{userEmail}",
              "collectedContacts": [{
                "@type": "Card",
                "version": "2.0",
                "uid": "stable-uid",
                "name": { "@type": "Name", "full": "Contact after failure" },
                "emails": {
                  "main": { "@type": "EmailAddress", "address": "contact@example.com" }
                }
              }]
            }
            """.replace("{userEmail}", user.username().asString());
        channel.basicPublish(CollectedContactsConsumer.EXCHANGE, "", null, validMessage.getBytes(StandardCharsets.UTF_8));

        // Then the consumer is still running and stores the contact
        AddressBookURL addressBook = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        AWAIT_AT_MOST.untilAsserted(() -> assertThat(cardDavClient.exportContact(user.username(), addressBook).blockOptional())
            .hasValueSatisfying(contacts -> assertThat(contacts).asString(StandardCharsets.UTF_8)
                .isEqualToNormalizingNewlines("""
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:stable-uid
                    FN:Contact after failure
                    EMAIL;PROP-ID=main:contact@example.com
                    END:VCARD
                    """)));
    }

    @SuppressWarnings("deprecation")
    private static String sha1(String value) {
        return Hashing.sha1()
            .hashString(value, StandardCharsets.UTF_8)
            .toString();
    }

}
