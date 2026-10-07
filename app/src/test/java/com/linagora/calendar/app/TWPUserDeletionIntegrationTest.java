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

import static com.linagora.calendar.storage.TestFixture.awaitAtMost;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.RabbitMQManagementAPI;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.saas.TWPCalendarUserDeletionModule;
import com.linagora.tmail.saas.rabbitmq.deletion.TWPUserDeletionConsumer;
import com.linagora.tmail.saas.rabbitmq.deletion.TWPUserDeletionRabbitMQConfiguration;

import io.restassured.builder.RequestSpecBuilder;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class TWPUserDeletionIntegrationTest {
    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    @RegisterExtension
    @Order(2)
    TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .enableTwpSetting(),
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(MonitoredRabbitMQProbe.class));

    private static SimpleConnectionPool connectionPool;
    private static ReactorRabbitMQChannelPool channelPool;

    @BeforeAll
    static void beforeAll(DockerSabreDavSetup dockerSabreDavSetup) {
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
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    private Sender sender;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        purgeUserDeletionDeadLetterQueue();

        sender = channelPool.getSender();

        awaitAtMost.untilAsserted(() -> given(new RequestSpecBuilder()
                .setContentType(JSON)
                .setAccept(JSON)
                .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
                .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
                .setBasePath("/")
                .build())
            .get("/healthcheck")
        .then()
            .statusCode(200)
            .body("checks.find { it.componentName == 'RabbitMQConsumers' }.status",
                equalTo("healthy")));
    }

    @Test
    void shouldDeleteUserDataWhenReceivingB2CUserDeletionEvent(TwakeCalendarGuiceServer server) {
        Username username = provisionUser(server);

        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_ROUTING_KEY_DEFAULT,
            userDeletionMessage(username));

        awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(username)).isNull());
    }

    @Test
    void shouldDeleteUserDataWhenReceivingB2BUserDeletionEvent(TwakeCalendarGuiceServer server) {
        Username username = provisionUser(server);

        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2B_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2B_ROUTING_KEY_DEFAULT,
            userDeletionMessage(username));

        awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(username)).isNull());
    }

    @Test
    void shouldNotDeleteOtherUsers(TwakeCalendarGuiceServer server) {
        Username deletedUser = provisionUser(server);
        Username otherUser = provisionUser(server);

        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_ROUTING_KEY_DEFAULT,
            userDeletionMessage(deletedUser));

        awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(deletedUser)).isNull());
        assertThat(server.getProbe(CalendarDataProbe.class).getUser(otherUser)).isNotNull();
    }

    @Test
    void shouldContinueProcessingAfterInvalidMessage(TwakeCalendarGuiceServer server) {
        Username username = provisionUser(server);

        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_ROUTING_KEY_DEFAULT,
            "invalid json message");
        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_ROUTING_KEY_DEFAULT,
            userDeletionMessage(username));

        awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(username)).isNull());
    }

    @Test
    void rabbitMQHealthChecksShouldMonitorTheCalendarUserDeletionQueues(TwakeCalendarGuiceServer server) {
        MonitoredRabbitMQProbe probe = server.getProbe(MonitoredRabbitMQProbe.class);

        assertThat(probe.consumedQueues())
            .contains(TWPCalendarUserDeletionModule.CONSUMER_CONFIG.queue())
            .doesNotContain(TWPUserDeletionConsumer.UserDeletionConsumerConfig.DEFAULT.queue());
        assertThat(probe.deadLetterQueues())
            .contains(TWPCalendarUserDeletionModule.CONSUMER_CONFIG.deadLetterQueue())
            .doesNotContain(TWPUserDeletionConsumer.UserDeletionConsumerConfig.DEFAULT.deadLetterQueue());
    }

    private Username provisionUser(TwakeCalendarGuiceServer server) {
        Domain domain = Domain.of("domain-" + UUID.randomUUID() + ".tld");
        Username username = Username.fromLocalPartWithDomain("bob", domain);
        server.getProbe(CalendarDataProbe.class)
            .addDomain(domain)
            .addUser(username, PASSWORD);
        return username;
    }

    private void purgeUserDeletionDeadLetterQueue() {
        try {
            RabbitMQManagementAPI.from(sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration())
                .purgeQueue("/", TWPCalendarUserDeletionModule.CONSUMER_CONFIG.deadLetterQueue());
        } catch (Exception e) {
            throw new RuntimeException("Unable to purge TWP user deletion dead letter queue before test", e);
        }
    }

    private void publish(String exchange, String routingKey, String message) {
        sender.send(Mono.just(new OutboundMessage(exchange, routingKey, message.getBytes(UTF_8))))
            .block();
    }

    private String userDeletionMessage(Username username) {
        return """
            {
                "internalEmail": "%s",
                "unknownField": "tolerated"
            }
            """.formatted(username.asString());
    }
}
