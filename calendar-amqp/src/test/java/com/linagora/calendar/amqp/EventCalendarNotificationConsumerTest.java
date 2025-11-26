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

import static com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

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

import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.calendar.storage.OpenPaaSId;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;

public class EventCalendarNotificationConsumerTest {

    private static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private static final ConditionFactory awaitAtMost = calmlyAwait.atMost(20, TimeUnit.SECONDS);

    private static final RetryBackoffConfiguration RETRY_BACKOFF_CONFIGURATION = RetryBackoffConfiguration.builder()
        .maxRetries(3)
        .firstBackoff(Duration.ofMillis(5))
        .jitterFactor(0.5)
        .build();

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.STRONG);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static Channel channel;

    private EventBus eventBus;
    private EventCalendarNotificationConsumer consumer;

    @BeforeAll
    static void beforeAll() throws Exception {
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
        Connection connection = connectionPool.getResilientConnection().block();
        channel = connection.createChannel();
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    void setUp() throws SSLException {
        eventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()), RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters());
        consumer = new EventCalendarNotificationConsumer(channelPool, QueueArguments.Builder::new, eventBus);
        consumer.init();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @ParameterizedTest
    @EnumSource(EventCalendarNotificationConsumer.Queue.class)
    void shouldDispatchEventToCorrectChannelAfterConsumingEventCalendarNotificationMessage(EventCalendarNotificationConsumer.Queue queue) throws Exception {
        RegistrationKey registrationKey = new CalendarURLRegistrationKey(new CalendarURL(new OpenPaaSId("base1"), new OpenPaaSId("calendar1")));
        RegistrationKey registrationKey2 = new CalendarURLRegistrationKey(new CalendarURL(new OpenPaaSId("base1"), new OpenPaaSId("calendar")));

        AtomicBoolean eventReceived = new AtomicBoolean(false);
        Mono.from(eventBus.register(event -> {
            if (event instanceof CalendarChangeEvent) {
                eventReceived.set(true);
            }
        }, registrationKey)).block();

        AtomicBoolean eventReceived2 = new AtomicBoolean(false);
        Mono.from(eventBus.register(event -> {
            if (event instanceof CalendarChangeEvent) {
                eventReceived2.set(true);
            }
        }, registrationKey2)).block();

        String json = """
            {
               "eventPath": "/calendars/base1/calendar1/3423434.ics",
               "event": []
            }
            """;
        publishMessage(queue.exchangeName(), json);

        // Verify only the correct listener receives the event
        awaitAtMost.untilAsserted(() -> assertThat(eventReceived.get()).isTrue());
        Thread.sleep(100);
        assertThat(eventReceived2.get()).isFalse();
    }

    private void publishMessage(String exchange, String message) throws IOException {
        AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder()
            .deliveryMode(PERSISTENT_TEXT_PLAIN.getDeliveryMode())
            .priority(PERSISTENT_TEXT_PLAIN.getPriority())
            .contentType(PERSISTENT_TEXT_PLAIN.getContentType())
            .build();

        channel.basicPublish(exchange, EMPTY_ROUTING_KEY, basicProperties, message.getBytes(StandardCharsets.UTF_8));
    }
}
