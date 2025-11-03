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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

public class EventITIPConsumerTest {

    private static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private static final ConditionFactory awaitAtMost = calmlyAwait.atMost(20, TimeUnit.SECONDS);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final Optional<Boolean> TRUST_ALL_SSL_CERTS = Optional.of(true);

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.STRONG);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static Connection connection;
    private static Channel channel;
    private static WireMockServer wireMockServer;

    private OpenPaaSUserDAO openPaaSUserDAO;
    private CalDavClient calDavClient;
    private EventITIPConsumer consumer;
    private Clock clock;

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
        connection = connectionPool.getResilientConnection().block();
        channel = connection.createChannel();
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
        wireMockServer.stop();
    }

    @BeforeEach
    void setUp() throws SSLException {
        openPaaSUserDAO = new MemoryOpenPaaSUserDAO();
        DavConfiguration davConfiguration = new DavConfiguration(new UsernamePasswordCredentials("abc", "123"),
            URI.create("http://localhost:" + wireMockServer.port()),
            TRUST_ALL_SSL_CERTS,
            Optional.empty());
        calDavClient = new CalDavClient(davConfiguration, TECHNICAL_TOKEN_SERVICE_TESTING);
        clock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));
        consumer = new EventITIPConsumer(channelPool, QueueArguments.Builder::new, calDavClient, openPaaSUserDAO, clock);
        consumer.init();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
        wireMockServer.resetAll();
    }

    @Test
    void shouldRequestIMIPCallbackAfterConsumingEventITIPMessage() throws Exception {
        OpenPaaSUser user = openPaaSUserDAO.add(Username.of("bob@example.com")).block();
        wireMockServer.stubFor(WireMock.request("IMIPCALLBACK", WireMock.urlEqualTo("/calendars/" + user.id().value()))
            .willReturn(WireMock.aResponse().withStatus(204)));

        String json = """
            {
              "sender" : "alice@example.com",
              "recipient" : "bob@example.com",
              "message" : "BEGIN:VCALENDAR\\nEND:VCALENDAR",
              "method" : "REQUEST",
              "significantChange" : true,
              "hasChange" : true,
              "uid" : "a71aadec-40d9-4d1e-a1ab-984202fb1d1d",
              "component" : "VEVENT"
            }
            """;
        publishMessage(json);

        awaitAtMost.untilAsserted(() -> {
            WireMock.verify(
                WireMock.requestedFor("IMIPCALLBACK", WireMock.urlEqualTo("/calendars/" + user.id().value()))
                    .withRequestBody(WireMock.equalToJson("""
                        {
                          "ical": "BEGIN:VCALENDAR\\nEND:VCALENDAR",
                          "sender": "alice@example.com",
                          "recipient": "bob@example.com",
                          "replyTo": "alice@example.com",
                          "uid": "a71aadec-40d9-4d1e-a1ab-984202fb1d1d",
                          "dtstamp": "%s",
                          "method": "REQUEST",
                          "sequence": "0",
                          "recurrence-id": null
                        }
                        """.formatted(TIME_FORMATTER.format(clock.instant()))))
            );
        });
    }

    private void publishMessage(String message) throws IOException {
        AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder()
            .deliveryMode(PERSISTENT_TEXT_PLAIN.getDeliveryMode())
            .priority(PERSISTENT_TEXT_PLAIN.getPriority())
            .contentType(PERSISTENT_TEXT_PLAIN.getContentType())
            .build();

        channel.basicPublish(EventITIPConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY, basicProperties, message.getBytes(StandardCharsets.UTF_8));
    }
}
