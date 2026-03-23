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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

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
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
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

    private static final ConditionFactory awaitAtMost = Awaitility.with()
        .pollInterval(Duration.ofMillis(200))
        .pollDelay(Duration.ofMillis(200))
        .await()
        .atMost(20, TimeUnit.SECONDS);

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String CEDRIC = "cedric@example.com";
    private static final String EVENT_UID = "abc-123-def-456";
    private static final String RECIPIENT_OPENPAAS_ID = "60a7b2c3d4e5f6a7b8c9d0e1";
    private static final String CALENDAR_ID = "my-calendar-id";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    // Test queue names — declared fresh per test to avoid AMQP channel-close-on-404 issues
    private String testFanoutQueue;
    private String testEmailQueue;

    private static final String SIMPLE_ICAL = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:abc-123-def-456
        SUMMARY:Test Meeting
        DTSTART;TZID=Europe/Paris:20240601T100000
        DTEND;TZID=Europe/Paris:20240601T110000
        ORGANIZER:mailto:bob@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    private static final String UPDATED_ICAL = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:abc-123-def-456
        SUMMARY:Updated Meeting
        DTSTART;TZID=Europe/Paris:20240601T110000
        DTEND;TZID=Europe/Paris:20240601T120000
        ORGANIZER:mailto:bob@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    /** Recurring master event — Alice is attendee, no occurrence overrides. */
    private static final String RECURRING_MASTER_ICAL = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:abc-123-def-456
        SUMMARY:Recurring Meeting
        DTSTART;TZID=Europe/Paris:20240601T100000
        DTEND;TZID=Europe/Paris:20240601T110000
        RRULE:FREQ=WEEKLY
        ORGANIZER:mailto:bob@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    /** Override for occurrence #2 of the recurring series — Alice + Cedric. */
    private static final String OCCURRENCE_OVERRIDE_ICAL = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:abc-123-def-456
        SUMMARY:Recurring Meeting - occurrence 2
        DTSTART;TZID=Europe/Paris:20240608T100000
        DTEND;TZID=Europe/Paris:20240608T110000
        RECURRENCE-ID;TZID=Europe/Paris:20240608T100000
        ORGANIZER:mailto:bob@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:cedric@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    /** Same override occurrence but with only Alice (before Cedric was added). */
    private static final String OCCURRENCE_OVERRIDE_ALICE_ONLY_ICAL = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:abc-123-def-456
        SUMMARY:Recurring Meeting - occurrence 2
        DTSTART;TZID=Europe/Paris:20240608T100000
        DTEND;TZID=Europe/Paris:20240608T110000
        RECURRENCE-ID;TZID=Europe/Paris:20240608T100000
        ORGANIZER:mailto:bob@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
        END:VEVENT
        END:VCALENDAR
        """;

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.WEAK);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static WireMockServer wireMockServer;

    private ItipLocalDeliveryConsumer consumer;
    private OpenPaaSUserDAO openPaaSUserDAO;
    private Channel channel; // fresh per test to avoid AMQP channel-close-on-404

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

        // Unique queue names per test to avoid cross-test pollution
        String testId = String.valueOf(System.nanoTime());
        testFanoutQueue = "test:itip:localDelivery:fanout:" + testId;
        testEmailQueue = "test:event:notificationEmail:" + testId;

        openPaaSUserDAO = mock(OpenPaaSUserDAO.class);
        when(openPaaSUserDAO.retrieve(any(Username.class)))
            .thenReturn(Mono.just(new OpenPaaSUser(
                Username.of(ALICE), new OpenPaaSId(RECIPIENT_OPENPAAS_ID), "Alice", "Test")));

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
            openPaaSUserDAO,
            DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT);

        // Declare test queues bound to relevant exchanges BEFORE starting the consumer
        Sender sender = channelPool.getSender();

        sender.declareQueue(QueueSpecification.queue(testFanoutQueue).durable(DURABLE)
            .arguments(QueueArguments.builder().build())).block();
        sender.bind(BindingSpecification.binding()
            .exchange(ItipLocalDeliveryConsumer.EXCHANGE_NAME)
            .queue(testFanoutQueue)
            .routingKey(EMPTY_ROUTING_KEY)).block();

        sender.declareExchange(ExchangeSpecification.exchange(EventEmailConsumer.EXCHANGE_NAME)
            .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())).block();
        sender.declareQueue(QueueSpecification.queue(testEmailQueue).durable(DURABLE)
            .arguments(QueueArguments.builder().build())).block();
        sender.bind(BindingSpecification.binding()
            .exchange(EventEmailConsumer.EXCHANGE_NAME)
            .queue(testEmailQueue)
            .routingKey(EMPTY_ROUTING_KEY)).block();

        consumer.init();

        // Fresh channel per test — avoids AMQP channel-close-on-404 poisoning subsequent tests
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

    // ---- Fan-out tests -----------------------------------------------------------------------

    @Test
    void fanOutShouldRepublishOneSingleRecipientMessagePerRecipient() throws Exception {
        List<byte[]> received = new ArrayList<>();
        channel.basicConsume(testFanoutQueue, true,
            (tag, delivery) -> received.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE, "mailto:" + CEDRIC), false, Optional.empty(), "REQUEST"));
        awaitAtMost.untilAsserted(() -> assertThat(received).hasSize(2));

        List<String> recipients = received.stream()
            .map(bytes -> {
                try {
                    return OBJECT_MAPPER.readTree(bytes).at("/recipients/0").asText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .toList();
        assertThat(recipients).containsExactlyInAnyOrder("mailto:" + ALICE, "mailto:" + CEDRIC);
    }

    @Test
    void fanOutShouldNotCallItipEndpoint() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE, "mailto:" + CEDRIC), true, Optional.empty(), "REQUEST"));

        Thread.sleep(500);
        WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/itip")));
    }

    @Test
    void fanOutShouldPreserveAllOtherFields() throws Exception {
        List<byte[]> received = new ArrayList<>();
        channel.basicConsume(testFanoutQueue, true,
            (tag, delivery) -> received.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE, "mailto:" + CEDRIC), true, Optional.of(SIMPLE_ICAL), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(received).hasSize(2));

        JsonNode node = OBJECT_MAPPER.readTree(received.get(0));
        assertThat(node.at("/uid").asText()).isEqualTo(EVENT_UID);
        assertThat(node.at("/method").asText()).isEqualTo("REQUEST");
        assertThat(node.at("/sender").asText()).isEqualTo("mailto:" + BOB);
        assertThat(node.at("/hasChange").asBoolean()).isTrue();
        assertThat(node.at("/recipients").size()).isEqualTo(1);
    }

    // ---- Local recipient (204) ---------------------------------------------------------------

    @Test
    void localRecipientShouldCallItipEndpoint() {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), false, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() ->
            WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/itip"))
                .withRequestBody(WireMock.matchingJsonPath("$.uid", WireMock.equalTo(EVENT_UID)))
                .withRequestBody(WireMock.matchingJsonPath("$.recipient", WireMock.equalTo(ALICE)))
                .withRequestBody(WireMock.matchingJsonPath("$.sender", WireMock.equalTo(BOB)))
                .withRequestBody(WireMock.matchingJsonPath("$.method", WireMock.equalTo("REQUEST")))));
    }

    @Test
    void localRecipientWithHasChangeShouldPublishEmailNotification() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode emailPayload = OBJECT_MAPPER.readTree(emailMessages.get(0));
        assertThat(emailPayload.at("/senderEmail").asText()).isEqualTo(BOB);
        assertThat(emailPayload.at("/recipientEmail").asText()).isEqualTo(ALICE);
        assertThat(emailPayload.at("/method").asText()).isEqualTo("REQUEST");
        assertThat(emailPayload.at("/notify").asBoolean()).isTrue();
        assertThat(emailPayload.at("/isNewEvent").asBoolean()).isTrue();
    }

    @Test
    void localRecipientWithHasChangeShouldIncludeEventPathForLocalUser() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode emailPayload = OBJECT_MAPPER.readTree(emailMessages.get(0));
        assertThat(emailPayload.at("/eventPath").asText())
            .isEqualTo("/calendars/" + RECIPIENT_OPENPAAS_ID + "/" + CALENDAR_ID + "/" + EVENT_UID + ".ics");
        assertThat(emailPayload.at("/calendarURI").asText()).isEqualTo(CALENDAR_ID);
    }

    @Test
    void localRecipientWithoutHasChangeShouldNotPublishEmailNotification() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), false, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() ->
            WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/itip"))));

        Thread.sleep(300);
        assertThat(channel.basicGet(testEmailQueue, true)).isNull();
    }

    // ---- External recipient (400) ------------------------------------------------------------

    @Test
    void externalRecipientShouldPublishEmailWhenHasChange() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(400)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode emailPayload = OBJECT_MAPPER.readTree(emailMessages.get(0));
        assertThat(emailPayload.at("/recipientEmail").asText()).isEqualTo(ALICE);
    }

    @Test
    void externalRecipientShouldHaveNoEventPathInEmailPayload() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(400)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode emailPayload = OBJECT_MAPPER.readTree(emailMessages.get(0));
        assertThat(emailPayload.has("eventPath")).isFalse();
    }

    @Test
    void externalRecipientWithoutHasChangeShouldPublishNothing() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(400)));

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), false, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() ->
            WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/itip"))));

        Thread.sleep(300);
        assertThat(channel.basicGet(testEmailQueue, true)).isNull();
    }

    // ---- 5xx → dead letter -------------------------------------------------------------------

    @Test
    void serverErrorShouldDeadLetterMessage() {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(500).withBody("Internal Server Error")));

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() ->
            assertThat(channel.basicGet(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE, true)).isNotNull());
    }

    // ---- Changes diff ------------------------------------------------------------------------

    @Test
    void changesFieldShouldContainDiffWhenOldMessagePresent() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.of(SIMPLE_ICAL), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode changes = OBJECT_MAPPER.readTree(emailMessages.get(0)).at("/changes");
        assertThat(changes.isMissingNode()).isFalse();
        assertThat(changes.at("/summary/previous").asText()).isEqualTo("Test Meeting");
        assertThat(changes.at("/summary/current").asText()).isEqualTo("Updated Meeting");
        assertThat(changes.at("/dtstart/previous/date").asText()).isEqualTo("2024-06-01 10:00:00.000000");
        assertThat(changes.at("/dtstart/current/date").asText()).isEqualTo("2024-06-01 11:00:00.000000");
        assertThat(changes.at("/dtstart/previous/timezone").asText()).isEqualTo("Europe/Paris");
    }

    @Test
    void changesFieldShouldBeAbsentWhenNoOldMessage() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.empty(), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).has("changes")).isFalse();
    }

    @Test
    void changesFieldShouldBeAbsentWhenOccurrenceOverrideNotInOldCalendar() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        // Old: master only. New: override with RECURRENCE-ID.
        // No old override to diff against → changes field must be absent (not "{}").
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("sender", "mailto:" + BOB);
        node.put("method", "REQUEST");
        node.put("uid", EVENT_UID);
        node.put("calendarId", CALENDAR_ID);
        node.put("message", OCCURRENCE_OVERRIDE_ICAL);
        node.put("hasChange", true);
        node.put("oldMessage", RECURRING_MASTER_ICAL);
        node.putArray("recipients").add("mailto:" + ALICE);
        publishToConsumer(OBJECT_MAPPER.writeValueAsString(node));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).has("changes")).isFalse();
    }

    @Test
    void changesFieldShouldContainDiffForUpdatedOccurrenceOverride() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        // Old: override occurrence with Alice only (SUMMARY "Recurring Meeting - occurrence 2").
        // New: same override occurrence with Alice + Cedric and updated summary.
        String updatedOverrideIcal = OCCURRENCE_OVERRIDE_ICAL.replace(
            "SUMMARY:Recurring Meeting - occurrence 2", "SUMMARY:Updated occurrence 2");
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("sender", "mailto:" + BOB);
        node.put("method", "REQUEST");
        node.put("uid", EVENT_UID);
        node.put("calendarId", CALENDAR_ID);
        node.put("message", updatedOverrideIcal);
        node.put("hasChange", true);
        node.put("oldMessage", OCCURRENCE_OVERRIDE_ALICE_ONLY_ICAL);
        node.putArray("recipients").add("mailto:" + ALICE);
        publishToConsumer(OBJECT_MAPPER.writeValueAsString(node));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode changes = OBJECT_MAPPER.readTree(emailMessages.get(0)).at("/changes");
        assertThat(changes.isMissingNode()).isFalse();
        assertThat(changes.at("/summary/previous").asText()).isEqualTo("Recurring Meeting - occurrence 2");
        assertThat(changes.at("/summary/current").asText()).isEqualTo("Updated occurrence 2");
    }

    @Test
    void isNewEventShouldBeTrueWhenRecipientAbsentFromOldMessage() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        // old event has CEDRIC as attendee, not ALICE
        String oldIcalWithCedric = SIMPLE_ICAL.replace("alice@example.com", CEDRIC);
        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.of(oldIcalWithCedric), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).at("/isNewEvent").asBoolean()).isTrue();
    }

    @Test
    void isNewEventShouldBeFalseWhenRecipientPresentInOldMessage() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.of(SIMPLE_ICAL), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).at("/isNewEvent").asBoolean()).isFalse();
    }

    @Test
    void isNewEventShouldBeTrueForOccurrenceOverrideWhenNoMatchingOldOverride() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        // Old: recurring master with Alice. New: override for occurrence #2 (RECURRENCE-ID).
        // Old calendar has no override for that occurrence → isNewEvent = true for Alice,
        // even though she is present in the master VEVENT.
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("sender", "mailto:" + BOB);
        node.put("method", "REQUEST");
        node.put("uid", EVENT_UID);
        node.put("calendarId", CALENDAR_ID);
        node.put("message", OCCURRENCE_OVERRIDE_ICAL);
        node.put("hasChange", true);
        node.put("oldMessage", RECURRING_MASTER_ICAL);
        node.putArray("recipients").add("mailto:" + ALICE);
        publishToConsumer(OBJECT_MAPPER.writeValueAsString(node));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).at("/isNewEvent").asBoolean()).isTrue();
    }

    @Test
    void isNewEventShouldBeFalseWhenRecipientPresentInMatchingOldOverride() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        // Old: override for occurrence #2 with Alice only.
        // New: same override with Alice + Cedric (Cedric added).
        // Alice was already in the old override → isNewEvent = false for Alice.
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("sender", "mailto:" + BOB);
        node.put("method", "REQUEST");
        node.put("uid", EVENT_UID);
        node.put("calendarId", CALENDAR_ID);
        node.put("message", OCCURRENCE_OVERRIDE_ICAL);
        node.put("hasChange", true);
        node.put("oldMessage", OCCURRENCE_OVERRIDE_ALICE_ONLY_ICAL);
        node.putArray("recipients").add("mailto:" + ALICE);
        publishToConsumer(OBJECT_MAPPER.writeValueAsString(node));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).at("/isNewEvent").asBoolean()).isFalse();
    }

    // ---- COUNTER method tests ----------------------------------------------------------------

    @Test
    void counterMethodShouldIncludeOldEventWhenOldMessagePresent() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.of(SIMPLE_ICAL), "COUNTER"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        JsonNode emailPayload = OBJECT_MAPPER.readTree(emailMessages.get(0));
        assertThat(emailPayload.has("oldEvent")).isTrue();
        assertThat(emailPayload.at("/oldEvent").asText()).isEqualTo(SIMPLE_ICAL);
    }

    @Test
    void requestMethodShouldNotIncludeOldEventField() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/itip"))
            .willReturn(WireMock.aResponse().withStatus(204)));

        List<byte[]> emailMessages = new ArrayList<>();
        channel.basicConsume(testEmailQueue, true,
            (tag, delivery) -> emailMessages.add(delivery.getBody()), tag -> {});

        publishToConsumer(buildPayload(List.of("mailto:" + ALICE), true, Optional.of(SIMPLE_ICAL), "REQUEST"));

        awaitAtMost.untilAsserted(() -> assertThat(emailMessages).hasSize(1));

        assertThat(OBJECT_MAPPER.readTree(emailMessages.get(0)).has("oldEvent")).isFalse();
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private void publishToConsumer(String payload) {
        channelPool.getSender()
            .send(Mono.just(new OutboundMessage(
                ItipLocalDeliveryConsumer.EXCHANGE_NAME,
                EMPTY_ROUTING_KEY,
                payload.getBytes(StandardCharsets.UTF_8))))
            .block();
    }

    private String buildPayload(List<String> recipients, boolean hasChange,
                                 Optional<String> oldMessage, String method) {
        try {
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("sender", "mailto:" + BOB);
            node.put("method", method);
            node.put("uid", EVENT_UID);
            node.put("calendarId", CALENDAR_ID);
            node.put("message", UPDATED_ICAL);
            node.put("hasChange", hasChange);
            oldMessage.ifPresent(old -> node.put("oldMessage", old));
            ArrayNode recipientsNode = node.putArray("recipients");
            recipients.forEach(recipientsNode::add);
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
