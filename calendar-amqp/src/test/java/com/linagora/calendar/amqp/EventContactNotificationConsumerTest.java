/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  This file is subject to the Affero Gnu Public License           *
 *  version 3.                                                      *
 ********************************************************************/

package com.linagora.calendar.amqp;

import static com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.events.EventBus;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.RegistrationKey;
import org.apache.james.events.RetryBackoffConfiguration;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linagora.calendar.storage.AddressBookChangeEvent;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.AddressBookURLRegistrationKey;
import com.linagora.calendar.storage.OpenPaaSId;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;

class EventContactNotificationConsumerTest {

    private static final ConditionFactory AWAIT = Awaitility.with()
        .pollInterval(Duration.ofMillis(100))
        .await()
        .atMost(20, TimeUnit.SECONDS);
    private static final RetryBackoffConfiguration RETRY_BACKOFF_CONFIGURATION = RetryBackoffConfiguration.builder()
        .maxRetries(3)
        .firstBackoff(Duration.ofMillis(5))
        .jitterFactor(0.5)
        .build();

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.WEAK);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static Channel channel;

    private EventBus eventBus;
    private EventContactNotificationConsumer consumer;

    @BeforeAll
    static void beforeAll() throws Exception {
        RabbitMQConfiguration configuration = rabbitMQExtension.getRabbitMQ().getConfiguration();
        connectionPool = new SimpleConnectionPool(new RabbitMQConnectionFactory(configuration),
            SimpleConnectionPool.Configuration.builder().retries(2).initialDelay(Duration.ofMillis(5)));
        channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.builder().retries(2).maxBorrowDelay(Duration.ofMillis(250)).maxChannel(10),
            new RecordingMetricFactory(), new NoopGaugeRegistry());
        channelPool.start();
        Connection connection = connectionPool.getResilientConnection().block();
        channel = connection.createChannel();
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    void setUp() {
        eventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()), RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters());
        consumer = new EventContactNotificationConsumer(channelPool, QueueArguments.Builder::new, eventBus);
        consumer.init();
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @ParameterizedTest
    @EnumSource(EventContactNotificationConsumer.Queue.class)
    void shouldDispatchAddressBookChangeForContactPayloadWithOnlyPath(EventContactNotificationConsumer.Queue queue) throws Exception {
        AddressBookURL expected = new AddressBookURL(new OpenPaaSId("base1"), "contacts");
        RegistrationKey registrationKey = new AddressBookURLRegistrationKey(expected);
        AtomicBoolean received = new AtomicBoolean(false);

        Mono.from(eventBus.register(event -> {
            if (event instanceof AddressBookChangeEvent change && change.addressBookURL().equals(expected)) {
                received.set(true);
            }
        }, registrationKey)).block();

        publishMessage(queue.exchangeName(), """
            { "path": "addressbooks/base1/contacts/contact-id.vcf" }
            """);

        AWAIT.untilAsserted(() -> assertThat(received.get()).isTrue());
    }

    private void publishMessage(String exchange, String payload) throws IOException {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
            .deliveryMode(PERSISTENT_TEXT_PLAIN.getDeliveryMode())
            .priority(PERSISTENT_TEXT_PLAIN.getPriority())
            .contentType(PERSISTENT_TEXT_PLAIN.getContentType())
            .build();
        channel.basicPublish(exchange, EMPTY_ROUTING_KEY, properties, payload.getBytes(StandardCharsets.UTF_8));
    }
}
