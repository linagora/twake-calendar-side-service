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

import static com.linagora.calendar.amqp.CalendarAmqpModule.DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentMatchers;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.OpenPaaSId;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class ItipLocalDeliveryConsumerTest {

    private static final ConditionFactory AWAIT_AT_MOST = Awaitility.with()
        .pollInterval(Duration.ofMillis(200))
        .pollDelay(Duration.ofMillis(200))
        .await()
        .atMost(20, TimeUnit.SECONDS);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String CEDRIC = "cedric@example.com";
    private static final String EVENT_UID = "abc-123-def-456";
    private static final String CALENDAR_ID = "my-calendar-id";
    private static final String LOCAL_USER_ID = "60a7b2c3d4e5f6a7b8c9d0e1";
    private static final String RESOURCE_ID = "resource-room-01";
    private static final String SIMPLE_ICAL = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:{UID}
        SUMMARY:Test Meeting
        DTSTART;TZID=Europe/Paris:20260401T100000
        DTEND;TZID=Europe/Paris:20260401T110000
        ORGANIZER:mailto:{ORGANIZER}
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{ATTENDEE}
        END:VEVENT
        END:VCALENDAR
        """
        .replace("{UID}", EVENT_UID)
        .replace("{ORGANIZER}", BOB)
        .replace("{ATTENDEE}", ALICE);

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.WEAK);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static WireMockServer wireMockServer;

    private ItipLocalDeliveryConsumer consumer;
    private LocalRecipientResolver localRecipientResolver;
    private Channel channel;

    private String testFanoutQueue;
    private String testEmailQueue;

    @BeforeAll
    static void beforeAll() throws Exception {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        RabbitMQConfiguration rabbitMQConfiguration = rabbitMQExtension.getRabbitMQ().getConfiguration();
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
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
        wireMockServer.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer.resetAll();

        String testId = String.valueOf(System.nanoTime());
        testFanoutQueue = "test:itip:localDelivery:fanout:" + testId;
        testEmailQueue = "test:event:notificationEmail:" + testId;

        localRecipientResolver = mock(LocalRecipientResolver.class);
        when(localRecipientResolver.resolve(ArgumentMatchers.any(Username.class))).thenReturn(Mono.just(Optional.empty()));

        DavConfiguration davConfiguration = new DavConfiguration(
            new UsernamePasswordCredentials("admin", "secret"),
            URI.create("http://localhost:" + wireMockServer.port()),
            Optional.of(true),
            Optional.empty(),
            Optional.empty());

        CalDavClient calDavClient = new CalDavClient(davConfiguration, TECHNICAL_TOKEN_SERVICE_TESTING);

        consumer = new ItipLocalDeliveryConsumer(
            channelPool,
            QueueArguments.Builder::new,
            calDavClient,
            localRecipientResolver,
            DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT);

        consumer.init();
        declareExchange(EventEmailConsumer.EXCHANGE_NAME);

        Connection connection = connectionPool.getResilientConnection().block();
        channel = connection.createChannel();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    @Test
    void shouldFanOutOneMessagePerRecipientWhenRecipientsSizeGreaterThanOne() {
        declareQueueBoundToExchange(ItipLocalDeliveryConsumer.EXCHANGE_NAME, testFanoutQueue);
        List<JsonNode> receivedMessages = consumeJsonMessages(testFanoutQueue);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s", "mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE, CEDRIC);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            List<String> recipients = receivedMessages.stream()
                .filter(node -> node.at("/recipients").isArray()
                    && node.at("/recipients").size() == 1)
                .map(node -> node.at("/recipients/0").asText())
                .toList();

            assertThat(recipients)
                .hasSize(2)
                .containsExactlyInAnyOrder("mailto:" + ALICE, "mailto:" + CEDRIC);
        });
    }

    @Test
    void shouldNotCallItipWhenProcessingMultiRecipientMessage() {
        declareQueueBoundToExchange(ItipLocalDeliveryConsumer.EXCHANGE_NAME, testFanoutQueue);
        List<JsonNode> receivedMessages = consumeJsonMessages(testFanoutQueue);
        stubItipNoContent();

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s", "mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE, CEDRIC);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() ->
            assertThat(receivedMessages.stream()
                .filter(node -> node.at("/recipients").isArray() && node.at("/recipients").size() == 1)
                .toList())
                .hasSize(2));

        WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
    }

    @Test
    void shouldKeepOriginalPayloadFieldsWhenFanningOutRecipients() {
        declareQueueBoundToExchange(ItipLocalDeliveryConsumer.EXCHANGE_NAME, testFanoutQueue);
        List<JsonNode> receivedMessages = consumeJsonMessages(testFanoutQueue);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s", "mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE, CEDRIC);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            List<JsonNode> fanOutMessages = receivedMessages.stream()
                .filter(node -> node.at("/recipients").isArray() && node.at("/recipients").size() == 1)
                .toList();

            assertThat(fanOutMessages)
                .hasSize(2)
                .allSatisfy(node -> assertSoftly(soft -> {
                    soft.assertThat(node.at("/uid").asText()).isEqualTo(EVENT_UID);
                    soft.assertThat(node.at("/method").asText()).isEqualTo("REQUEST");
                    soft.assertThat(node.at("/sender").asText()).isEqualTo("mailto:" + BOB);
                    soft.assertThat(node.at("/calendarId").asText()).isEqualTo(CALENDAR_ID);
                    soft.assertThat(node.at("/message").asText()).isEqualTo(SIMPLE_ICAL);
                    soft.assertThat(node.at("/recipients").size()).isEqualTo(1);
                }));
        });
    }

    @Test
    void shouldCallItipWhenRecipientIsLocalUser() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        stubItipNoContent();

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() ->
            WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/itip"))
                .withRequestBody(WireMock.matchingJsonPath("$.uid", WireMock.equalTo(EVENT_UID)))
                .withRequestBody(WireMock.matchingJsonPath("$.recipient", WireMock.equalTo(ALICE)))
                .withRequestBody(WireMock.matchingJsonPath("$.sender", WireMock.equalTo(BOB)))
                .withRequestBody(WireMock.matchingJsonPath("$.method", WireMock.equalTo("REQUEST")))));
    }

    @Test
    void shouldCallItipWhenRecipientIsResource() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(RESOURCE_ID))));
        stubItipNoContent();

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() ->
            WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/itip"))
                .withRequestBody(WireMock.matchingJsonPath("$.recipient", WireMock.equalTo(ALICE)))));
    }

    @Test
    void shouldNotCallItipWhenMethodIsCounter() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "COUNTER",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> assertThat(emailMessages).hasSize(1));
        WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
    }

    @Test
    void shouldIgnoreDeliveryWhenRequestSenderIsNotOrganizer() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(CEDRIC, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
            assertThat(emailMessages).isEmpty();
        });
    }

    @Test
    void shouldIgnoreDeliveryWhenRequestOrganizerChangedComparedToOldMessage() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);
        String oldMessageWithDifferentOrganizer = SIMPLE_ICAL.replace("mailto:" + BOB, "mailto:" + CEDRIC);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "oldMessage": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), jsonString(oldMessageWithDifferentOrganizer), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
            assertThat(emailMessages).isEmpty();
        });
    }

    @Test
    void shouldIgnoreDeliveryWhenRecurringRequestHasMultipleOrganizers() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);
        String recurringMessage = recurringIcal(BOB, CEDRIC);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(recurringMessage), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
            assertThat(emailMessages).isEmpty();
        });
    }

    @Test
    void shouldIgnoreDeliveryWhenRecurringRequestOrganizerChangedComparedToOldMessage() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);
        String recurringCurrentMessage = recurringIcal(BOB, BOB);
        String recurringOldMessage = recurringIcal(CEDRIC, CEDRIC);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "oldMessage": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(recurringCurrentMessage), jsonString(recurringOldMessage), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
            assertThat(emailMessages).isEmpty();
        });
    }

    @Test
    void shouldNotCallItipWhenRecipientIsExternal() {
        when(localRecipientResolver.resolve(Username.of(ALICE))).thenReturn(Mono.just(Optional.empty()));
        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> assertThat(emailMessages).hasSize(1));
        WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
    }

    @Test
    void shouldIncludeSequenceWhenMethodIsCancelAndSequencePresent() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        stubItipNoContent();

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "CANCEL",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString("""
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:CANCEL
                BEGIN:VEVENT
                UID:abc-123-def-456
                SEQUENCE:7
                SUMMARY:Cancelled meeting
                DTSTART;TZID=Europe/Paris:20260401T100000
                DTEND;TZID=Europe/Paris:20260401T110000
                ORGANIZER:mailto:bob@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() ->
            WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/itip"))
                .withRequestBody(WireMock.matchingJsonPath("$.method", WireMock.equalTo("CANCEL")))
                .withRequestBody(WireMock.matchingJsonPath("$.sequence", WireMock.equalTo("7")))));
    }

    @Test
    void shouldPublishEmailNotificationForSingleRecipient() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));
        stubItipNoContent();

        declareQueueBoundToExchange(EventEmailConsumer.EXCHANGE_NAME, testEmailQueue);
        List<JsonNode> emailMessages = consumeJsonMessages(testEmailQueue);

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() -> {
            assertThat(emailMessages).hasSize(1);
            JsonNode emailPayload = emailMessages.getFirst();
            assertThat(emailPayload.at("/senderEmail").asText()).isEqualTo(BOB);
            assertThat(emailPayload.at("/recipientEmail").asText()).isEqualTo(ALICE);
            assertThat(emailPayload.at("/method").asText()).isEqualTo("REQUEST");
            assertThat(emailPayload.at("/notify").asBoolean()).isTrue();
            assertThat(emailPayload.at("/calendarURI").asText()).isEqualTo(CALENDAR_ID);
        });
    }

    @Test
    void shouldRouteToDeadLetterWhenItipReturns5xx() {
        when(localRecipientResolver.resolve(Username.of(ALICE)))
            .thenReturn(Mono.just(Optional.of(new OpenPaaSId(LOCAL_USER_ID))));

        Sender sender = channelPool.getSender();
        sender.declareExchange(ExchangeSpecification.exchange(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE)
            .durable(DURABLE)
            .type(BuiltinExchangeType.FANOUT.getType())).block();
        sender.bind(BindingSpecification.binding()
            .exchange(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE)
            .queue(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE)
            .routingKey(EMPTY_ROUTING_KEY)).block();
        try {
            channel.queuePurge(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(500).withBody("Internal Server Error")));

        String payload = """
            {
              "sender": "mailto:%s",
              "method": "REQUEST",
              "uid": "%s",
              "calendarId": "%s",
              "message": %s,
              "hasChange": true,
              "recipients": ["mailto:%s"]
            }
            """.formatted(BOB, EVENT_UID, CALENDAR_ID, jsonString(SIMPLE_ICAL), ALICE);

        publishToConsumer(payload);

        AWAIT_AT_MOST.untilAsserted(() ->
            assertThat(channel.basicGet(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE, true)).isNotNull());
    }

    private List<JsonNode> consumeJsonMessages(String queueName) {
        List<JsonNode> messages = new ArrayList<>();
        try {
            channel.basicConsume(queueName, true,
                (tag, delivery) -> messages.add(OBJECT_MAPPER.readTree(delivery.getBody())),
                tag -> {});
            return messages;
        } catch (Exception e) {
            throw new RuntimeException("Failed to consume queue " + queueName, e);
        }
    }

    private void stubItipNoContent() {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));
    }

    private void publishToConsumer(String payload) {
        channelPool.getSender()
            .send(Mono.just(new OutboundMessage(
                ItipLocalDeliveryConsumer.EXCHANGE_NAME,
                EMPTY_ROUTING_KEY,
                payload.getBytes(StandardCharsets.UTF_8))))
            .block();
    }

    private void declareQueueBoundToExchange(String exchangeName, String queueName) {
        Sender sender = channelPool.getSender();
        declareExchange(exchangeName);
        sender.declareQueue(QueueSpecification.queue(queueName)
            .durable(DURABLE)
            .arguments(QueueArguments.builder().build())).block();
        sender.bind(BindingSpecification.binding()
            .exchange(exchangeName)
            .queue(queueName)
            .routingKey(EMPTY_ROUTING_KEY)).block();
    }

    private void declareExchange(String exchangeName) {
        channelPool.getSender()
            .declareExchange(ExchangeSpecification.exchange(exchangeName)
            .durable(DURABLE)
            .type(BuiltinExchangeType.FANOUT.getType())).block();
    }

    private String jsonString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String recurringIcal(String masterOrganizer, String overrideOrganizer) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:{UID}
            SUMMARY:Recurring meeting
            DTSTART;TZID=Europe/Paris:20260401T100000
            DTEND;TZID=Europe/Paris:20260401T110000
            RRULE:FREQ=DAILY;COUNT=2
            ORGANIZER:mailto:{MASTER_ORGANIZER}
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{ATTENDEE}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            RECURRENCE-ID;TZID=Europe/Paris:20260402T100000
            SUMMARY:Recurring meeting override
            DTSTART;TZID=Europe/Paris:20260402T100000
            DTEND;TZID=Europe/Paris:20260402T110000
            ORGANIZER:mailto:{OVERRIDE_ORGANIZER}
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{ATTENDEE}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", EVENT_UID)
            .replace("{MASTER_ORGANIZER}", masterOrganizer)
            .replace("{OVERRIDE_ORGANIZER}", overrideOrganizer)
            .replace("{ATTENDEE}", ALICE);
    }
}
