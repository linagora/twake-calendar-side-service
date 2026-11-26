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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.utils.GuiceProbe;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TestFixture;
import com.linagora.calendar.twakeactivity.TwakeActivityConfiguration;
import com.linagora.calendar.twakeactivity.UserActivityConsumer;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

class TwakeActivityIntegrationTest {
    private record Activity(String routingKey, String contentType, String messageId, JsonNode body) {
    }

    private static final String ACTIVITY_EXCHANGE = TwakeActivityConfiguration.DEFAULT_EXCHANGE;
    private static final String FEED_QUEUE = "test-user-activity-feed";
    private static final String USER = "calendar";
    private static final String PASSWORD = "calendar";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ConditionFactory AWAIT = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200));

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @TempDir
    Path workingDirectory;

    private TwakeCalendarGuiceServer server;
    private RabbitMQConfiguration rabbitMQConfiguration;
    private DavTestHelper dav;
    private CardDavClient cardDavClient;
    private OpenPaaSUser bob;

    // Without twp.rabbitmq.uri, the Twake Workplace connection the activity goes to is the side service's one.
    @BeforeEach
    void setUp() throws Exception {
        rabbitMQConfiguration = sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration();
        Path conf = Files.createDirectories(workingDirectory.resolve("conf"));
        for (String file : List.of("configuration.properties", "jwt_privatekey", "jwt_publickey", "rabbitmq.properties", "webadmin.properties")) {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(file)) {
                Files.copy(in, conf.resolve(file));
            }
        }
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(ACTIVITY_EXCHANGE, BuiltinExchangeType.TOPIC, true);
            channel.queueDeclare(FEED_QUEUE, true, false, false, null);
            channel.queueBind(FEED_QUEUE, ACTIVITY_EXCHANGE, "#");
        }

        server = TwakeCalendarMain.createServer(TwakeCalendarConfiguration.builder()
                .workingDirectory(workingDirectory.toFile())
                .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
                .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
                .enableTwpSetting()
                .enableTwakeActivity()
                .build())
            .overrideWith(List.of(AppTestHelper.OIDC_BY_PASS_MODULE,
                DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
                binder -> Multibinder.newSetBinder(binder, GuiceProbe.class).addBinding().to(MonitoredRabbitMQProbe.class)));
        server.start();

        bob = sabreDavExtension.newTestUser();
        server.getProbe(CalendarDataProbe.class).addDomain(bob.username().getDomainPart().orElseThrow());
        dav = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING);
        AWAIT.untilAsserted(() -> assertThat(consumerCount(UserActivityConsumer.QUEUE)).isEqualTo(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            for (String queue : List.of(UserActivityConsumer.QUEUE, UserActivityConsumer.DEAD_LETTER_QUEUE, FEED_QUEUE)) {
                channel.queueDelete(queue);
            }
        }
    }

    @Test
    void rabbitMQHealthChecksShouldMonitorTheActivityQueue() {
        MonitoredRabbitMQProbe probe = server.getProbe(MonitoredRabbitMQProbe.class);

        assertThat(probe.consumedQueues()).contains(UserActivityConsumer.QUEUE);
        assertThat(probe.deadLetterQueues()).contains(UserActivityConsumer.DEAD_LETTER_QUEUE);
    }

    @Test
    void eventCreationShouldPublishAnEventCreatedActivity() {
        String uid = UUID.randomUUID().toString();
        dav.upsertCalendar(bob, event(uid), uid);

        Activity activity = awaitActivity("com.twake.calendar.event.created.v1");

        assertThat(activity.contentType()).isEqualTo("application/cloudevents+json");
        assertThat(activity.messageId()).isEqualTo(activity.body().path("id").asText());
        assertThatJson(activity.body().toString()).isEqualTo("""
            {
              "specversion": "1.0",
              "id": "${json-unit.any-string}",
              "source": "twake://calendar",
              "type": "com.twake.calendar.event.created.v1",
              "time": "${json-unit.any-string}",
              "subject": "calendars/%1$s/%1$s/%2$s.ics",
              "twakeactor": "%3$s",
              "twakedomain": "%4$s",
              "twakeresourceowner": "%3$s",
              "data": {
                "path": "calendars/%1$s/%1$s/%2$s.ics",
                "calendar": "calendars/%1$s/%1$s"
              }
            }
            """.formatted(bob.id().value(), uid, bob.username().asString(), bob.username().getDomainPart().orElseThrow().asString()));
    }

    @Test
    void contactCreationShouldPublishAContactCreatedActivity() {
        String uid = UUID.randomUUID().toString();
        AddressBookURL addressBook = new AddressBookURL(bob.id(), "contacts");
        cardDavClient.upsertContact(bob.username(), addressBook, uid, vcard(uid)).block();

        Activity activity = awaitActivity("com.twake.contacts.contact.created.v1");

        assertThat(activity.contentType()).isEqualTo("application/cloudevents+json");
        assertThat(activity.messageId()).isEqualTo(activity.body().path("id").asText());
        assertThatJson(activity.body().toString()).isEqualTo("""
            {
              "specversion": "1.0",
              "id": "${json-unit.any-string}",
              "source": "twake://contacts",
              "type": "com.twake.contacts.contact.created.v1",
              "time": "${json-unit.any-string}",
              "subject": "addressbooks/%1$s/contacts/%2$s.vcf",
              "twakeactor": "%3$s",
              "twakedomain": "%4$s",
              "twakeresourceowner": "%3$s",
              "data": {
                "path": "addressbooks/%1$s/contacts/%2$s.vcf",
                "addressbook": "addressbooks/%1$s/contacts"
              }
            }
            """.formatted(bob.id().value(), uid, bob.username().asString(), bob.username().getDomainPart().orElseThrow().asString()));
    }

    @Test
    void eventUpdateShouldPublishNoActivity() throws Exception {
        String uid = UUID.randomUUID().toString();
        dav.upsertCalendar(bob, event(uid), uid);
        awaitActivity("com.twake.calendar.event.created.v1");

        dav.upsertCalendar(bob, event(uid).replace("SUMMARY:Planning", "SUMMARY:Planning moved"), uid);
        String marker = UUID.randomUUID().toString();
        dav.upsertCalendar(bob, event(marker), marker);

        Activity next = awaitActivity("com.twake.calendar.event.created.v1");
        assertThat(next.body().path("data").path("path").asText()).endsWith(marker + ".ics");
    }

    private Activity awaitActivity(String routingKey) {
        return AWAIT.until(() -> {
            try (Connection connection = connectionFactory().newConnection();
                 Channel channel = connection.createChannel()) {
                GetResponse response = channel.basicGet(FEED_QUEUE, true);
                if (response == null) {
                    return null;
                }
                assertThat(response.getEnvelope().getRoutingKey()).isEqualTo(routingKey);
                return new Activity(response.getEnvelope().getRoutingKey(), response.getProps().getContentType(),
                    response.getProps().getMessageId(), OBJECT_MAPPER.readTree(response.getBody()));
            }
        }, Objects::nonNull);
    }

    private static String event(String uid) {
        return """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Twake//Activity test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20361001T080000Z\r
            DTSTART:20361010T090000Z\r
            DTEND:20361010T100000Z\r
            SUMMARY:Planning\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid);
    }

    private static byte[] vcard(String uid) {
        return """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(uid).getBytes(StandardCharsets.UTF_8);
    }

    private long consumerCount(String queue) throws Exception {
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            return channel.consumerCount(queue);
        }
    }

    private ConnectionFactory connectionFactory() throws Exception {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setUri(rabbitMQConfiguration.getUri());
        connectionFactory.setUsername(USER);
        connectionFactory.setPassword(PASSWORD);
        return connectionFactory;
    }
}
