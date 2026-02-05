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

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.Inject;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSDomainSubscriptionConsumer;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionConsumer;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class SaaSSubscriptionIntegrationTest {
    private static final String SUBSCRIPTION_EXCHANGE = "saas.subscription";
    private static final String USER_ROUTING_KEY = "saas.subscription.routingKey";
    private static final String DOMAIN_ROUTING_KEY = "domain.subscription.changed";

    public static class SaaSSubscriptionProbe implements GuiceProbe {
        @Inject
        private SaaSSubscriptionConsumer userSubscriptionConsumer;

        @Inject
        private SaaSDomainSubscriptionConsumer domainSubscriptionConsumer;

        public void closeConsumers() {
            userSubscriptionConsumer.close();
            domainSubscriptionConsumer.close();
        }
    }

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .enableTwpSetting()
            .enableSaasSubscription(),
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(SaaSSubscriptionProbe.class));

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

    private RequestSpecification webadminRequestSpecification;
    private Sender sender;

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        sender = channelPool.getSender();

        webadminRequestSpecification = new RequestSpecBuilder()
            .setContentType(JSON)
            .setAccept(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .setBasePath("/")
            .build();

        Fixture.awaitAtMost.untilAsserted(() -> given(webadminRequestSpecification)
            .get("/healthcheck")
            .then()
            .statusCode(200)
            .body("checks.find { it.componentName == 'SaaSSubscriptionQueueConsumerHealthCheck' }.status",
                equalTo("healthy")));
    }

    @AfterEach
    void tearDown(TwakeCalendarGuiceServer server) {
        server.getProbe(SaaSSubscriptionProbe.class).closeConsumers();
    }

    @Test
    void shouldCreateDomainWhenReceivingDomainSubscriptionWithCalendarFeature(TwakeCalendarGuiceServer server) {
        String domainName = "test-domain-" + UUID.randomUUID() + ".tld";

        String message = createDomainSubscriptionMessage(domainName);
        publishDomainSubscriptionMessage(message);

        Fixture.awaitAtMost.untilAsserted(() -> {
            assertThat(server.getProbe(CalendarDataProbe.class).domainId(Domain.of(domainName))).isNotNull();
        });
    }

    @Test
    void shouldRegisterUserWhenReceivingUserSubscriptionWithCalendarFeature(TwakeCalendarGuiceServer server) {
        String domainName = "domain-" + UUID.randomUUID() + ".tld";
        String userEmail = "bob@" + domainName;

        // First create the domain
        publishDomainSubscriptionMessage(createDomainSubscriptionMessage(domainName));
        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).domainId(Domain.of(domainName))).isNotNull());

        // Then create the user
        String userMessage = createUserSubscriptionMessage(userEmail);
        publishUserSubscriptionMessage(userMessage);

        Fixture.awaitAtMost.untilAsserted(() -> {
            OpenPaaSUser user = server.getProbe(CalendarDataProbe.class).getUser(Username.of(userEmail));
            assertThat(user).isNotNull();
            assertThat(user.username().asString()).isEqualTo(userEmail);
        });
    }

    @Test
    void shouldHandleMultipleDomainsAndUsers(TwakeCalendarGuiceServer server) {
        String domain1 = "multi-test1-" + UUID.randomUUID() + ".tld";
        String domain2 = "multi-test2-" + UUID.randomUUID() + ".tld";
        String user1 = "user1@" + domain1;
        String user2 = "user2@" + domain2;

        // Create domains
        publishDomainSubscriptionMessage(createDomainSubscriptionMessage(domain1));
        publishDomainSubscriptionMessage(createDomainSubscriptionMessage(domain2));

        Fixture.awaitAtMost.untilAsserted(() -> {
            assertThat(server.getProbe(CalendarDataProbe.class).domainId(Domain.of(domain1))).isNotNull();
            assertThat(server.getProbe(CalendarDataProbe.class).domainId(Domain.of(domain2))).isNotNull();
        });

        // Create users
        publishUserSubscriptionMessage(createUserSubscriptionMessage(user1));
        publishUserSubscriptionMessage(createUserSubscriptionMessage(user2));

        Fixture.awaitAtMost.untilAsserted(() -> {
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(Username.of(user1))).isNotNull();
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(Username.of(user2))).isNotNull();
        });
    }

    @Test
    void shouldContinueProcessingAfterInvalidMessage(TwakeCalendarGuiceServer server) {
        // Send invalid message
        publishDomainSubscriptionMessage("invalid json message");

        // Then send valid message
        String domainName = "after-invalid-" + UUID.randomUUID() + ".tld";
        publishDomainSubscriptionMessage(createDomainSubscriptionMessage(domainName));

        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).domainId(Domain.of(domainName))).isNotNull());
    }

    @Test
    void shouldExposeWebAdminHealthcheck() {
        Fixture.awaitAtMost.untilAsserted(() -> {
            String body = given(webadminRequestSpecification)
                .when()
                .get("/healthcheck")
                .then()
                .extract()
                .body()
                .asString();

            assertThatJson(body)
                .inPath("checks")
                .isArray()
                .anySatisfy(node ->
                    assertThatJson(node).isEqualTo("""
                            {
                              "componentName": "SaaSSubscriptionDeadLetterQueueHealthCheck",
                              "escapedComponentName": "SaaSSubscriptionDeadLetterQueueHealthCheck",
                              "status": "healthy",
                              "cause": null
                            }
                        """))
                .anySatisfy(node ->
                    assertThatJson(node).isEqualTo("""
                            {
                              "componentName": "SaaSSubscriptionQueueConsumerHealthCheck",
                              "escapedComponentName": "SaaSSubscriptionQueueConsumerHealthCheck",
                              "status": "healthy",
                              "cause": null
                            }
                        """));
        });
    }

    private void publishDomainSubscriptionMessage(String message) {
        sender.send(Mono.just(new OutboundMessage(SUBSCRIPTION_EXCHANGE, DOMAIN_ROUTING_KEY, message.getBytes(UTF_8))))
            .block();
    }

    private void publishUserSubscriptionMessage(String message) {
        sender.send(Mono.just(new OutboundMessage(SUBSCRIPTION_EXCHANGE, USER_ROUTING_KEY, message.getBytes(UTF_8))))
            .block();
    }

    private String createDomainSubscriptionMessage(String domain) {
        return """
            {
                "domain": "%s",
                "mailDnsConfigurationValidated": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(domain);
    }

    private String createUserSubscriptionMessage(String email) {
        return """
            {
                "internalEmail": "%s",
                "isPaying": false,
                "canUpgrade": false,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(email);
    }
}
