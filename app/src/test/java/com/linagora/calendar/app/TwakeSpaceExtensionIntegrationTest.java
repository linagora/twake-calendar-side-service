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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.util.concurrency.ConcurrentTestRunner;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.amqp.meet.MeetConfiguration;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TestFixture;
import com.linagora.calendar.twakespace.SpaceTeamCalendars;
import com.linagora.calendar.twakespace.TwakeSpaceConfiguration;
import com.linagora.calendar.twakespace.amqp.CalendarActivityConsumer;
import com.linagora.calendar.twakespace.amqp.MeetingRequestConsumer;
import com.linagora.calendar.twakespace.amqp.SpaceEventConsumer;
import com.linagora.calendar.twakespace.model.DuplicateSpaceTeamCalendarException;
import com.linagora.calendar.twakespace.model.SpaceId;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.GetResponse;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

class TwakeSpaceExtensionIntegrationTest {
    private record Slot(String start, String end) {
    }

    // Sabre sends no reply for a past event, so the slots stay in the future.
    private static final Slot PLANNED = new Slot("20361010T090000Z", "20361010T100000Z");
    private static final Slot MOVED = new Slot("20361011T090000Z", "20361011T100000Z");
    private static final Slot PROPOSED = new Slot("20361012T140000Z", "20361012T150000Z");
    private static final String SPACE_EXCHANGE = TwakeSpaceConfiguration.DEFAULT_SPACE_EXCHANGE;
    private static final String QUEUE = SpaceEventConsumer.QUEUE;
    private static final String DEAD_LETTER_QUEUE = SpaceEventConsumer.DEAD_LETTER_QUEUE;
    private static final String ACTIVITY_EXCHANGE = TwakeSpaceConfiguration.DEFAULT_ACTIVITY_EXCHANGE;
    private static final String FEED_QUEUE = "test-feed";
    private static final String USER = "calendar";
    private static final String PASSWORD = "calendar";
    private static final String UNREADABLE = "not json";
    private static final Domain DOMAIN = Domain.of("space.tld");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicLong EVENT_COUNT = new AtomicLong();
    private static final ConditionFactory AWAIT = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200));
    private static final String COMMAND_EXCHANGE = TwakeSpaceConfiguration.DEFAULT_COMMAND_EXCHANGE;
    private static final String MEET_TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String MEET_ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String ROOM = "abc-defg-hij";
    private static final ClientAndServer MEET = ClientAndServer.startClientAndServer(0);
    private static final String MEET_URL = "http://127.0.0.1:" + MEET.getLocalPort();

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @TempDir
    Path workingDirectory;

    private final List<String> skippedActivities = new ArrayList<>();
    private TwakeCalendarGuiceServer server;
    private DavTestHelper dav;
    private CalDavClient calDavClient;
    private RabbitMQConfiguration rabbitMQConfiguration;
    private RequestSpecification webAdmin;

    // Without twp.rabbitmq.uri, the TWP connection carrying space events is the side service's one.
    @BeforeEach
    void setUp() throws Exception {
        rabbitMQConfiguration = sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration();
        Path conf = Files.createDirectories(workingDirectory.resolve("conf"));
        for (String file : List.of("configuration.properties", "jwt_privatekey", "jwt_publickey", "rabbitmq.properties", "webadmin.properties")) {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(file)) {
                Files.copy(in, conf.resolve(file));
            }
        }

        server = TwakeCalendarMain.createServer(TwakeCalendarConfiguration.builder()
                .workingDirectory(workingDirectory.toFile())
                .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
                .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
                .enableTwpSetting()
                .enableMeet()
                .enableTwakeSpace()
                .build())
            .overrideWith(List.of(AppTestHelper.OIDC_BY_PASS_MODULE,
                DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
                binder -> binder.bind(MeetConfiguration.class).toInstance(new MeetConfiguration("test-client-id", "test-client-secret",
                    URI.create(MEET_URL), false, Duration.ofSeconds(5), Optional.empty())),
                binder -> {
                    Multibinder<GuiceProbe> probes = Multibinder.newSetBinder(binder, GuiceProbe.class);
                    probes.addBinding().to(MonitoredRabbitMQProbe.class);
                    probes.addBinding().to(SpaceTeamCalendarsProbe.class);
                }));
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
        MEET.reset();
        MEET.when(request().withMethod("POST").withPath(MEET_TOKEN_PATH))
            .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("{\"access_token\":\"jwt\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        MEET.when(request().withMethod("POST").withPath(MEET_ROOMS_PATH))
            .respond(response().withStatusCode(201).withContentType(MediaType.APPLICATION_JSON)
                .withBody("{\"id\":\"550e8400-e29b-41d4-a716-446655440000\",\"slug\":\"%s\",\"url\":\"%s/%s\"}".formatted(ROOM, MEET_URL, ROOM)));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            for (String queue : List.of(QUEUE, DEAD_LETTER_QUEUE, CalendarActivityConsumer.QUEUE, CalendarActivityConsumer.DEAD_LETTER_QUEUE,
                MeetingRequestConsumer.QUEUE, MeetingRequestConsumer.DEAD_LETTER_QUEUE, FEED_QUEUE)) {
                channel.queueDelete(queue);
            }
        }
    }

    @Test
    void rabbitMQHealthChecksShouldMonitorTheTwakeSpaceQueues() {
        MonitoredRabbitMQProbe probe = server.getProbe(MonitoredRabbitMQProbe.class);

        assertThat(probe.consumedQueues()).contains(QUEUE, CalendarActivityConsumer.QUEUE, MeetingRequestConsumer.QUEUE);
        assertThat(probe.deadLetterQueues()).contains(DEAD_LETTER_QUEUE, CalendarActivityConsumer.DEAD_LETTER_QUEUE,
            MeetingRequestConsumer.DEAD_LETTER_QUEUE);
    }

    @Test
    void meetingRequestShouldPublishTheMeetingWithItsRoom() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"), member("bob", "viewer"));
        String teamCalendarId = teamCalendar(spaceId).path("id").asText();

        publishCommand(meetingRequest("alice", teamCalendarId, "meeting-1"));

        JsonNode created = awaitActivity("com.twake.calendar.event.created.v1");
        assertThat(created.path("subject").asText()).isEqualTo("event/meeting-1");
        assertThat(created.path("twakeactor").asText()).isEqualTo("alice@space.tld");
        assertThat(created.path("data").path("object")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"type": "event", "id": "meeting-1", "title": "Design review", "container": {"kind": "calendar", "id": "%s"}}"""
            .formatted(teamCalendarId)));
        assertThat(created.path("data").path("state").path("meeting")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"room": "%s"}""".formatted(ROOM)));
        assertThat(created.path("data").path("state").path("start").asText()).isEqualTo("2036-10-08T10:32:00Z");
        assertThat(created.path("data").path("state").path("end").asText()).isEqualTo("2036-10-08T11:02:00Z");
        assertThat(created.path("data").has("recipients")).isFalse();
        assertThat(MEET.retrieveRecordedRequests(request().withMethod("POST").withPath(MEET_TOKEN_PATH)))
            .singleElement()
            .satisfies(token -> assertThat(token.getBodyAsString().replaceAll("\\s", "")).contains("\"scope\":\"alice@space.tld\""));
    }

    @Test
    void meetingRequestShouldPutTheMeetingInTheTeamCalendar() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"), member("bob", "viewer"));
        String teamCalendarId = teamCalendar(spaceId).path("id").asText();

        publishCommand(meetingRequest("alice", teamCalendarId, "meeting-1"));

        awaitActivity("com.twake.calendar.event.created.v1");
        String meeting = calDavClient.fetchCalendarEvent(username("bob"),
                URI.create(CalendarURL.from(new OpenPaaSId(teamCalendarId)).asUri() + "/meeting-1.ics"))
            .block().calendarData().toString();
        assertThat(meeting)
            .contains("DTSTART;TZID=Europe/Paris:20361008T123200")
            .contains("SUMMARY:Design review")
            .contains("DESCRIPTION:Last pass on the mockups")
            .containsPattern("ORGANIZER[^:]*:mailto:alice@space.tld")
            .contains("X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:%s/%s".formatted(MEET_URL, ROOM))
            .doesNotContain("ATTENDEE");
    }

    @Test
    void meetingRequestedAgainShouldCreateASingleMeeting() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"));
        String teamCalendarId = teamCalendar(spaceId).path("id").asText();
        publishCommand(meetingRequest("alice", teamCalendarId, "meeting-1"));
        awaitActivity("com.twake.calendar.event.created.v1");

        publishCommand(meetingRequest("alice", teamCalendarId, "meeting-1"));
        awaitMeetingsHandled(teamCalendarId);

        assertThat(MEET.retrieveRecordedRequests(request().withMethod("POST").withPath(MEET_ROOMS_PATH))).hasSize(2);
        assertThat(messageCount(MeetingRequestConsumer.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void meetingRequestOfAViewerShouldCreateNothing() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"), member("bob", "viewer"));
        String teamCalendarId = teamCalendar(spaceId).path("id").asText();

        publishCommand(meetingRequest("bob", teamCalendarId, "meeting-1"));
        awaitMeetingsHandled(teamCalendarId);

        assertThat(MEET.retrieveRecordedRequests(request().withMethod("POST").withPath(MEET_ROOMS_PATH))).hasSize(1);
        assertThat(calDavClient.calendarReportByUid(username("alice"), new OpenPaaSId(teamCalendarId), "meeting-1").blockOptional()).isEmpty();
        assertThat(messageCount(MeetingRequestConsumer.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void meetingRequestOfANonMemberShouldCreateNothing() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"));
        String teamCalendarId = teamCalendar(spaceId).path("id").asText();
        provisionedSpace(member("carol", "admin"));

        publishCommand(meetingRequest("carol", teamCalendarId, "meeting-1"));
        awaitMeetingsHandled(teamCalendarId);

        assertThat(MEET.retrieveRecordedRequests(request().withMethod("POST").withPath(MEET_ROOMS_PATH))).hasSize(1);
        assertThat(messageCount(MeetingRequestConsumer.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void unreadableMeetingRequestShouldGoToTheDeadLetterQueue() throws Exception {
        publishCommand(UNREADABLE);

        AWAIT.untilAsserted(() -> assertThat(messageCount(MeetingRequestConsumer.DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    @Test
    void meetingRequestShouldGoToTheDeadLetterQueueWhenMeetFails() throws Exception {
        String spaceId = provisionedSpace(member("alice", "admin"));
        MEET.reset();
        MEET.when(request().withMethod("POST").withPath(MEET_TOKEN_PATH)).respond(response().withStatusCode(503));

        publishCommand(meetingRequest("alice", teamCalendar(spaceId).path("id").asText(), "meeting-1"));

        AWAIT.untilAsserted(() -> assertThat(messageCount(MeetingRequestConsumer.DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    @Test
    void eventLinkedToAMeetRoomShouldCarryTheRoom() throws Exception {
        provisionedSpace(member("alice", "admin"));
        String ics = event("uid-1", "Sprint planning", PLANNED);
        putEvent("alice", ics);
        assertThat(awaitActivity("com.twake.calendar.event.created.v1").path("data").path("state").has("meeting")).isFalse();

        putEvent("alice", ics.replace("LOCATION:Room 1\r\n", "LOCATION:Room 1\r\nX-OPENPAAS-VIDEOCONFERENCE:%s/%s\r\n".formatted(MEET_URL, ROOM)));

        assertThat(awaitActivity("com.twake.calendar.event.updated.v1").path("data").path("state").path("meeting")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"room": "%s"}""".formatted(ROOM)));
    }

    @Test
    void eventLinkedToAnotherVisioShouldCarryNoRoom() throws Exception {
        provisionedSpace(member("alice", "admin"));

        putEvent("alice", event("uid-1", "Sprint planning", PLANNED)
            .replace("LOCATION:Room 1\r\n", "LOCATION:Room 1\r\nX-OPENPAAS-VIDEOCONFERENCE:https://visio.other.tld/%s\r\n".formatted(ROOM)));

        assertThat(awaitActivity("com.twake.calendar.event.created.v1").path("data").path("state").has("meeting")).isFalse();
    }

    @Test
    void concurrentFindOrCreateShouldMakeASingleTeamCalendar() throws Exception {
        SpaceId spaceId = new SpaceId(UUID.randomUUID().toString());
        SpaceTeamCalendarsProbe probe = server.getProbe(SpaceTeamCalendarsProbe.class);

        ConcurrentTestRunner.builder()
            .reactorOperation((threadNumber, step) -> probe.findOrCreate(DOMAIN, spaceId, Optional.of("Marketing")).then())
            .threadCount(10)
            .operationCount(1)
            .runSuccessfullyWithin(Duration.ofMinutes(1));

        assertThat(teamCalendar(spaceId.value()).path("id").asText()).isEqualTo(SpaceTeamCalendars.teamCalendarId(spaceId).value());
    }

    @Test
    void findShouldFailWhenASpaceHasTwoTeamCalendars() {
        SpaceId spaceId = new SpaceId(UUID.randomUUID().toString());
        for (String displayName : List.of("Marketing", "Marketing again")) {
            given(webAdmin).body("{\"name\": \"%s\", \"displayName\": \"%s\"}".formatted(spaceId.value(), displayName))
                .post().then().statusCode(201);
        }

        assertThatThrownBy(() -> server.getProbe(SpaceTeamCalendarsProbe.class).find(DOMAIN, spaceId).block())
            .isInstanceOf(DuplicateSpaceTeamCalendarException.class);
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

        putEvent("alice", event("uid-1", "Sprint planning", PLANNED,"bob"));

        JsonNode created = awaitActivity("com.twake.calendar.event.created.v1");
        assertThat(created.path("source").asText()).isEqualTo("twake://calendar");
        assertThat(created.path("subject").asText()).isEqualTo("event/uid-1");
        assertThat(created.path("twakeorg").asText()).isEqualTo("org");
        assertThat(created.path("twakeactor").asText()).isEqualTo("alice@space.tld");
        assertThat(created.path("data").path("object")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"type": "event", "id": "uid-1", "title": "Sprint planning", "container": {"kind": "calendar", "id": "%s"}}"""
            .formatted(teamCalendarId)));
        assertThat(created.path("data").path("state")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"start": "2036-10-10T09:00:00Z", "end": "2036-10-10T10:00:00Z", "allDay": false, "location": "Room 1",
             "rsvp": {"accepted": 0, "declined": 0, "tentative": 0, "pending": 1}}"""));
        assertThat(created.path("data").path("preview").asText()).isEqualTo("Room 1");
        assertThat(created.path("data").path("recipients")).isEqualTo(OBJECT_MAPPER.readTree("""
            [{"email": "bob@space.tld", "reason": "attendee"}]"""));
    }

    @Test
    void reschedulingShouldPublishEventRescheduledWithThePreviousTime() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        putEvent("alice", event("uid-1", "Sprint planning", PLANNED,"bob"));
        awaitActivity("com.twake.calendar.event.created.v1");

        putEvent("alice", event("uid-1", "Sprint planning", MOVED, "bob"));

        JsonNode rescheduled = awaitActivity("com.twake.calendar.event.rescheduled.v1");
        assertThat(rescheduled.path("data").path("state").path("start").asText()).isEqualTo("2036-10-11T09:00:00Z");
        assertThat(rescheduled.path("data").path("state").path("previous")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"start": "2036-10-10T09:00:00Z", "end": "2036-10-10T10:00:00Z"}"""));
        assertThat(rescheduled.path("data").path("recipients")).isEqualTo(OBJECT_MAPPER.readTree("""
            [{"email": "bob@space.tld", "reason": "attendee"}]"""));
    }

    @Test
    void renamingAnEventShouldPublishEventUpdatedWithoutRecipients() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        putEvent("alice", event("uid-1", "Sprint planning", PLANNED,"bob"));
        awaitActivity("com.twake.calendar.event.created.v1");

        putEvent("alice", event("uid-1", "Sprint review", PLANNED, "bob"));

        JsonNode updated = awaitActivity("com.twake.calendar.event.updated.v1");
        assertThat(updated.path("data").path("object").path("title").asText()).isEqualTo("Sprint review");
        assertThat(updated.path("data").has("recipients")).isFalse();
        assertThat(updated.path("data").path("state").has("previous")).isFalse();
    }

    @Test
    void memberAcceptingShouldPublishEventAccepted() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        String ics = event("uid-1", "Sprint planning", PLANNED,"bob");
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

    // Sabre also mails the organizer a reply, which must not publish a second card.
    @Test
    void memberAcceptingShouldPublishASingleCard() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        String ics = event("uid-1", "Sprint planning", PLANNED, "bob");
        putEvent("alice", ics);
        awaitActivity("com.twake.calendar.event.created.v1");
        putEvent("bob", ics.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=bob", "PARTSTAT=ACCEPTED;CN=bob"));
        awaitActivity("com.twake.calendar.event.accepted.v1");

        putEvent("alice", event("uid-2", "Retro", PLANNED, "bob"));

        awaitActivity("com.twake.calendar.event.created.v1");
        assertThat(skippedActivities).doesNotContain("com.twake.calendar.event.accepted.v1");
    }

    @Test
    void unreadableCalendarMessageShouldBeDeadLetteredWithoutStoppingTheConsumer() throws Exception {
        provisionedSpace(member("alice", "admin"));
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            channel.basicPublish("calendar:event:updated", "", null, UNREADABLE.getBytes(StandardCharsets.UTF_8));
        }

        putEvent("alice", event("uid-1", "Sprint planning", PLANNED));

        awaitActivity("com.twake.calendar.event.created.v1");
        assertThat(messageCount(CalendarActivityConsumer.DEAD_LETTER_QUEUE)).isEqualTo(1);
    }

    @Test
    void attendeeOutsideTheSpaceDecliningShouldPublishEventDeclined() throws Exception {
        provisionedSpace(member("alice", "admin"));
        server.getProbe(CalendarDataProbe.class).addUser(username("carol"), "secret");
        putEvent("alice", event("uid-1", "Sprint planning", PLANNED,"carol"));
        awaitActivity("com.twake.calendar.event.created.v1");
        String carolEvent = AWAIT.until(() -> personalEventIds("carol"), ids -> !ids.isEmpty()).getFirst();

        dav.upsertCalendar(username("carol"), personalEventUri("carol", carolEvent),
            event("uid-1", "Sprint planning", PLANNED,"carol")
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
        putEvent("alice", event("uid-1", "Sprint planning", PLANNED,"carol"));
        awaitActivity("com.twake.calendar.event.created.v1");
        String carolEvent = AWAIT.until(() -> personalEventIds("carol"), ids -> !ids.isEmpty()).getFirst();

        String counter = event("uid-1", "Sprint planning", PROPOSED, "carol")
            .replace("BEGIN:VEVENT", "METHOD:COUNTER\r\nBEGIN:VEVENT");
        dav.postCounter(carol, carolEvent, new DavTestHelper.CounterRequest(counter, "carol@space.tld", "alice@space.tld", "uid-1", 0)).block();

        JsonNode proposed = awaitActivity("com.twake.calendar.event.proposed.v1");
        assertThat(proposed.path("twakeactor").asText()).isEqualTo("carol@space.tld");
        assertThat(proposed.path("data").path("state").path("start").asText()).isEqualTo("2036-10-10T09:00:00Z");
        assertThat(proposed.path("data").path("state").path("proposed")).isEqualTo(OBJECT_MAPPER.readTree("""
            {"start": "2036-10-12T14:00:00Z", "end": "2036-10-12T15:00:00Z", "by":"carol@space.tld"}"""));
    }

    @Test
    void eventOfAPersonalCalendarShouldPublishNoActivity() throws Exception {
        provisionedSpace(member("alice", "admin"), member("bob", "editor"));
        OpenPaaSUser alice = server.getProbe(CalendarDataProbe.class).getUser(username("alice"));
        dav.upsertCalendar(alice, event("uid-personal", "Dentist", PLANNED, "bob"), "uid-personal");

        putEvent("alice", event("uid-1", "Sprint planning", PLANNED,"bob"));

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

    // Unreadable events are dead lettered, so the dead letter queue counts the events the queue received.
    @Test
    void unreadableSpaceAndMemberEventsShouldBeDeadLettered() throws Exception {
        List<String> routingKeys = List.of("twake.space.created", "twake.space.updated", "twake.space.deleted",
            "twake.space.member.added", "twake.space.member.removed", "twake.space.member.role.changed");
        for (String routingKey : routingKeys) {
            publish(routingKey, UNREADABLE);
        }

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(routingKeys.size()));
    }

    @Test
    void unreadableGroupEventsShouldNotBeDeadLettered() throws Exception {
        publish("twake.space.group.linked", UNREADABLE);
        awaitConsumed();

        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
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

        publish("twake.space.updated", updated(spaceId, "Sales"));

        AWAIT.untilAsserted(() -> assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo("Sales"));
    }

    @Test
    void spaceDeletedShouldRecordTheDeletion() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin")));

        publish("twake.space.deleted", deleted(spaceId));
        awaitConsumed();

        Document space = Mono.from(sabreDavExtension.dockerSabreDavSetup().getMongoDB()
            .getCollection("twake_spaces").find(new Document("_id", spaceId)).first()).block();
        assertThat(space.getDate("deletion")).isNotNull();
    }

    @Test
    void spaceDeletedShouldRemoveEveryMember() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        publish("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), member("bob", "viewer")));

        publish("twake.space.deleted", deleted(spaceId));
        awaitConsumed();

        assertThat(members(spaceId)).isEmpty();
    }

    @Test
    void memberAddedToAnUnknownSpaceShouldProvisionItsTeamCalendar() throws Exception {
        String spaceId = UUID.randomUUID().toString();

        publish("twake.space.member.added", memberEventIn(DOMAIN, spaceId, member("bob", "viewer")));

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(Map.entry("bob@space.tld", "dav:read")));
        assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo(spaceId);
    }

    @Test
    void eventsHandledInAnyOrderShouldKeepTheNewestValues() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String created = created(spaceId, "Marketing", member("alice", "admin"), member("bob", "editor"));
        String roleChanged = memberEvent(spaceId, member("bob", "viewer"));
        String renamed = updated(spaceId, "Sales");

        publish("twake.space.member.role.changed", roleChanged);
        publish("twake.space.updated", renamed);
        publish("twake.space.created", created);

        AWAIT.untilAsserted(() -> assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write"),
            Map.entry("bob@space.tld", "dav:read")));
        assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo("Sales");
    }

    static Stream<List<Integer>> handlingOrders() {
        return Stream.of(
            List.of(0, 1, 2, 3, 4, 5),
            List.of(5, 4, 3, 2, 1, 0),
            List.of(3, 5, 1, 0, 4, 2),
            List.of(4, 2, 0, 5, 3, 1),
            List.of(1, 4, 3, 2, 5, 0));
    }

    // Built in this order, so each event is newer than the ones before it.
    private static List<Map.Entry<String, String>> spaceHistory(String spaceId) {
        return List.of(
            Map.entry("twake.space.created", created(spaceId, "Marketing", member("alice", "admin"), member("bob", "editor"))),
            Map.entry("twake.space.member.added", memberEvent(spaceId, member("carol", "editor"))),
            Map.entry("twake.space.member.role.changed", memberEvent(spaceId, member("bob", "viewer"))),
            Map.entry("twake.space.updated", updated(spaceId, "Sales")),
            Map.entry("twake.space.member.removed", memberEvent(spaceId, member("carol", "editor"))),
            Map.entry("twake.space.member.added", memberEvent(spaceId, member("dave", "viewer"))));
    }

    @ParameterizedTest
    @MethodSource("handlingOrders")
    void spaceEventsShouldGiveTheSameTeamCalendarWhateverTheirOrder(List<Integer> order) throws Exception {
        String spaceId = UUID.randomUUID().toString();
        List<Map.Entry<String, String>> history = spaceHistory(spaceId);

        for (int index : order) {
            publish(history.get(index).getKey(), history.get(index).getValue());
        }
        awaitConsumed();

        assertThat(teamCalendar(spaceId).path("displayName").asText()).isEqualTo("Sales");
        assertThat(members(spaceId)).containsOnly(
            Map.entry("alice@space.tld", "dav:read-write"),
            Map.entry("bob@space.tld", "dav:read"),
            Map.entry("dave@space.tld", "dav:read"));
    }

    // Index 6 is the deletion, the newest event.
    static Stream<List<Integer>> handlingOrdersWithDeletion() {
        return Stream.of(
            List.of(0, 1, 2, 3, 4, 5, 6),
            List.of(6, 5, 4, 3, 2, 1, 0),
            List.of(0, 6, 1, 2, 3, 4, 5),
            List.of(3, 5, 1, 0, 6, 4, 2),
            List.of(4, 2, 6, 0, 5, 3, 1));
    }

    // The team calendar exists when the creation was handled before the deletion, and nobody keeps access to it.
    @ParameterizedTest
    @MethodSource("handlingOrdersWithDeletion")
    void deletedSpaceShouldBeSharedWithNoOneWhateverTheOrderOfItsEvents(List<Integer> order) throws Exception {
        String spaceId = UUID.randomUUID().toString();
        List<Map.Entry<String, String>> history = new ArrayList<>(spaceHistory(spaceId));
        history.add(Map.entry("twake.space.deleted", deleted(spaceId)));

        for (int index : order) {
            publish(history.get(index).getKey(), history.get(index).getValue());
        }
        awaitConsumed();

        if (teamCalendarNames(DOMAIN).contains(spaceId)) {
            assertThat(members(spaceId)).isEmpty();
        }
        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void spaceDeletedBeforeItsCreationIsHandledShouldGetNoTeamCalendar() throws Exception {
        String spaceId = UUID.randomUUID().toString();
        String created = created(spaceId, "Marketing", member("alice", "admin"));
        String deleted = deleted(spaceId);

        publish("twake.space.deleted", deleted);
        publish("twake.space.created", created);
        awaitConsumed();

        assertThat(teamCalendarNames(DOMAIN)).doesNotContain(spaceId);
        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
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
        publish("twake.space.deleted", deleted(spaceId));

        publish("twake.space.member.added", memberEvent(spaceId, member("bob", "viewer")));
        awaitConsumed();

        assertThat(members(spaceId)).isEmpty();
        assertThat(messageCount(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void spaceCreatedWithoutNameShouldBeDeadLettered() throws Exception {
        publish("twake.space.created", """
            {"organizationId": "org", "id": "%s", "members": [%s], "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(UUID.randomUUID(), member("alice", "admin"), DOMAIN.asString(), timestamp()));

        AWAIT.untilAsserted(() -> assertThat(messageCount(DEAD_LETTER_QUEUE)).isEqualTo(1));
    }

    // The test server consumes one event at a time: once this space has its team calendar, the events before were handled.
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
             "members": [%s], "groups": [], "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(organizationDomain.asString(), spaceId, member("alice", "admin"), DOMAIN.asString(), timestamp());
    }

    private static String created(String spaceId, String name, String... members) {
        return """
            {"organizationId": "org", "id": "%s", "name": "%s", "members": [%s], "groups": [],
             "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(spaceId, name, String.join(",", members), DOMAIN.asString(), timestamp());
    }

    private static String memberEvent(String spaceId, String member) {
        return """
            {"organizationId": "org", "id": "%s", "members": [%s], "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(spaceId, member, DOMAIN.asString(), timestamp());
    }

    private static String memberEventIn(Domain organizationDomain, String spaceId, String member) {
        return """
            {"organizationId": "org", "organizationDomain": "%s", "id": "%s", "members": [%s], "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(organizationDomain.asString(), spaceId, member, DOMAIN.asString(), timestamp());
    }

    private static String updated(String spaceId, String name) {
        return """
            {"organizationId": "org", "id": "%s", "name": "%s", "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(spaceId, name, DOMAIN.asString(), timestamp());
    }

    private static String deleted(String spaceId) {
        return """
            {"organizationId": "org", "id": "%s", "actor": "alice@%s", "timestamp": "%s"}"""
            .formatted(spaceId, DOMAIN.asString(), timestamp());
    }

    // Each event built is newer than the ones built before it, as ldap-rest stamps them.
    private static String timestamp() {
        return Instant.now().plusMillis(EVENT_COUNT.incrementAndGet()).toString();
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

    private void publishCommand(String body) throws Exception {
        try (Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            channel.basicPublish(COMMAND_EXCHANGE, "com.twake.space.meeting.requested.v1", new AMQP.BasicProperties.Builder().deliveryMode(2).build(),
                body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String meetingRequest(String organizer, String teamCalendarId, String uid) {
        return """
            {"specversion": "1.0", "id": "%s", "source": "twake://space", "type": "com.twake.space.meeting.requested.v1",
             "time": "2026-10-08T10:02:11Z", "twakeorg": "org", "twakeactorid": "%s", "twakeactor": "%s@%s",
             "data": {"uid": "%s", "container": {"kind": "calendar", "id": "%s"}, "title": "Design review",
                      "start": "2036-10-08T12:32:00+02:00", "end": "2036-10-08T13:02:00+02:00", "timezone": "Europe/Paris",
                      "description": "Last pass on the mockups"}}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID(), organizer, DOMAIN.asString(), uid, teamCalendarId);
    }

    // The test server handles one request at a time: once alice's meeting reaches the feed, the requests before were handled.
    private void awaitMeetingsHandled(String teamCalendarId) throws Exception {
        String sentinel = UUID.randomUUID().toString();
        publishCommand(meetingRequest("alice", teamCalendarId, sentinel));
        AWAIT.until(() -> awaitActivity("com.twake.calendar.event.created.v1").path("subject").asText().equals("event/" + sentinel));
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

    // Alice organizes every event.
    private static String event(String uid, String summary, Slot slot, String... attendees) {
        StringBuilder attendeeLines = new StringBuilder("ATTENDEE;PARTSTAT=ACCEPTED;CN=alice:mailto:alice@%s\r\n".formatted(DOMAIN.asString()));
        for (String attendee : attendees) {
            attendeeLines.append("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;CN=%s:mailto:%s@%s\r\n".formatted(attendee, attendee, DOMAIN.asString()));
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
            ORGANIZER;CN=alice:mailto:alice@%s\r
            %sEND:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, slot.start(), slot.end(), summary, DOMAIN.asString(), attendeeLines);
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
        return connectionFactory;
    }
}
