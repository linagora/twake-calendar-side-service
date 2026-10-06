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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQManagementAPI;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TestFixture;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.GetResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

class TwakeSpaceExtensionIntegrationTest {
    private static final String STARTABLE = "com.linagora.calendar.twakespace.TwakeSpaceStartable";
    private static final String HEALTH_CHECK = "com.linagora.calendar.twakespace.TwakeSpaceHealthCheck";
    private static final String SPACE_EXCHANGE = "test-space";
    private static final String QUEUE = "test-calendar-space";
    private static final String DEAD_LETTER_QUEUE = "test-calendar-space-dead-letter";
    private static final String ACTIVITY_EXCHANGE = "test-activity";
    private static final String FEED_QUEUE = "test-feed";
    private static final String USER = "calendar";
    private static final String PASSWORD = "calendar";
    private static final String UNREADABLE = "not json";
    private static final Domain DOMAIN = Domain.of("space.tld");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ConditionFactory AWAIT = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200));

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @TempDir
    Path workingDirectory;

    // RabbitMQ deletes vhosts asynchronously: reusing a name races with the previous test's deletion.
    private final String spaceVhost = "twake-space-" + UUID.randomUUID();
    // Sabre publishes on the vhost every test shares: a queue per test keeps a test from reading the previous one's events.
    private final String calendarQueue = "test-twake-space-calendar-" + UUID.randomUUID();
    private final String calendarDeadLetterQueue = calendarQueue + "-dead-letter";
    private final List<String> skippedActivities = new ArrayList<>();
    private TwakeCalendarGuiceServer server;
    private DavTestHelper dav;
    private CalDavClient calDavClient;
    private RabbitMQConfiguration rabbitMQConfiguration;
    private RabbitMQManagementAPI managementAPI;
    private RequestSpecification webAdmin;

    @BeforeEach
    void setUp() throws Exception {
        rabbitMQConfiguration = sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration();
        managementAPI = RabbitMQManagementAPI.from(rabbitMQConfiguration);
        managementAPI.addVhost(spaceVhost);
        assertThat(management("PUT", "/api/permissions/" + spaceVhost + "/" + USER, """
            {"configure": ".*", "write": ".*", "read": ".*"}""").statusCode()).isBetween(200, 299);

        Path conf = Files.createDirectories(workingDirectory.resolve("conf"));
        for (String file : List.of("configuration.properties", "jwt_privatekey", "jwt_publickey", "rabbitmq.properties", "webadmin.properties")) {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(file)) {
                Files.copy(in, conf.resolve(file));
            }
        }
        URI amqp = rabbitMQConfiguration.getUri();
        Files.writeString(conf.resolve("extensions.properties"), """
            guice.extension.startable=%s
            twakespace.exchange=%s
            twakespace.routing.keys=twake.space.created,twake.space.updated,twake.space.deleted,twake.space.member.#
            twakespace.queue=%s
            twakespace.dead.letter.queue=%s
            twakespace.activity.exchange=%s
            twakespace.calendar.queue=%s
            twakespace.calendar.dead.letter.queue=%s
            twakespace.rabbitmq.uri=amqp://%s:%s@%s:%d/%s
            twakespace.rabbitmq.management.uri=%s
            twakespace.rabbitmq.management.user=%s
            twakespace.rabbitmq.management.password=%s
            twakespace.rabbitmq.quorum.queues.enable=true
            twakespace.rabbitmq.quorum.queues.delivery.limit=10
            """.formatted(STARTABLE, SPACE_EXCHANGE, QUEUE, DEAD_LETTER_QUEUE, ACTIVITY_EXCHANGE, calendarQueue, calendarDeadLetterQueue,
            USER, PASSWORD, amqp.getHost(), amqp.getPort(), spaceVhost,
            rabbitMQConfiguration.getManagementUri(), USER, PASSWORD));
        Files.writeString(conf.resolve("healthcheck.properties"), "additional.healthchecks=" + HEALTH_CHECK);

        server = TwakeCalendarMain.createServer(TwakeCalendarConfiguration.builder()
                .workingDirectory(workingDirectory.toFile())
                .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
                .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
                .build())
            .overrideWith(List.of(AppTestHelper.OIDC_BY_PASS_MODULE,
                DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension)));
        server.start();
        server.getProbe(CalendarDataProbe.class).addDomain(DOMAIN);
        webAdmin = new RequestSpecBuilder()
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .setBasePath("/domains/" + DOMAIN.asString() + "/team-calendars")
            .build();
        AWAIT.untilAsserted(() -> assertThat(consumerCount(QUEUE)).isEqualTo(1));
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(ACTIVITY_EXCHANGE, BuiltinExchangeType.TOPIC, true);
            channel.queueDeclare(FEED_QUEUE, true, false, false, null);
            channel.queueBind(FEED_QUEUE, ACTIVITY_EXCHANGE, "#");
        }
        dav = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        management("DELETE", "/api/vhosts/" + spaceVhost, null);
        try (Connection connection = calendarConnectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            channel.queueDelete(calendarQueue);
            channel.queueDelete(calendarDeadLetterQueue);
        }
    }

    @Test
    void healthCheckShouldReportTheConsumerHealthy() {
        given().port(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .get("/healthcheck/checks/TwakeSpace")
        .then()
            .statusCode(200)
            .body("status", equalTo("healthy"));
    }

    @Test
    void spaceCreatedShouldPublishTheProvisionedTeamCalendar() throws Exception {
        String spaceId = UUID.randomUUID().toString();

        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));

        AWAIT.untilAsserted(() -> teamCalendar(spaceId));
        Delivery provisioned = AWAIT.until(this::nextActivity, Objects::nonNull);
        JsonNode event = OBJECT_MAPPER.readTree(provisioned.getBody());
        assertThat(provisioned.getEnvelope().getRoutingKey()).isEqualTo("com.twake.calendar.space.provisioned.v1");
        assertThat(provisioned.getProperties().getContentType()).isEqualTo("application/cloudevents+json");
        assertThat(event.path("specversion").asText()).isEqualTo("1.0");
        assertThat(event.path("type").asText()).isEqualTo("com.twake.calendar.space.provisioned.v1");
        assertThat(event.path("source").asText()).isEqualTo("twake://calendar");
        assertThat(event.path("twakeorg").asText()).isEqualTo("org");
        assertThat(event.path("id").asText()).isNotBlank();
        assertThat(event.path("time").asText()).isNotBlank();
        assertThat(event.path("data").path("space_id").asText()).isEqualTo(spaceId);
        assertThat(event.path("data").path("resource").path("kind").asText()).isEqualTo("calendar");
        assertThat(event.path("data").path("resource").path("id").asText()).isEqualTo(teamCalendar(spaceId).path("id").asText());
    }

    @Test
    void redeliveredSpaceCreatedShouldPublishTheSameProvisionedEventAgain() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String created = created(spaceId, "Marketing", member("alice", "admin"));
        publish("twake.space.created", created);
        JsonNode first = OBJECT_MAPPER.readTree(AWAIT.until(this::nextActivity, Objects::nonNull).getBody());

        publish("twake.space.created", created);

        JsonNode second = OBJECT_MAPPER.readTree(AWAIT.until(this::nextActivity, Objects::nonNull).getBody());
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(second.path("data")).isEqualTo(first.path("data"));
    }

    @Test
    void eventCreatedInTheTeamCalendarShouldPublishEventCreated() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        String teamCalendarId = teamCalendar(spaceId).path("id").asText();

        putEvent("alice", event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "bob"));

        JsonNode created = awaitActivity("com.twake.calendar.event.created.v1");
        assertThat(created.path("source").asText()).isEqualTo("twake://calendar");
        assertThat(created.path("subject").asText()).isEqualTo("event/uid-1");
        assertThat(created.path("twakeorg").asText()).isEqualTo("org");
        assertThat(created.path("twakeactor").asText()).isEqualTo("alice@space.tld");
        assertThat(created.path("data").path("object")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"type": "event", "id": "uid-1", "title": "Sprint planning", "container": {"kind": "calendar", "id": "%s"}}"""
            .formatted(teamCalendarId)));
        assertThat(created.path("data").path("state")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"start": "2026-10-10T09:00:00Z", "end": "2026-10-10T10:00:00Z", "allDay": false, "location": "Room 1",
             "rsvp": {"accepted": 0, "declined": 0, "tentative": 0, "pending": 1}}"""));
        assertThat(created.path("data").path("preview").asText()).isEqualTo("Room 1");
        assertThat(created.path("data").path("recipients")).isEqualTo(OBJECT_MAPPER.readTree("""
            [{"email": "bob@space.tld", "reason": "attendee"}]"""));
    }

    @Test
    void reschedulingShouldPublishEventRescheduledWithThePreviousTime() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        putEvent("alice", event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "bob"));
        awaitActivity("com.twake.calendar.event.created.v1");

        putEvent("alice", event("uid-1", "Sprint planning", "20261011T090000Z", "20261011T100000Z", "alice", "bob"));

        JsonNode rescheduled = awaitActivity("com.twake.calendar.event.rescheduled.v1");
        assertThat(rescheduled.path("data").path("state").path("start").asText()).isEqualTo("2026-10-11T09:00:00Z");
        assertThat(rescheduled.path("data").path("state").path("previous")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"start": "2026-10-10T09:00:00Z", "end": "2026-10-10T10:00:00Z"}"""));
        assertThat(rescheduled.path("data").path("recipients")).isEqualTo(OBJECT_MAPPER.readTree("""
            [{"email": "bob@space.tld", "reason": "attendee"}]"""));
    }

    @Test
    void renamingAnEventShouldPublishEventUpdatedWithoutRecipients() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        putEvent("alice", event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "bob"));
        awaitActivity("com.twake.calendar.event.created.v1");

        putEvent("alice", event("uid-1", "Sprint review", "20261010T090000Z", "20261010T100000Z", "alice", "bob"));

        JsonNode updated = awaitActivity("com.twake.calendar.event.updated.v1");
        assertThat(updated.path("data").path("object").path("title").asText()).isEqualTo("Sprint review");
        assertThat(updated.path("data").has("recipients")).isFalse();
        assertThat(updated.path("data").path("state").has("previous")).isFalse();
    }

    @Test
    void memberAcceptingShouldPublishEventAccepted() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        String ics = event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "bob");
        putEvent("alice", ics);
        awaitActivity("com.twake.calendar.event.created.v1");

        putEvent("bob", ics.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=bob", "PARTSTAT=ACCEPTED;CN=bob"));

        JsonNode accepted = awaitActivity("com.twake.calendar.event.accepted.v1");
        assertThat(accepted.path("twakeactor").asText()).isEqualTo("bob@space.tld");
        assertThat(accepted.path("data").path("state").path("rsvp")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"accepted": 1, "declined": 0, "tentative": 0, "pending": 0}"""));
        assertThat(accepted.path("data").path("recipients")).isEqualTo(OBJECT_MAPPER.readTree("""
            [{"email": "alice@space.tld", "reason": "attendee"}]"""));
    }

    @Test
    void attendeeOutsideTheSpaceDecliningShouldPublishEventDeclined() throws Exception {
        provisionedSpace(member("alice", "admin"));
        server.getProbe(CalendarDataProbe.class).addUser(username("carol"), "secret");
        putEvent("alice", event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "carol"));
        awaitActivity("com.twake.calendar.event.created.v1");
        String carolEvent = AWAIT.until(() -> personalEventIds("carol"), ids -> !ids.isEmpty()).getFirst();

        dav.upsertCalendar(username("carol"), personalEventUri("carol", carolEvent),
            event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "carol")
                .replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=carol", "PARTSTAT=DECLINED;CN=carol")).block();

        JsonNode declined = awaitActivity("com.twake.calendar.event.declined.v1");
        assertThat(declined.path("twakeactor").asText()).isEqualTo("carol@space.tld");
        assertThat(declined.path("data").path("object").path("id").asText()).isEqualTo("uid-1");
        assertThat(declined.path("data").path("state").path("rsvp")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"accepted": 0, "declined": 1, "tentative": 0, "pending": 0}"""));
        assertThat(declined.path("data").path("recipients")).isEqualTo(OBJECT_MAPPER.readTree("""
            [{"email": "alice@space.tld", "reason": "attendee"}]"""));
    }

    @Test
    void attendeeOutsideTheSpaceProposingANewTimeShouldPublishEventProposed() throws Exception {
        provisionedSpace(member("alice", "admin"));
        server.getProbe(CalendarDataProbe.class).addUser(username("carol"), "secret");
        OpenPaaSUser carol = server.getProbe(CalendarDataProbe.class).getUser(username("carol"));
        putEvent("alice", event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "carol"));
        awaitActivity("com.twake.calendar.event.created.v1");
        String carolEvent = AWAIT.until(() -> personalEventIds("carol"), ids -> !ids.isEmpty()).getFirst();

        String counter = event("uid-1", "Sprint planning", "20261012T140000Z", "20261012T150000Z", "alice", "carol")
            .replace("BEGIN:VEVENT", "METHOD:COUNTER\r\nBEGIN:VEVENT");
        dav.postCounter(carol, carolEvent, new DavTestHelper.CounterRequest(counter, "carol@space.tld", "alice@space.tld", "uid-1", 0)).block();

        JsonNode proposed = awaitActivity("com.twake.calendar.event.proposed.v1");
        assertThat(proposed.path("twakeactor").asText()).isEqualTo("carol@space.tld");
        assertThat(proposed.path("data").path("state").path("start").asText()).isEqualTo("2026-10-10T09:00:00Z");
        assertThat(proposed.path("data").path("state").path("proposed")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"start": "2026-10-12T14:00:00Z", "end": "2026-10-12T15:00:00Z", "by": "carol@space.tld"}"""));
    }

    @Test
    void eventOfAPersonalCalendarShouldPublishNoActivity() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        OpenPaaSUser alice = server.getProbe(CalendarDataProbe.class).getUser(username("alice"));
        dav.upsertCalendar(alice, event("uid-personal", "Dentist", "20261010T090000Z", "20261010T100000Z", "alice", "bob"), "uid-personal");

        putEvent("alice", event("uid-1", "Sprint planning", "20261010T090000Z", "20261010T100000Z", "alice", "bob"));

        assertThat(awaitActivity("com.twake.calendar.event.created.v1").path("subject").asText()).isEqualTo("event/uid-1");
        assertThat(skippedActivities).containsOnly("com.twake.calendar.space.provisioned.v1");
    }

    @Test
    void memberWithAnUnknownRoleShouldReadTheTeamCalendar() throws Exception {
        String spaceId = UUID.randomUUID().toString();

        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), member("bob", "owner")));

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write"),
            Map.entry("bob@space.tld", "dav:read")));
    }

    @Test
    void spaceQueueShouldBeAQuorumQueueWithSingleActiveConsumerAndDeadLetter() {
        assertThat(managementAPI.queueDetails(spaceVhost, QUEUE).getArguments())
            .containsEntry("x-queue-type", "quorum")
            .containsEntry("x-single-active-consumer", "true")
            .containsEntry("x-dead-letter-exchange", DEAD_LETTER_QUEUE)
            .containsEntry("x-delivery-limit", "10");
    }

    // Unreadable events are dead lettered, so the dead letter queue counts the events the queue received.
    @Test
    void spaceQueueShouldReceiveEverySpaceAndMemberEvent() throws Exception {
        List<String> routingKeys = List.of("twake.space.created", "twake.space.updated", "twake.space.deleted",
            "twake.space.member.added", "twake.space.member.removed", "twake.space.member.role.changed");
        for (String routingKey : routingKeys) {
            publish(routingKey, UNREADABLE);
        }

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(routingKeys.size()));
    }

    @Test
    void spaceQueueShouldNotReceiveGroupEvents() throws Exception {
        publish("twake.space.group.linked", UNREADABLE);
        awaitConsumed();

        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void spaceEventsShouldBeConsumedAfterTheConnectionIsLost() throws Exception {
        AWAIT.until(() -> closeConnectionsOf(spaceVhost) > 0);
        AWAIT.untilAsserted(() -> assertThat(consumerCount(QUEUE)).isEqualTo(1));

        publish("twake.space.created", UNREADABLE);

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    @Test
    void spaceCreatedShouldProvisionATeamCalendarNamedAfterTheSpace() throws Exception {
        String spaceId = UUID.randomUUID().toString();

        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));

        AWAIT.untilAsserted(() -> assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo("Marketing"));
    }

    @Test
    void spaceCreatedShouldShareTheTeamCalendarFollowingEachRole() throws Exception {
        String spaceId = UUID.randomUUID().toString();

        publish("twake.space.created", created(spaceId, "Marketing",
            member("alice", "admin"), member("bob", "editor"), member("carol", "viewer")));

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write"),
            Map.entry("bob@space.tld", "dav:read-write"),
            Map.entry("carol@space.tld", "dav:read")));
    }

    @Test
    void redeliveredSpaceCreatedShouldKeepASingleTeamCalendar() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String created = created(spaceId, "Marketing", member("alice", "admin"));

        publish("twake.space.created", created);
        publish("twake.space.created", created);
        awaitConsumed();

        assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo("Marketing");
    }

    @Test
    void memberAddedShouldShareTheTeamCalendarFollowingTheRole() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));

        publish("twake.space.member.added", memberEvent(spaceId, member("bob", "viewer")));

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write"),
            Map.entry("bob@space.tld", "dav:read")));
    }

    @Test
    void memberRoleChangedShouldChangeTheShare() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), member("bob", "editor")));

        publish("twake.space.member.role.changed", memberEvent(spaceId, member("bob", "viewer")));

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write"),
            Map.entry("bob@space.tld", "dav:read")));
    }

    @Test
    void memberRemovedShouldRevokeTheShare() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), member("bob", "editor")));

        publish("twake.space.member.removed", memberEvent(spaceId, member("bob", "editor")));

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write")));
    }

    @Test
    void spaceUpdatedShouldRenameTheTeamCalendar() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));

        publish("twake.space.updated", """
            {"organizationId": "org", "id": "%s", "name": "Sales", "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(spaceId, DOMAIN.asString()));

        AWAIT.untilAsserted(() -> assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo("Sales"));
    }

    @Test
    void spaceDeletedShouldRecordTheDeletion() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));

        publish("twake.space.deleted", """
            {"organizationId": "org", "id": "%s", "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(spaceId, DOMAIN.asString()));
        awaitConsumed();

        Document space = Mono.from(sabreDavExtension.dockerSabreDavSetup().getMongoDB()
            .getCollection("twake_spaces").find(new Document("_id", spaceId)).first()).block();
        assertThat(space.getDate("deletion")).isNotNull();
    }

    @Test
    void spaceDeletedShouldRemoveEveryMember() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), member("bob", "viewer")));

        publish("twake.space.deleted", """
            {"organizationId": "org", "id": "%s", "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(spaceId, DOMAIN.asString()));
        awaitConsumed();

        assertThat(members(spaceId)).isEmpty();
    }

    @Test
    void eventOfASpaceWithoutTeamCalendarShouldBeDeadLettered() throws Exception {
        publish("twake.space.member.added", memberEvent(UUID.randomUUID().toString(), member("bob", "viewer")));

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    @Test
    void replayedSpaceCreatedShouldNotUndoLaterEvents() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String created = created(spaceId, "Marketing", member("alice", "admin"), member("bob", "editor"));
        publish("twake.space.created", created);
        publish("twake.space.member.removed", memberEvent(spaceId, member("bob", "editor")));

        publish("twake.space.created", created);
        awaitConsumed();

        assertThat(members(spaceId)).containsOnly(Map.entry("alice@space.tld", "dav:read-write"));
    }

    @Test
    void spaceOfADomainUnknownToTheSideServiceShouldBeDeadLettered() throws Exception {
        publish("twake.space.created", createdIn(Domain.of("unknown.tld"), UUID.randomUUID().toString()));

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    @Test
    void failedEventShouldBeRetriedBeforeBeingDeadLettered() throws Exception {
        Domain lateDomain = Domain.of("late.tld");
        String spaceId = UUID.randomUUID().toString();
        Logger startableLogger = (Logger) LoggerFactory.getLogger(STARTABLE);
        Level level = startableLogger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        startableLogger.setLevel(Level.WARN);
        startableLogger.addAppender(logs);
        try {
            publish("twake.space.created", createdIn(lateDomain, spaceId));
            AWAIT.until(() -> logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("Failed to handle twake.space.created for space " + spaceId)));

            server.getProbe(CalendarDataProbe.class).addDomain(lateDomain);

            AWAIT.untilAsserted(() -> assertThat(teamCalendarNames(lateDomain)).containsExactly(spaceId));
            assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
        } finally {
            startableLogger.detachAppender(logs);
            startableLogger.setLevel(level);
        }
    }

    @Test
    void organizationDomainShouldPrevailOverTheAdminDomain() throws Exception {
        Domain organizationDomain = Domain.of("organization.tld");
        server.getProbe(CalendarDataProbe.class).addDomain(organizationDomain);
        String spaceId = UUID.randomUUID().toString();

        publish("twake.space.created", createdIn(organizationDomain, spaceId));

        AWAIT.untilAsserted(() -> assertThat(teamCalendarNames(organizationDomain)).containsExactly(spaceId));
    }

    @Test
    void memberOutsideTheSpaceDomainShouldNotBeShared() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String outsider = """
            {"uuid": "1", "username": "mallory", "email": "mallory@other.tld", "firstName": "Mallory", "lastName": "Doe", "role": "editor"}""";

        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), outsider));
        publish("twake.space.member.added", memberEvent(spaceId, outsider));
        awaitConsumed();

        assertThat(members(spaceId)).containsOnly(Map.entry("alice@space.tld", "dav:read-write"));
    }

    @Test
    void removingAMemberOutsideTheSpaceDomainShouldBeAcknowledged() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String outsider = """
            {"uuid": "1", "username": "mallory", "email": "mallory@outside.tld", "firstName": "Mallory", "lastName": "Doe", "role": "editor"}""";
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), outsider));

        publish("twake.space.member.removed", memberEvent(spaceId, outsider));
        awaitConsumed();

        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void memberAddedToADeletedSpaceShouldNotBeShared() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));
        publish("twake.space.deleted", """
            {"organizationId": "org", "id": "%s", "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(spaceId, DOMAIN.asString()));

        publish("twake.space.member.added", memberEvent(spaceId, member("bob", "viewer")));
        awaitConsumed();

        assertThat(members(spaceId)).isEmpty();
        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void spaceCreatedWithoutNameShouldBeDeadLettered() throws Exception {
        publish("twake.space.created", """
            {"organizationId": "org", "id": "%s", "members": [%s], "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(UUID.randomUUID(), member("alice", "admin"), DOMAIN.asString()));

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    // Events are consumed in order: once this space has its team calendar, the events before were handled.
    private void awaitConsumed() throws Exception {
        String sentinel = UUID.randomUUID().toString();
        publish("twake.space.created", created(sentinel, "Sentinel", member("alice", "admin")));
        AWAIT.untilAsserted(() -> teamCalendar(sentinel));
    }

    private Map<String, String> members(String spaceId) throws Exception {
        Map<String, String> members = new HashMap<>();
        String body = given(webAdmin).get("/{id}/members", teamCalendar(spaceId).path("id").asText())
            .then().statusCode(200).extract().body().asString();
        for (JsonNode member : OBJECT_MAPPER.readTree(body)) {
            members.put(member.path("username").asText(), member.path("davRight").asText());
        }
        return members;
    }

    private JsonNode teamCalendar(String spaceId) throws Exception {
        JsonNode found = null;
        for (JsonNode teamCalendar : OBJECT_MAPPER.readTree(given(webAdmin).get().then().statusCode(200).extract().body().asString())) {
            if (teamCalendar.path("name").asText().equals(spaceId)) {
                assertThat(found).as("team calendars named %s", spaceId).isNull();
                found = teamCalendar;
            }
        }
        assertThat(found).as("team calendar named %s", spaceId).isNotNull();
        return found;
    }

    private List<String> teamCalendarNames(Domain domain) throws Exception {
        List<String> names = new ArrayList<>();
        String body = given(webAdmin).basePath("/domains/" + domain.asString() + "/team-calendars")
            .get().then().statusCode(200).extract().body().asString();
        for (JsonNode teamCalendar : OBJECT_MAPPER.readTree(body)) {
            names.add(teamCalendar.path("name").asText());
        }
        return names;
    }

    // The admin's domain is the test domain, so the calendar can only land in organizationDomain through that field.
    private static String createdIn(Domain organizationDomain, String spaceId) {
        return """
            {"organizationId": "org", "organizationDomain": "%s", "id": "%s", "name": "Marketing",
             "members": [%s], "groups": [], "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(organizationDomain.asString(), spaceId, member("alice", "admin"), DOMAIN.asString());
    }

    private static String created(String spaceId, String name, String... members) {
        return """
            {"organizationId": "org", "id": "%s", "name": "%s", "members": [%s], "groups": [],
             "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(spaceId, name, String.join(",", members), DOMAIN.asString());
    }

    private static String memberEvent(String spaceId, String member) {
        return """
            {"organizationId": "org", "id": "%s", "members": [%s], "actor": "alice@%s", "timestamp": "2026-10-06T10:00:00.000Z"}"""
            .formatted(spaceId, member, DOMAIN.asString());
    }

    private static String member(String username, String role) {
        return """
            {"uuid": "%s", "username": "%s", "email": "%s@%s", "firstName": "%s", "lastName": "Doe", "role": "%s"}"""
            .formatted(UUID.randomUUID(), username, username, DOMAIN.asString(), username, role);
    }

    private void publish(String routingKey, String body) throws Exception {
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            channel.basicPublish(SPACE_EXCHANGE, routingKey, new AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Delivery nextActivity() throws Exception {
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            GetResponse response = channel.basicGet(FEED_QUEUE, true);
            if (response == null) {
                return null;
            }
            return new Delivery(response.getEnvelope(), response.getProps(), response.getBody());
        }
    }

    private String provisionedSpace(String... members) throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", members));
        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).hasSize(members.length));
        return spaceId;
    }

    private JsonNode awaitActivity(String type) {
        return AWAIT.until(() -> {
            Delivery delivery;
            while ((delivery = nextActivity()) != null) {
                JsonNode event = OBJECT_MAPPER.readTree(delivery.getBody());
                assertThat(delivery.getEnvelope().getRoutingKey()).isEqualTo(event.path("type").asText());
                if (event.path("type").asText().equals(type)) {
                    return event;
                }
                skippedActivities.add(event.path("type").asText());
            }
            return null;
        }, Objects::nonNull);
    }

    // Members reach the team calendar through their own instance of it, as the calendar frontend does.
    private void putEvent(String username, String ics) throws Exception {
        OpenPaaSId userId = server.getProbe(CalendarDataProbe.class).userId(username(username));
        CalendarURL instance = calDavClient.findUserCalendars(username(username), userId)
            .filter(calendar -> !calendar.calendarId().equals(userId))
            .blockFirst();
        String uid = ics.substring(ics.indexOf("UID:") + 4, ics.indexOf("\r\n", ics.indexOf("UID:")));
        dav.upsertCalendar(username(username), URI.create(instance.asUri() + "/" + uid + ".ics"), ics).block();
    }

    private List<String> personalEventIds(String username) {
        OpenPaaSId userId = server.getProbe(CalendarDataProbe.class).userId(username(username));
        return calDavClient.findUserCalendarEventIds(username(username), CalendarURL.from(userId)).collectList().block();
    }

    private URI personalEventUri(String username, String eventId) {
        OpenPaaSId userId = server.getProbe(CalendarDataProbe.class).userId(username(username));
        return URI.create(CalendarURL.from(userId).asUri() + "/" + eventId + ".ics");
    }

    private static Username username(String localPart) {
        return Username.fromLocalPartWithDomain(localPart, DOMAIN);
    }

    private static String event(String uid, String summary, String start, String end, String organizer, String... attendees) {
        StringBuilder attendeeLines = new StringBuilder("ATTENDEE;PARTSTAT=ACCEPTED;CN=%s:mailto:%s@%s\r\n".formatted(organizer, organizer, DOMAIN.asString()));
        for (String attendee : attendees) {
            if (!attendee.equals(organizer)) {
                attendeeLines.append("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=%s:mailto:%s@%s\r\n".formatted(attendee, attendee, DOMAIN.asString()));
            }
        }
        return """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//TwakeSpace extension test\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20261007T000000Z\r
            DTSTART:%s\r
            DTEND:%s\r
            SUMMARY:%s\r
            LOCATION:Room 1\r
            ORGANIZER;CN=%s:mailto:%s@%s\r
            %sEND:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, start, end, summary, organizer, organizer, DOMAIN.asString(), attendeeLines);
    }

    // The management API refreshes its counts every few seconds, AMQP answers the current ones.
    private long messageCount(String queue) throws Exception {
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            return channel.messageCount(queue);
        }
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
        connectionFactory.setVirtualHost(spaceVhost);
        return connectionFactory;
    }

    private ConnectionFactory calendarConnectionFactory() throws Exception {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setUri(rabbitMQConfiguration.getUri());
        connectionFactory.setUsername(USER);
        connectionFactory.setPassword(PASSWORD);
        return connectionFactory;
    }

    private int closeConnectionsOf(String vhost) throws Exception {
        int closed = 0;
        for (JsonNode connection : OBJECT_MAPPER.readTree(management("GET", "/api/vhosts/" + vhost + "/connections", null).body())) {
            String name = URLEncoder.encode(connection.get("name").asText(), StandardCharsets.UTF_8).replace("+", "%20");
            if (management("DELETE", "/api/connections/" + name, null).statusCode() == 204) {
                closed++;
            }
        }
        return closed;
    }

    private HttpResponse<String> management(String method, String path, String body) throws Exception {
        String credentials = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(HttpRequest.newBuilder(rabbitMQConfiguration.getManagementUri().resolve(path))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
