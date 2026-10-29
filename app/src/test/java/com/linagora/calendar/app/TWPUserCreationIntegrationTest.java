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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.RabbitMQManagementAPI;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
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

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.saas.TWPUserCreationConsumer;
import com.linagora.calendar.saas.TWPUserCreationRabbitMQConfiguration;
import com.linagora.calendar.storage.OpenPaaSId;

import io.restassured.builder.RequestSpecBuilder;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class TWPUserCreationIntegrationTest {
    private record TestDomain(Domain domain, OpenPaaSId id) {
    }

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
    private CardDavClient cardDavClient;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws SSLException {
        purgeUserCreationDeadLetterQueue();

        sender = channelPool.getSender();
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

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

    @AfterEach
    void tearDown() {
        // The Sabre stack is shared: do not leave the invalid message behind, it degrades other test classes DLQ healthcheck
        purgeUserCreationDeadLetterQueue();
    }

    @Test
    void shouldAddMemberToDomainAddressBookWhenReceivingUserCreatedEvent(TwakeCalendarGuiceServer server) throws Exception {
        // Given a domain known to the side service, and a new member of it
        TestDomain domain = addDomain(server);
        Username alice = Username.fromLocalPartWithDomain("alice", domain.domain());

        // When the user.created event of that member is published
        publishUserCreated(userCreatedMessage(alice));

        // Then the member card is added to the domain address book, without registering the member as a calendar user
        awaitAtMost.untilAsserted(() -> assertThat(findDomainMemberCards(domain.id(), alice))
            .extracting(DomainMemberCard::contact)
            .extracting(AddressBookContact::mail)
            .containsExactly(Optional.of(alice.asMailAddress())));
        assertThat(server.getProbe(CalendarDataProbe.class).getUser(alice)).isNull();
    }

    @Test
    void shouldContinueProcessingAfterInvalidMessage(TwakeCalendarGuiceServer server) throws Exception {
        // Given a domain known to the side service, and a new member of it
        TestDomain domain = addDomain(server);
        Username alice = Username.fromLocalPartWithDomain("alice", domain.domain());

        // When an invalid message, then the user.created event of that member, are published
        publishUserCreated("invalid json message");
        publishUserCreated(userCreatedMessage(alice));

        // Then the member card is still added to the domain address book
        awaitAtMost.untilAsserted(() -> assertThat(findDomainMemberCards(domain.id(), alice)).hasSize(1));
    }

    @Test
    void rabbitMQHealthChecksShouldMonitorTheCalendarUserCreationQueues(TwakeCalendarGuiceServer server) {
        // Given the side service started with TWP settings enabled
        MonitoredRabbitMQProbe probe = server.getProbe(MonitoredRabbitMQProbe.class);

        // When the monitored queues are listed
        // Then the user creation queue and its dead letter queue are monitored
        assertThat(probe.consumedQueues()).contains(TWPUserCreationConsumer.QUEUE);
        assertThat(probe.deadLetterQueues()).contains(TWPUserCreationConsumer.DEAD_LETTER_QUEUE);
    }

    private TestDomain addDomain(TwakeCalendarGuiceServer server) {
        Domain domain = Domain.of("domain-" + UUID.randomUUID() + ".tld");
        CalendarDataProbe probe = server.getProbe(CalendarDataProbe.class);
        probe.addDomain(domain);
        OpenPaaSId domainId = probe.domainId(domain);
        cardDavClient.createDomainMembersAddressBook(domainId).block();
        return new TestDomain(domain, domainId);
    }

    private List<DomainMemberCard> findDomainMemberCards(OpenPaaSId domainId, Username username) throws Exception {
        MailAddress mail = username.asMailAddress();
        return cardDavClient.findContactsDomainMembersByEmail(domainId, mail)
            .collectList()
            .block();
    }

    private void purgeUserCreationDeadLetterQueue() {
        try {
            RabbitMQManagementAPI.from(sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration())
                .purgeQueue("/", TWPUserCreationConsumer.DEAD_LETTER_QUEUE);
        } catch (Exception e) {
            throw new RuntimeException("Unable to purge TWP user creation dead letter queue", e);
        }
    }

    private void publishUserCreated(String message) {
        sender.send(Mono.just(new OutboundMessage(TWPUserCreationRabbitMQConfiguration.TWP_USER_CREATED_EXCHANGE_DEFAULT,
                TWPUserCreationRabbitMQConfiguration.TWP_USER_CREATED_ROUTING_KEY_DEFAULT, message.getBytes(UTF_8))))
            .block();
    }

    private String userCreatedMessage(Username username) {
        return """
            {
                "twakeId": "%s",
                "internalEmail": "%s"
            }
            """.formatted(username.getLocalPart(), username.asString());
    }
}
