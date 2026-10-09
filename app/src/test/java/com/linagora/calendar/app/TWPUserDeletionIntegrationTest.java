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
import java.util.UUID;

import javax.net.ssl.SSLException;

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
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.saas.TWPCalendarUserDeletionModule;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.tmail.saas.rabbitmq.deletion.TWPUserDeletionConsumer;
import com.linagora.tmail.saas.rabbitmq.deletion.TWPUserDeletionRabbitMQConfiguration;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import io.restassured.builder.RequestSpecBuilder;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class TWPUserDeletionIntegrationTest {
    private static final String PASSWORD = "secret";
    private static final String SABRE_DATABASE = "sabre";
    private static final String SECONDARY_CALENDAR_ID = "secondary-calendar";
    private static final String CUSTOM_ADDRESS_BOOK_ID = "custom-book";

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
    private static MongoClient sabreMongoClient;

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
        sabreMongoClient = MongoClients.create(dockerSabreDavSetup.getMongoDbIpAddress().toString());
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
        sabreMongoClient.close();
    }

    private Sender sender;
    private CalDavClient calDavClient;
    private CardDavClient cardDavClient;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws SSLException {
        purgeUserDeletionDeadLetterQueue();

        sender = channelPool.getSender();
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
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
    void shouldDeleteUserCalendarsWhenReceivingUserDeletionEvent(TwakeCalendarGuiceServer server) {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        CalendarURL primaryCalendarURL = CalendarURL.from(user.id());
        calDavClient.createNewCalendar(user.username(), user.id(),
            new CalDavClient.NewCalendar(SECONDARY_CALENDAR_ID, "Secondary", "#97c3c1", "A secondary calendar")).block();
        // Ensure calendar directory is activated
        calDavClient.export(primaryCalendarURL, user.username()).block();
        String eventUid = UUID.randomUUID().toString();
        calDavClient.importCalendar(primaryCalendarURL, eventUid, user.username(), eventIcs(eventUid).getBytes(UTF_8)).block();
        assertThat(countSecondaryCalendars(user)).isOne();
        assertThat(countEvents(eventUid)).isOne();

        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_ROUTING_KEY_DEFAULT,
            userDeletionMessage(user.username()));

        awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(user.username())).isNull());
        assertThat(countSecondaryCalendars(user)).isZero();
        assertThat(countEvents(eventUid)).isZero();
    }

    @Test
    void shouldDeleteUserContactsWhenReceivingUserDeletionEvent(TwakeCalendarGuiceServer server) {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        cardDavClient.createUserAddressBook(user.username(), user.id(), CUSTOM_ADDRESS_BOOK_ID, "Custom book").block();
        String vcardUid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(user.username(), new AddressBookURL(user.id(), "collected"), vcardUid,
            vcard(vcardUid).getBytes(UTF_8)).block();
        assertThat(countCustomAddressBooks(user)).isOne();
        assertThat(countContacts(vcardUid)).isOne();

        publish(TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_EXCHANGE_DEFAULT,
            TWPUserDeletionRabbitMQConfiguration.TWP_USER_DELETION_B2C_ROUTING_KEY_DEFAULT,
            userDeletionMessage(user.username()));

        awaitAtMost.untilAsserted(() ->
            assertThat(server.getProbe(CalendarDataProbe.class).getUser(user.username())).isNull());
        assertThat(countCustomAddressBooks(user)).isZero();
        assertThat(countContacts(vcardUid)).isZero();
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

    // Once the OpenPaaS user record is gone, Sabre can no longer authenticate requests on their behalf:
    // DAV data deletion is therefore asserted directly against Sabre storage.
    private long countSecondaryCalendars(OpenPaaSUser user) {
        return count("calendarinstances", Filters.and(
            Filters.eq("principaluri", principalUri(user)),
            Filters.eq("uri", SECONDARY_CALENDAR_ID)));
    }

    private long countEvents(String eventUid) {
        return count("calendarobjects", Filters.eq("uid", eventUid));
    }

    private long countCustomAddressBooks(OpenPaaSUser user) {
        return count("addressbooks", Filters.and(
            Filters.eq("principaluri", principalUri(user)),
            Filters.eq("uri", CUSTOM_ADDRESS_BOOK_ID)));
    }

    private long countContacts(String vcardUid) {
        return count("cards", Filters.regex("carddata", "UID:" + vcardUid));
    }

    private String principalUri(OpenPaaSUser user) {
        return "principals/users/" + user.id().value();
    }

    private long count(String collection, Bson filter) {
        return Mono.from(sabreMongoClient.getDatabase(SABRE_DATABASE)
                .getCollection(collection)
                .countDocuments(filter))
            .block();
    }

    private String eventIcs(String uid) {
        return """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid);
    }

    private String vcard(String uid) {
        return """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:John Doe
            EMAIL:john.doe@example.com
            END:VCARD
            """.formatted(uid);
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
