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
import java.util.UUID;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQManagementAPI;
import org.apache.james.core.Domain;
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
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

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
    private TwakeCalendarGuiceServer server;
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
            twakespace.rabbitmq.uri=amqp://%s:%s@%s:%d/%s
            twakespace.rabbitmq.management.uri=%s
            twakespace.rabbitmq.management.user=%s
            twakespace.rabbitmq.management.password=%s
            twakespace.rabbitmq.quorum.queues.enable=true
            twakespace.rabbitmq.quorum.queues.delivery.limit=10
            """.formatted(STARTABLE, SPACE_EXCHANGE, QUEUE, DEAD_LETTER_QUEUE,
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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        management("DELETE", "/api/vhosts/" + spaceVhost, null);
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
