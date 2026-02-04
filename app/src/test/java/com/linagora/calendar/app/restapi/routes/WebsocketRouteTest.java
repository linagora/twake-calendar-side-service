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

package com.linagora.calendar.app.restapi.routes;

import static com.linagora.calendar.app.TestFixture.awaitMessage;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.eventsearch.CalendarSearchServiceContract.CALMLY_AWAIT;
import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.http.HttpStatus;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.utils.GuiceProbe;
import org.awaitility.core.ConditionFactory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.CalDavClient.PublicRight;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.calendar.storage.EventBusAlarmEvent;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.UsernameRegistrationKey;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedMimeType;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.path.json.JsonPath;
import net.javacrumbs.jsonunit.core.Option;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

class WebsocketRouteTest {
    private static final ConditionFactory NEGATIVE_AWAIT = await()
        .during(1, SECONDS)
        .pollInterval(200, MILLISECONDS);

    static class EventBusProbe implements GuiceProbe {
        private final EventBus eventBus;

        @Inject
        EventBusProbe(EventBus eventBus) {
            this.eventBus = eventBus;
        }

        public void dispatch(CalendarChangeEvent event, CalendarURL calendarURL) {
            eventBus.dispatch(event, new CalendarURLRegistrationKey(calendarURL))
                .block();
        }

        public void dispatchAlarmEvent(EventBusAlarmEvent event, Username username) {
            eventBus.dispatch(event, new UsernameRegistrationKey(username))
                .block();
        }
    }

    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @Order(2)
    @RegisterExtension
    static DockerRedisExtension redisExtension = new DockerRedisExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .enableRedis(),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(EventBusProbe.class),
        new AbstractModule() {
            @Provides
            @Singleton
            public RedisConfiguration redisConfiguration() {
                return StandaloneRedisConfiguration.from(redisExtension.redisURI().toString());
            }
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;

    private OpenPaaSUser bob;
    private OpenPaaSUser alice;

    private int restApiPort;
    private WebSocket webSocket;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        this.bob = sabreDavExtension.newTestUser(Optional.of("bob"));
        this.alice = sabreDavExtension.newTestUser(Optional.of("alice"));

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(bob.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(bob.username(), PASSWORD);
        calendarDataProbe.addUserToRepository(alice.username(), PASSWORD);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(bob.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterEach
    void tearDown() {
        if (webSocket != null) {
            webSocket.close(1000, "test finished");
        }
    }

    @Test
    void websocketShouldAllowSubscriptionForOwnerWithValidTicket() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String registerCalendarURL = CalendarURL.from(bob.id()).asUri().toString();
        webSocket.send(String.format("""
            {
                "register": ["%s"]
            }
            """, registerCalendarURL));

        String json = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(json)
            .isEqualTo("""
                {
                    "registered": [ "%s"]
                }""".formatted(registerCalendarURL));
    }

    @Test
    void websocketShouldRejectInvalidTicket() throws Exception {
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

        OkHttpClient client = new OkHttpClient.Builder().build();

        Request wsRequest = new Request.Builder()
            .url("ws://localhost:" + restApiPort + "/ws?ticket=INVALID")
            .build();

        client.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onFailure(@NotNull WebSocket webSocket, Throwable throwable, Response response) {
                errors.offer(throwable);
            }
        });

        Throwable result = errors.poll(3, TimeUnit.SECONDS);
        assertThat(result)
            .isNotNull();
    }

    @Test
    void websocketShouldReturnNotRegisteredWhenNoRights() {
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(UUID.randomUUID().toString(),
            "My Calendar", "#00ff00", "Test");
        calDavClient.createNewCalendar(alice.username(), alice.id(), newCalendar).block();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // create a calendar owned by another user
        String foreignCalendarUrl = new CalendarURL(alice.id(), new OpenPaaSId(newCalendar.id())).asUri().toString();

        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(foreignCalendarUrl));

        String json = awaitMessage(messages, msg -> msg.contains("notRegistered"));
        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "Forbidden" }
                }""".formatted(foreignCalendarUrl));
    }

    @Test
    void websocketShouldReturnNotRegisteredWhenCalendarDoesNotExist() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String nonExistentCalendar = new CalendarURL(bob.id(), new OpenPaaSId(UUID.randomUUID().toString())).asUri().toString();

        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(nonExistentCalendar));

        String json = awaitMessage(messages, msg -> msg.contains("notRegistered"));
        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "NotFound" }
                }""".formatted(nonExistentCalendar));
    }

    @Test
    void websocketShouldRejectRequestWhenRegisterAndUnregisterSameCalendar() {
        // Given: Bob & calendar
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // When: send mixed request containing both register and unregister for the same calendar
        webSocket.send("""
            {
                "register": ["%s"],
                "unregister": ["%s"]
            }
            """.formatted(calendarUri, calendarUri));

        // Then: server must reject as invalid request
        String json = awaitMessage(messages, msg -> msg.contains("error"));
        assertThatJson(json)
            .node("error")
            .asString()
            .startsWith("register and unregister cannot contain duplicated entries");
    }

    @Test
    void websocketShouldNoopWhenRegisterAndUnregisterAreBothEmpty() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            {
                "register": [],
                "unregister": []
            }
            """);

        String json = awaitMessage(messages, msg -> msg.equals("{}"));
        assertThatJson(json)
            .isEqualTo("{}");
    }

    @Test
    void websocketShouldAllowRegisterWhenUserHasRights() {
        String sharedCalendarId = UUID.randomUUID().toString();
        CalDavClient.NewCalendar publicCalendar = new CalDavClient.NewCalendar(sharedCalendarId,
            "Public Calendar", "#123456", "Public calendar for testing");

        calDavClient.createNewCalendar(alice.username(), alice.id(), publicCalendar).block();

        // Set ACL: Owner makes it PUBLIC READ
        CalendarURL calendarURL = new CalendarURL(alice.id(), new OpenPaaSId(sharedCalendarId));
        calDavClient.updateCalendarAcl(alice.username(), calendarURL, PublicRight.READ).block();

        // Connect WS
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String registerCalendarURL = calendarURL.asUri().toString();

        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(registerCalendarURL));

        String json = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(json)
            .isEqualTo("""
                {
                    "registered": ["%s"]
                }
                """.formatted(registerCalendarURL));
    }

    @Test
    void websocketShouldUnregisterSuccessfully() {
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            {
                "unregister": ["%s"]
            }
            """.formatted(calendarUri));

        String unregJson = awaitMessage(messages, msg -> msg.contains("unregistered"));
        assertThatJson(unregJson)
            .isEqualTo("""
                {
                    "unregistered": ["%s"]
                }
                """.formatted(calendarUri));
    }

    @Test
    void websocketShouldHandleMixedRegisterAndUnregister() {
        // Given: A & C belong to bob (valid)
        String calendarA = CalendarURL.from(bob.id()).asUri().toString();
        CalDavClient.NewCalendar publicCalendar = new CalDavClient.NewCalendar(UUID.randomUUID().toString(),
            "Public Calendar", "#123456", "Public calendar for testing");
        calDavClient.createNewCalendar(bob.username(), bob.id(), publicCalendar).block();
        String calendarC = new CalendarURL(bob.id(), new OpenPaaSId(publicCalendar.id())).asUri().toString();

        // B does not exist → should fail register
        String calendarB = new CalendarURL(bob.id(), new OpenPaaSId(UUID.randomUUID().toString()))
            .asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // When: send register A+B and unregister C in same payload
        webSocket.send("""
            {
                "register": ["%s", "%s"],
                "unregister": ["%s"]
            }
            """.formatted(calendarA, calendarB, calendarC));

        String json = awaitMessage(messages, msg -> msg.contains("notRegistered"));
        // Then: A registered, B notRegistered, C unregistered
        assertThatJson(json)
            .isEqualTo("""
                {
                  "registered": ["%s"],
                  "notRegistered": { "%s" : "NotFound" },
                  "unregistered": ["%s"]
                }
                """.formatted(calendarA, calendarB, calendarC));
    }

    @Test
    void websocketShouldReceiveEventPushedFromEventBus(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob & calendar
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Bob subscribe
        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(calendarUri));

        // Consume subscription ACK
        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack).isEqualTo("""
            {
                "registered": ["%s"]
            }
            """.formatted(calendarUri));

        // When: dispatch event via EventBusProbe
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        CalendarURL url = CalendarURL.from(bob.id());
        CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), url);
        eventBusProbe.dispatch(event, url);

        // Then: WebSocket must receive event push
        String pushed = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThatJson(pushed)
            .isEqualTo("""
            {
              "%s" : {
                "syncToken" : "${json-unit.ignore}"
              }
            }
            """.formatted(calendarUri));
    }

    @Test
    void websocketShouldReturnUnregisteredWhenUnregisteringIdempotently() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        webSocket.send("""
            {
                "unregister": ["%s"]
            }
            """.formatted(calendarUri));

        String json = awaitMessage(messages, msg -> msg.contains("unregistered"));
        assertThatJson(json)
            .isEqualTo("""
                {
                    "unregistered": ["%s"]
                }
                """.formatted(calendarUri));
    }

    @Test
    void registeringSameCalendarTwiceShouldStillReturnRegistered() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String first = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(first).isEqualTo("""
            { "registered": ["%s"] }
            """.formatted(calendarUri));

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String second = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(second).isEqualTo("""
            { "registered": ["%s"] }
            """.formatted(calendarUri));
    }

    @Test
    void websocketShouldStopReceivingEventsAfterUnregister(TwakeCalendarGuiceServer guiceServer) {
        // Given: bob & calendar A
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();
        CalendarURL calendar = CalendarURL.from(bob.id());

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Step 1: Register A
        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        // Ack for register
        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack).isEqualTo("""
            { "registered": ["%s"] }
            """.formatted(calendarUri));

        // Step 2: Dispatch event #1 → MUST receive
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        CalendarChangeEvent event1 = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event1, calendar);

        String pushed1 = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThat(pushed1).as("WebSocket should receive event after register").isNotNull();

        assertThatJson(pushed1)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarUri));

        // Step 3: Unregister A
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(calendarUri));

        String unregAck = awaitMessage(messages, msg -> msg.contains("unregistered"));
        assertThatJson(unregAck)
            .isEqualTo("""
                { "unregistered": ["%s"] }
                """.formatted(calendarUri));

        // Step 4: Dispatch event #2 → MUST NOT receive anything
        CalendarChangeEvent event2 = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event2, calendar);

        // MUST NOT receive syncToken event for this calendar anymore (ignore unrelated messages)
        NEGATIVE_AWAIT.untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("WebSocket should NOT receive syncToken events after unregister (%s)", calendarUri)
                    .noneSatisfy(msg -> assertThat(msg)
                        .contains("\"syncToken\"")
                        .contains(calendarUri));
            });
    }

    @Test
    void websocketShouldNotReceiveDuplicateEventsWhenRegisteredTwice(TwakeCalendarGuiceServer guiceServer) {
        // Given: bob & a calendar A
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();
        CalendarURL calendar = CalendarURL.from(bob.id());

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // And: two idempotent register calls
        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));
        String ack1 = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack1)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));
        String ack2 = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack2)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: an event is dispatched
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event, calendar);

        // Then: websocket receives exactly one event
        String pushed = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThat(pushed)
            .as("WebSocket should receive ONE event even after double registration")
            .isNotNull();

        assertThatJson(pushed).isEqualTo("""
            { "%s" : { "syncToken": "${json-unit.ignore}" } }
            """.formatted(calendarUri));

        // And: no duplicate syncToken event arrives later (ignore unrelated messages)
        NEGATIVE_AWAIT.untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("WebSocket should NOT receive duplicate syncToken event after multiple register calls (%s)", calendarUri)
                    .noneSatisfy(msg -> assertThat(msg)
                        .contains("\"syncToken\"")
                        .contains(calendarUri));
            });
    }

    @Test
    void websocketShouldNotReceiveEventsFromUnregisteredCalendar(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob has calendar A (default) and a newly created calendar B
        String calendarA = CalendarURL.from(bob.id()).asUri().toString();

        // Create calendar B
        CalDavClient.NewCalendar newCalendarB = new CalDavClient.NewCalendar(
            UUID.randomUUID().toString(), "Calendar B", "#00ff00", "Test B");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendarB).block();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // And: Bob only registers calendar A
        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarA));

        String message = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(message)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarA));

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // When: event is dispatched for calendar B (unregistered)
        CalendarURL urlB = new CalendarURL(bob.id(), new OpenPaaSId(newCalendarB.id()));
        CalendarChangeEvent eventB = new CalendarChangeEvent(Event.EventId.random(), urlB);
        eventBusProbe.dispatch(eventB, urlB);

        String calendarB = urlB.asUri().toString();

        // Then: websocket must NOT receive any syncToken event for unregistered calendar B (ignore unrelated messages)
        NEGATIVE_AWAIT
            .untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Should not receive syncToken events for unregistered calendar B (%s)", calendarB)
                    .noneSatisfy(msg -> assertThat(msg)
                        .contains("\"syncToken\"")
                        .contains(calendarB));
            });
    }

    @Test
    void websocketShouldReceiveEventsFromRemainingCalendarAfterUnregister(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob has calendar A and newly created calendar B
        String calendarA = CalendarURL.from(bob.id()).asUri().toString();

        CalDavClient.NewCalendar newCalendarB = new CalDavClient.NewCalendar(
            UUID.randomUUID().toString(), "Calendar B", "#00ff00", "Test B");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendarB).block();

        String calendarB = new CalendarURL(bob.id(), new OpenPaaSId(newCalendarB.id())).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // And: Bob registers A + B
        webSocket.send("""
            { "register": ["%s", "%s"] }
            """.formatted(calendarA, calendarB));

        // Acknowledge both
        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
            {
              "registered": ["%s", "%s"]
            }
            """.formatted(calendarA, calendarB));

        // And: Bob unregisters A
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(calendarA));

        String unregAck = awaitMessage(messages, msg -> msg.contains("unregistered"));
        assertThatJson(unregAck).isEqualTo("""
            {
              "unregistered": ["%s"]
            }
            """.formatted(calendarA));

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // When: event dispatched to calendar B
        CalendarURL urlB = new CalendarURL(bob.id(), new OpenPaaSId(newCalendarB.id()));
        CalendarChangeEvent eventB = new CalendarChangeEvent(Event.EventId.random(), urlB);
        eventBusProbe.dispatch(eventB, urlB);

        // Then: WebSocket must receive event only for B
        String pushedB = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThat(pushedB)
            .as("WebSocket must receive event for B")
            .isNotNull();

        assertThatJson(pushedB)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarB));
    }

    @Test
    void websocketShouldReceiveEventsForAllRegisteredCalendars(TwakeCalendarGuiceServer guiceServer) {
        // Given: A and newly created calendar B
        String calendarA = CalendarURL.from(bob.id()).asUri().toString();

        CalDavClient.NewCalendar newCalendarB = new CalDavClient.NewCalendar(
            UUID.randomUUID().toString(), "Calendar B", "#00ff00", "Test B");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendarB).block();

        String calendarB = new CalendarURL(bob.id(), new OpenPaaSId(newCalendarB.id())).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // And: Bob registers A + B
        webSocket.send("""
            { "register": ["%s", "%s"] }
            """.formatted(calendarA, calendarB));

        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                { "registered": ["%s", "%s"] }
                """.formatted(calendarA, calendarB));

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // When: dispatch event for A
        CalendarURL urlA = CalendarURL.from(bob.id());
        CalendarChangeEvent eventA = new CalendarChangeEvent(Event.EventId.random(), urlA);
        eventBusProbe.dispatch(eventA, urlA);

        // Then: websocket receives A
        String pushedA = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThat(pushedA).isNotNull();
        assertThatJson(pushedA)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarA));

        // When: dispatch event for B
        CalendarURL urlB = new CalendarURL(bob.id(), new OpenPaaSId(newCalendarB.id()));
        CalendarChangeEvent eventB = new CalendarChangeEvent(Event.EventId.random(), urlB);
        eventBusProbe.dispatch(eventB, urlB);

        // Then: websocket receives B as well
        String pushedB = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThat(pushedB).isNotNull();
        assertThatJson(pushedB)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarB));
    }

    @Test
    void websocketShouldStopReceivingEventsWhenCalendarStopsBeingPublic(TwakeCalendarGuiceServer guiceServer) {
        // Given: Alice creates calendar B and shares it publicly
        CalDavClient.NewCalendar aliceCalendarB = new CalDavClient.NewCalendar(
            UUID.randomUUID().toString(),
            "Alice Calendar B",
            "#ff0000",
            "Shared public test");

        calDavClient.createNewCalendar(alice.username(), alice.id(), aliceCalendarB).block();

        CalendarURL calendarB = new CalendarURL(alice.id(), new OpenPaaSId(aliceCalendarB.id()));
        String calendarBUri = calendarB.asUri().toString();

        // And: share calendar B publicly
        calDavClient.updateCalendarAcl(alice.username(), calendarB, PublicRight.READ).block();

        // And: Bob opens WebSocket and registers Alice's calendar B
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarBUri));

        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarBUri));

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // When: event is dispatched for calendar B (public)
        CalendarChangeEvent event1 = new CalendarChangeEvent(Event.EventId.random(),  calendarB);
        eventBusProbe.dispatch(event1, calendarB);

        // Then: Bob MUST receive the event
        String pushed1 = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThatJson(pushed1)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarBUri));

        // And: Alice removes public sharing
        calDavClient.updateCalendarAcl(alice.username(), calendarB, PublicRight.HIDE_ALL_EVENT).block();

        // When: event is dispatched again after access revoked
        CalendarChangeEvent event2 = new CalendarChangeEvent(Event.EventId.random(), calendarB);
        eventBusProbe.dispatch(event2, calendarB);

        // Then: Bob MUST NOT receive this event anymore (ignore unrelated messages)
        NEGATIVE_AWAIT.untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Bob must not receive syncToken events after calendar is no longer publicly shared (%s)", calendarBUri)
                    .noneSatisfy(msg -> assertThat(msg)
                        .contains("\"syncToken\"")
                        .contains(calendarBUri));
            });
    }

    @Test
    void websocketShouldBroadcastEventsToAllSubscribedClients(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob & a calendar
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        // Two independent WebSocket sessions (simulating 2 browser tabs)
        BlockingQueue<String> messages1 = new LinkedBlockingQueue<>();
        BlockingQueue<String> messages2 = new LinkedBlockingQueue<>();

        WebSocket ws1 = connectWebSocket(restApiPort, generateTicket(bob), messages1);
        WebSocket ws2 = connectWebSocket(restApiPort, generateTicket(bob), messages2);

        try {
            // And: both WS1 & WS2 register the same calendar
            ws1.send("""
                { "register": ["%s"] }
                """.formatted(calendarUri));
            String ack1 = awaitMessage(messages1, msg -> msg.contains("registered"));
            assertThatJson(ack1)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            ws2.send("""
                { "register": ["%s"] }
                """.formatted(calendarUri));

            String ack2 = awaitMessage(messages2, msg -> msg.contains("registered"));
            assertThatJson(ack2)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

            // When: event is dispatched for A
            CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
            eventBusProbe.dispatch(event, calendar);

            // Then: BOTH WS clients must receive the event
            String pushed1 = awaitMessage(messages1, msg -> msg.contains(calendarUri) && msg.contains("syncToken"));
            String pushed2 = awaitMessage(messages2, msg -> msg.contains(calendarUri) && msg.contains("syncToken"));

            assertThat(pushed1)
                .as("WS1 should receive broadcast event")
                .isNotNull();

            assertThat(pushed2)
                .as("WS2 should receive broadcast event")
                .isNotNull();

            assertThatJson(pushed1)
                .isEqualTo("""
                    { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                    """.formatted(calendarUri));

            assertThatJson(pushed2)
                .isEqualTo("""
                    { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                    """.formatted(calendarUri));
        } finally {
            ws1.close(1000, "done");
            ws2.close(1000, "done");
        }
    }

    @Test
    void websocketShouldIsolateUnregisterAcrossMultipleClients(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob & a calendar
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();

        // WS1 & WS2 simulate two browser tabs
        BlockingQueue<String> messages1 = new LinkedBlockingQueue<>();
        BlockingQueue<String> messages2 = new LinkedBlockingQueue<>();

        WebSocket ws1 = connectWebSocket(restApiPort, generateTicket(bob), messages1);
        WebSocket ws2 = connectWebSocket(restApiPort, generateTicket(bob), messages2);

        try {
            // And: WS1 registers calendar
            ws1.send("""
                { "register": ["%s"] }
                """.formatted(calendarUri));
            String ack1 = awaitMessage(messages1, msg -> msg.contains("registered"));
            assertThatJson(ack1)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            // And: WS2 registers the same calendar
            ws2.send("""
                { "register": ["%s"] }
                """.formatted(calendarUri));
            String ack2 = awaitMessage(messages2, msg -> msg.contains("registered"));
            assertThatJson(ack2)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            // And: WS1 unregisters calendar
            ws1.send("""
                { "unregister": ["%s"] }
                """.formatted(calendarUri));
            String unregAck = awaitMessage(messages1, msg -> msg.contains("unregistered"));
            assertThatJson(unregAck)
                .isEqualTo("""
                    { "unregistered": ["%s"] }
                    """.formatted(calendarUri));

            EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

            // When: event is dispatched for this calendar
            CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
            eventBusProbe.dispatch(event, calendar);

            // Then: WS1 MUST NOT receive any syncToken event (ignore unrelated messages)
            NEGATIVE_AWAIT.untilAsserted(() -> {
                    List<String> received = new ArrayList<>();
                    messages1.drainTo(received);

                    assertThat(received)
                        .as("WS1 should NOT receive syncToken event after unregister (%s)", calendarUri)
                        .noneSatisfy(msg -> assertThat(msg)
                            .contains("\"syncToken\"")
                            .contains(calendarUri));
                });

            // And: WS2 MUST receive the event
            String pushed2 = awaitMessage(messages2, msg -> msg.contains("syncToken"));
            assertThatJson(pushed2)
                .isEqualTo("""
                    { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                    """.formatted(calendarUri));
        } finally {
            ws1.close(1000, "done");
            ws2.close(1000, "done");
        }
    }

    @Test
    void websocketShouldEnableDisplayNotification() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("{\"enableDisplayNotification\": true}");

        String response = awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));
        assertThatJson(response)
            .isEqualTo("{\"displayNotificationEnabled\":true}");
    }

    @Test
    void websocketShouldDisableDisplayNotification() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // First enable
        webSocket.send("{\"enableDisplayNotification\": true}");
        awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));

        // Then disable
        webSocket.send("{\"enableDisplayNotification\": false}");

        String response = awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));
        assertThatJson(response)
            .isEqualTo("{\"displayNotificationEnabled\":false}");
    }

    @Test
    void websocketShouldReceiveAlarmEventAfterEnablingDisplayNotification(TwakeCalendarGuiceServer guiceServer) {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Enable display notification
        webSocket.send("{\"enableDisplayNotification\": true}");
        String enableResponse = awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));
        assertThatJson(enableResponse)
            .isEqualTo("{\"displayNotificationEnabled\":true}");

        // Dispatch alarm event
        Instant eventStartTime = Instant.now().plusSeconds(900);
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            bob.username(),
            "Test Meeting",
            "calendars/user/calendar/event.ics",
            eventStartTime);
        eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

        // Should receive alarm
        String alarmMessage = awaitMessage(messages, msg -> msg.contains("alarms"));
        String expected = """
                {
                    "alarms": [
                        {
                            "eventSummary": "Test Meeting",
                            "eventURL": "calendars/user/calendar/event.ics",
                            "eventStartTime": "{eventStartTime}"
                        }
                    ]
                }
                """.replace("{eventStartTime}", eventStartTime.toString());

        assertThatJson(alarmMessage)
            .isEqualTo(expected);
    }

    @Test
    void websocketShouldNotReceiveAlarmEventWithoutEnablingDisplayNotification(TwakeCalendarGuiceServer guiceServer) {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // DO NOT enable display notification

        // Dispatch alarm event
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            bob.username(),
            "Test Meeting",
            "calendars/user/calendar/event.ics",
            Instant.now().plusSeconds(900));
        eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

        // Should NOT receive alarm
        NEGATIVE_AWAIT.untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Should NOT receive alarm event without enabling display notification")
                    .noneSatisfy(msg -> assertThat(msg).contains("\"alarms\""));
            });
    }

    @Test
    void websocketShouldNotReceiveAlarmEventAfterDisablingDisplayNotification(TwakeCalendarGuiceServer guiceServer) {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Enable display notification
        webSocket.send("{\"enableDisplayNotification\": true}");
        awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));

        // Disable display notification
        webSocket.send("{\"enableDisplayNotification\": false}");
        awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));

        // Dispatch alarm event
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            bob.username(),
            "Test Meeting",
            "calendars/user/calendar/event.ics",
            Instant.now().plusSeconds(900));
        eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

        // Should NOT receive alarm after disabling
        NEGATIVE_AWAIT.untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Should NOT receive alarm event after disabling display notification")
                    .noneSatisfy(msg -> assertThat(msg).contains("\"alarms\""));
            });
    }

    @Test
    void websocketShouldNotReceiveDuplicateAlarmsWhenEnablingTwice(TwakeCalendarGuiceServer guiceServer) throws Exception {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Enable twice
        webSocket.send("{\"enableDisplayNotification\": true}");
        messages.poll(5, TimeUnit.SECONDS);

        webSocket.send("{\"enableDisplayNotification\": true}");
        messages.poll(5, TimeUnit.SECONDS);

        // Dispatch alarm event
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            bob.username(),
            "Test Meeting",
            "calendars/user/calendar/event.ics",
            Instant.now().plusSeconds(900));
        eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

        // Should receive exactly one alarm
        String alarmMessage = awaitMessage(messages, msg -> msg.contains("alarms"));
        assertThatJson(alarmMessage)
            .node("alarms[0].eventSummary")
            .isStringEqualTo("Test Meeting");

        // No duplicate
        String duplicate = messages.poll(2, TimeUnit.SECONDS);
        assertThat(duplicate).isNull();
    }

    @Test
    void websocketShouldIsolateDisplayNotificationAcrossClients(TwakeCalendarGuiceServer guiceServer) {
        BlockingQueue<String> messages1 = new LinkedBlockingQueue<>();
        BlockingQueue<String> messages2 = new LinkedBlockingQueue<>();

        WebSocket ws1 = connectWebSocket(restApiPort, generateTicket(bob), messages1);
        WebSocket ws2 = connectWebSocket(restApiPort, generateTicket(bob), messages2);

        try {
            // WS1 enables display notification
            ws1.send("{\"enableDisplayNotification\": true}");
            awaitMessage(messages1, msg -> msg.contains("displayNotificationEnabled"));

            // WS2 does NOT enable display notification

            // Dispatch alarm event
            EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
            EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
                Event.EventId.random(),
                bob.username(),
                "Test Meeting",
                "calendars/user/calendar/event.ics",
                Instant.now().plusSeconds(900));
            eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

            // WS1 should receive alarm
            String alarm1 = awaitMessage(messages1, msg -> msg.contains("alarms"));
            assertThatJson(alarm1)
                .node("alarms[0].eventSummary")
                .isStringEqualTo("Test Meeting");

            // WS2 should NOT receive alarm
            NEGATIVE_AWAIT.untilAsserted(() -> {
                    List<String> received = new ArrayList<>();
                    messages2.drainTo(received);

                    assertThat(received)
                        .as("WS2 should NOT receive alarm event when display notification is not enabled")
                        .noneSatisfy(msg -> assertThat(msg).contains("\"alarms\""));
                });
        } finally {
            ws1.close(1000, "done");
            ws2.close(1000, "done");
        }
    }

    @Test
    void websocketShouldBroadcastAlarmToAllClientsWithDisplayNotificationEnabled(TwakeCalendarGuiceServer guiceServer) {
        BlockingQueue<String> messages1 = new LinkedBlockingQueue<>();
        BlockingQueue<String> messages2 = new LinkedBlockingQueue<>();

        WebSocket ws1 = connectWebSocket(restApiPort, generateTicket(bob), messages1);
        WebSocket ws2 = connectWebSocket(restApiPort, generateTicket(bob), messages2);

        try {
            // Both clients enable display notification
            ws1.send("{\"enableDisplayNotification\": true}");
            String ack1 = awaitMessage(messages1, msg -> msg.contains("displayNotificationEnabled"));
            assertThatJson(ack1).isEqualTo("{\"displayNotificationEnabled\":true}");

            ws2.send("{\"enableDisplayNotification\": true}");
            String ack2 = awaitMessage(messages2, msg -> msg.contains("displayNotificationEnabled"));
            assertThatJson(ack2).isEqualTo("{\"displayNotificationEnabled\":true}");

            // Dispatch alarm event
            EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
            EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
                Event.EventId.random(),
                bob.username(),
                "Test Meeting",
                "calendars/user/calendar/event.ics",
                Instant.now().plusSeconds(900));
            eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

            // Both should receive alarm (order/noise tolerant)
            String alarm1 = awaitMessage(messages1, msg -> msg.contains("\"alarms\""));
            String alarm2 = awaitMessage(messages2, msg -> msg.contains("\"alarms\""));

            assertThatJson(alarm1)
                .node("alarms[0].eventSummary")
                .isStringEqualTo("Test Meeting");
            assertThatJson(alarm2)
                .node("alarms[0].eventSummary")
                .isStringEqualTo("Test Meeting");
        } finally {
            ws1.close(1000, "done");
            ws2.close(1000, "done");
        }
    }

    @Test
    void websocketShouldNotReceiveAlarmForDifferentUser(TwakeCalendarGuiceServer guiceServer) {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Bob enables display notification
        webSocket.send("{\"enableDisplayNotification\": true}");
        awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));

        // Dispatch alarm event for Alice (different user)
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            alice.username(),
            "Alice Meeting",
            "calendars/alice/calendar/event.ics",
            Instant.now().plusSeconds(900));
        eventBusProbe.dispatchAlarmEvent(alarmEvent, alice.username());

        // Bob should NOT receive Alice's alarm
        NEGATIVE_AWAIT.untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Bob should NOT receive alarm for different user")
                    .noneSatisfy(msg -> assertThat(msg).contains("\"alarms\""));
            });
    }

    @Test
    void websocketShouldReceiveAlarmAfterSubscribingToCalendar(TwakeCalendarGuiceServer guiceServer) {
        // GIVEN: Bob opened a WS connection
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // AND: Bob subscribes to display notification
        webSocket.send("{\"enableDisplayNotification\": true}");
        String displayAck = awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));
        assertThatJson(displayAck)
            .isEqualTo("{\"displayNotificationEnabled\":true}");

        // WHEN: Bob subscribed to his calendar changes
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();
        webSocket.send("{\"register\": [\"%s\"]}".formatted(calendarUri));
        String calendarAck = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(calendarAck)
            .isEqualTo("{\"registered\": [\"%s\"]}".formatted(calendarUri));

        // THEN: Bob is notified of event alarms
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            bob.username(),
            "Test Meeting",
            "calendars/user/calendar/event.ics",
            Instant.now().plusSeconds(900));
        eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

        String alarmMessage = awaitMessage(messages, msg -> msg.contains("\"alarms\""));
        assertThatJson(alarmMessage)
            .node("alarms[0].eventSummary")
            .isStringEqualTo("Test Meeting");
    }

    @Test
    void websocketShouldReceiveCalendarChangesAfterEnablingDisplayNotification(TwakeCalendarGuiceServer guiceServer) {
        // GIVEN: Bob opened a WS connection
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // AND: Bob registers to his calendar changes
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();
        webSocket.send("{\"register\": [\"%s\"]}".formatted(calendarUri));
        String calendarAck = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(calendarAck)
            .isEqualTo("{\"registered\": [\"%s\"]}".formatted(calendarUri));

        // WHEN: Bob subscribed to display notification
        webSocket.send("{\"enableDisplayNotification\": true}");
        String displayAck = awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));
        assertThatJson(displayAck)
            .isEqualTo("{\"displayNotificationEnabled\":true}");

        // THEN: Bob is notified when updating events
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        CalendarURL calendar = CalendarURL.from(bob.id());
        CalendarChangeEvent calendarEvent = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(calendarEvent, calendar);

        String calendarMessage = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThatJson(calendarMessage)
            .isEqualTo("{\"" + calendarUri + "\": {\"syncToken\": \"${json-unit.ignore}\"}}");
    }

    @Test
    void websocketShouldReceiveBothCalendarChangesAndAlarms(TwakeCalendarGuiceServer guiceServer) {
        // GIVEN: Bob opened a WS connection with both subscriptions
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Subscribe to calendar changes
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();
        webSocket.send("{\"register\": [\"%s\"]}".formatted(calendarUri));
        String registerAck = awaitMessage(messages, msg -> msg.contains("\"registered\""));
        assertThatJson(registerAck)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // Enable display notification (alarms)
        webSocket.send("{\"enableDisplayNotification\": true}");
        String displayAck = awaitMessage(messages, msg -> msg.contains("displayNotificationEnabled"));
        assertThatJson(displayAck)
            .isEqualTo("{\"displayNotificationEnabled\":true}");

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // WHEN: Both alarm and calendar change events are dispatched
        EventBusAlarmEvent alarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            bob.username(),
            "Alarm Meeting",
            "calendars/user/calendar/event.ics",
            Instant.now().plusSeconds(900));
        eventBusProbe.dispatchAlarmEvent(alarmEvent, bob.username());

        CalendarURL calendar = CalendarURL.from(bob.id());
        CalendarChangeEvent calendarEvent = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(calendarEvent, calendar);

        // THEN: Bob receives both notifications (order-independent, ignore unrelated messages)
        CALMLY_AWAIT
            .untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Expected both an alarm and a syncToken notification but got: %s", received)
                    .anySatisfy(msg -> assertThat(msg).contains("\"alarms\""));

                assertThat(received)
                    .as("Expected both an alarm and a syncToken notification but got: %s", received)
                    .anySatisfy(msg -> assertThat(msg).contains("\"syncToken\""));
            });
    }

    private String generateTicket(OpenPaaSUser user) {
        String ticketResponse = given()
            .auth().preemptive().basic(user.username().asString(), PASSWORD)
        .when()
            .post("http://localhost:" + restApiPort + "/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .asString();
        return JsonPath.from(ticketResponse).getString("value");
    }

    private WebSocket connectWebSocket(int port, String ticket, BlockingQueue<String> messages) {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        Request wsRequest = new Request.Builder()
            .url("ws://localhost:" + port + "/ws?ticket=" + ticket)
            .build();
        WebSocket webSocket=  client.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                messages.offer(text);
            }
        });

        // warm up
        awaitMessage(messages, msg -> msg.contains("\"calendarListRegistered\""));
        return webSocket;
    }

    @Test
    void websocketShouldReceiveEventWhenImportingIntoRegisteredCalendar(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob owns a calendar and subscribes to it via WebSocket
        CalendarURL calendarABC = CalendarURL.from(bob.id());
        String calendarUri = calendarABC.asUri().toString();

        // And: Bob opens a WebSocket connection and registers the calendar
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        // Wait for and validate the WebSocket registration acknowledgment
        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: import ICS into the registered calendar using the helper
        String importId = importIcsIntoCalendar(guiceServer, calendarABC, calendarUri, bob);

        // Then: the WebSocket client should receive an import-completed notification
        String pushed = awaitMessage(messages, msg -> msg.contains("imports"));
        assertThat(pushed)
            .as("WebSocket should receive event pushed after import")
            .isNotNull();

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "%s" : {
                    "imports" : {
                      "%s" : {
                        "status" : "completed",
                        "succeedCount" : 1,
                        "failedCount" : 0
                      }
                    }
                  }
                }
                """.formatted(calendarUri, importId));
    }

    @Test
    void websocketShouldReceiveFailedImportEventWhenImportFails(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob owns a calendar and subscribes via WebSocket
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: import an INVALID ICS file
        String importId = importIcsBytesIntoCalendar(guiceServer, calendar, calendarUri, bob, "NOT_A_VALID_ICS".getBytes(StandardCharsets.UTF_8));
        // Then: WebSocket receives FAILED import event
        String pushed = awaitMessage(messages, msg -> msg.contains("imports"));
        assertThat(pushed)
            .as("WebSocket should receive failed import notification")
            .isNotNull();

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "%s" : {
                    "imports" : {
                      "%s" : {
                        "status" : "failed"
                      }
                    }
                  }
                }
                """.formatted(calendarUri, importId));
    }

    @Test
    void websocketShouldReceiveImportEventAndCalendarSyncEvent(TwakeCalendarGuiceServer guiceServer) {
        // Given: Bob owns a calendar and subscribes to it via WebSocket
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: import ICS into the registered calendar
        String importId = importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);

        // Then: WebSocket receives the import-completed notification
        String importMessage = awaitMessage(messages, msg -> msg.contains("imports"));
        assertThatJson(importMessage)
            .isEqualTo("""
                {
                  "%s" : {
                    "imports" : {
                      "%s" : {
                        "status" : "completed",
                        "succeedCount" : 1,
                        "failedCount" : 0
                      }
                    }
                  }
                }
                """.formatted(calendarUri, importId));

        // When: a calendar change event is dispatched
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event, calendar);

        // Then: WebSocket also receives a syncToken notification
        String syncMessage = awaitMessage(messages, msg -> msg.contains("syncToken"));
        assertThatJson(syncMessage)
            .isEqualTo("""
                {
                  "%s" : {
                    "syncToken" : "${json-unit.ignore}"
                  }
                }
                """.formatted(calendarUri));
    }

    @Test
    void websocketShouldNotReceiveImportEventWhenCalendarNotRegistered(TwakeCalendarGuiceServer guiceServer) {
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Import without registering the calendar
        importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);

        // must NOT receive any calendar import event
        NEGATIVE_AWAIT
            .untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Should not receive calendar import events when calendar is not registered")
                    .noneSatisfy(msg -> assertThat(msg).contains("\"imports\""));
            });
    }

    @Test
    void websocketShouldNotReceiveImportEventAfterUnregister(TwakeCalendarGuiceServer guiceServer) throws Exception {
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Register calendar
        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // Unregister calendar
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(calendarUri));

        String unregAck = awaitMessage(messages, msg -> msg.contains("unregistered"));
        assertThatJson(unregAck)
            .isEqualTo("""
                { "unregistered": ["%s"] }
                """.formatted(calendarUri));

        // Import after unregister
        importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);

        String pushed = messages.poll(3, TimeUnit.SECONDS);
        assertThat(pushed)
            .as("WebSocket should NOT receive import event after unregister")
            .isNull();
    }

    @Test
    void websocketShouldReceiveEventWhenImportingIntoRegisteredAddressBook(TwakeCalendarGuiceServer guiceServer) {
        String addressBookId = "collected";
        AddressBookURL addressBook = new AddressBookURL(bob.id(), addressBookId);
        String addressBookUri = addressBook.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(addressBookUri));

        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThat(ack).isNotNull();

        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(addressBookUri));

        String importId = importVCardIntoAddressBook(guiceServer, addressBook, bob);

        // Then: WebSocket receives import-completed notification
        String pushed = awaitMessage(messages, msg -> msg.contains("imports"));
        assertThat(pushed)
            .as("WebSocket should receive addressbook import event")
            .isNotNull();

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "%s" : {
                    "imports" : {
                      "%s" : {
                        "status" : "completed",
                        "succeedCount":1,
                        "failedCount":0
                      }
                    }
                  }
                }
                """.formatted(addressBookUri, importId));
    }

    @Test
    void websocketShouldReturnNotRegisteredWhenNoRightsOnAddressBook() {
        // Given: Alice owns an address book (default "collected")
        AddressBookURL aliceAddressBook = new AddressBookURL(alice.id(), "collected");
        String aliceAddressBookUri = aliceAddressBook.asUri().toString();

        // And: Bob opens a websocket connection
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // When: Bob tries to register Alice's address book
        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(aliceAddressBookUri));

        // Then: Bob must NOT be registered due to missing rights
        String json = awaitMessage(messages, msg -> msg.contains("notRegistered"));
        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "Forbidden" }
                }
                """.formatted(aliceAddressBookUri));
    }

    @Test
    void websocketShouldReturnNotRegisteredWhenAddressBookDoesNotExist() {
        AddressBookURL nonExistentAddressBook =
            new AddressBookURL(bob.id(), UUID.randomUUID().toString());
        String addressBookUri = nonExistentAddressBook.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(addressBookUri));

        CALMLY_AWAIT
            .untilAsserted(() -> assertThatJson(awaitMessage(messages, msg -> msg.contains("notRegistered")))
                .isEqualTo("""
                    {
                        "notRegistered": { "%s" : "NotFound" }
                    }
                    """.formatted(addressBookUri)));
    }

    @Test
    void websocketShouldNotReceiveAddressBookEventsAfterUnregister(TwakeCalendarGuiceServer guiceServer) {
        AddressBookURL addressBook = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBook.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // register
        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(addressBookUri));
        awaitMessage(messages, msg -> msg.contains("registered"));

        // unregister
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(addressBookUri));
        awaitMessage(messages, msg -> msg.contains("unregister"));

        // import vcard
        importVCardIntoAddressBook(guiceServer, addressBook, bob);

        // must NOT receive anything
        NEGATIVE_AWAIT
            .untilAsserted(() -> {
                List<String> received = new ArrayList<>();
                messages.drainTo(received);

                assertThat(received)
                    .as("Should not receive address book import events after unregister")
                    .noneSatisfy(msg -> assertThat(msg).contains("\"imports\""));
            });
    }

    @Test
    void websocketShouldReceiveEventsWhenRegisteringCalendarAndAddressBook(TwakeCalendarGuiceServer guiceServer) throws Exception {
        // Given: Bob owns a calendar and an address book
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();
        // Trigger an initial esn-Sabre provisioning
        importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);
        SECONDS.sleep(1);

        AddressBookURL addressBook = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBook.asUri().toString();

        // And: Bob opens a WebSocket connection
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // When: Bob registers BOTH calendar and address book
        webSocket.send("""
            {
                "register": ["%s", "%s"]
            }
            """.formatted(calendarUri, addressBookUri));

        // Then: registration ACK contains both
        String ack = awaitMessage(messages, msg -> msg.contains("registered"));
        assertThatJson(ack)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                    "registered": ["%s", "%s"]
                }
                """.formatted(calendarUri, addressBookUri));

        // When: import ICS into calendar
        String calendarImportId = importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);

        // Then: WebSocket receives calendar import event
        String calendarImportEvent = awaitMessage(messages, msg -> msg.contains("imports"));
        assertThat(calendarImportEvent)
            .as("WebSocket should receive calendar import event")
            .isNotNull();

        assertThatJson(calendarImportEvent)
            .isEqualTo("""
                {
                  "%s" : {
                    "imports" : {
                      "%s" : {
                        "status" : "completed",
                        "succeedCount" : 1,
                        "failedCount" : 0
                      }
                    }
                  }
                }
                """.formatted(calendarUri, calendarImportId));

        // When: import vCard into address book
        String addressBookImportId = importVCardIntoAddressBook(guiceServer, addressBook, bob);

        // Then: WebSocket receives address book import event
        CALMLY_AWAIT
            .untilAsserted(() -> {
                List<String> receivedMessages = new ArrayList<>();
                messages.drainTo(receivedMessages);

                assertThat(receivedMessages)
                    .as("Expected address book import event but got: %s", receivedMessages)
                    .anySatisfy(message ->
                        assertThatJson(message)
                            .isEqualTo("""
                                {
                                  "%s" : {
                                    "imports" : {
                                      "%s" : {
                                        "status" : "completed",
                                        "succeedCount": 1,
                                        "failedCount": 0
                                      }
                                    }
                                  }
                                }
                                """.formatted(addressBookUri, addressBookImportId))
                    );
            });
    }

    @Test
    void websocketShouldReceiveCalendarListCreatedEvent() {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Calendar Created", "#00ff00", "created");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();

        String createdCalendarUrl = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()))
            .asUri().toASCIIString();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"created\"")
            && message.contains(createdCalendarUrl));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "created": ["%s"]
                  }
                }
                """.formatted(createdCalendarUrl));
    }

    @Test
    void websocketShouldReceiveCalendarListUpdatedEvent() {
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Calendar Updated", "#00ff00", "updated");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();

        CalendarURL calendarURL = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));
        String calendarUri = calendarURL.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String payload = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propertyupdate xmlns:D="DAV:">
              <D:set>
                <D:prop>
                  <D:displayname>Updated Calendar</D:displayname>
                </D:prop>
              </D:set>
            </D:propertyupdate>
            """;
        davTestHelper.updateCalendar(bob, calendarURL, payload);

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"updated\"")
            && message.contains(calendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "updated": ["%s"]
                  }
                }
                """.formatted(calendarUri));
    }

    @Test
    void websocketShouldReceiveCalendarListDeletedEvent() {
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(UUID.randomUUID().toString(),
            "Calendar Deleted", "#00ff00", "deleted");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();
        CalendarURL calendarURL = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));
        String calendarUri = calendarURL.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        calDavClient.deleteCalendar(bob.username(), calendarURL).block();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"deleted\"")
            && message.contains(calendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "deleted": ["%s"]
                  }
                }
                """.formatted(calendarUri));
    }

    @Test
    void subscriberShouldReceiveCalendarListSubscribedEventWhenSubscribingToPublicCalendar() {
        CalendarURL aliceCalendar = CalendarURL.from(alice.id());
        calDavClient.updateCalendarAcl(alice.username(), aliceCalendar, PublicRight.READ).block();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        SubscribedCalendarRequest request = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(alice.id().value())
            .name("Alice public")
            .color("#00FF00")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(bob, request);
        String subscribedCalendarUri = new CalendarURL(bob.id(), new OpenPaaSId(request.id())).asUri().toASCIIString();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"subscribed\"")
            && message.contains(subscribedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "subscribed": ["%s"]
                  }
                }
                """.formatted(subscribedCalendarUri));
    }

    @Test
    void subscriberShouldReceiveCalendarListDeletedEventWhenSubscriberDeletesSubscribedCalendar() {
        CalendarURL aliceCalendar = CalendarURL.from(alice.id());
        calDavClient.updateCalendarAcl(alice.username(), aliceCalendar, PublicRight.READ).block();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        SubscribedCalendarRequest request = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(alice.id().value())
            .name("Alice public")
            .color("#00FF00")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(bob, request);
        CalendarURL subscribedCalendar = new CalendarURL(bob.id(), new OpenPaaSId(request.id()));
        String subscribedCalendarUri = subscribedCalendar.asUri().toASCIIString();

        awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"subscribed\"")
            && message.contains(subscribedCalendarUri));

        calDavClient.deleteCalendar(bob.username(), subscribedCalendar).block();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"deleted\"")
            && message.contains(subscribedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "deleted": ["%s"]
                  }
                }
                """.formatted(subscribedCalendarUri));
    }

    @Test
    void subscriberShouldReceiveCalendarListUpdatedEventWhenSubscriberRenamesSubscribedCalendar() {
        CalendarURL aliceCalendar = CalendarURL.from(alice.id());
        calDavClient.updateCalendarAcl(alice.username(), aliceCalendar, PublicRight.READ).block();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        SubscribedCalendarRequest request = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(alice.id().value())
            .name("Alice public")
            .color("#00FF00")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(bob, request);
        CalendarURL subscribedCalendar = new CalendarURL(bob.id(), new OpenPaaSId(request.id()));
        String subscribedCalendarUri = subscribedCalendar.asUri().toASCIIString();
        awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"subscribed\"")
            && message.contains(subscribedCalendarUri));

        String payload = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propertyupdate xmlns:D="DAV:">
              <D:set>
                <D:prop>
                  <D:displayname>Renamed subscribed</D:displayname>
                </D:prop>
              </D:set>
            </D:propertyupdate>
            """;
        davTestHelper.updateCalendar(bob, subscribedCalendar, payload);

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"updated\"")
            && message.contains(subscribedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "updated": ["%s"]
                  }
                }
                """.formatted(subscribedCalendarUri));
    }

    @Test
    void subscriberShouldReceiveCalendarListDeletedEventWhenOwnerHidesSubscribedCalendar() {
        String aliceCalendarId = UUID.randomUUID().toString();
        NewCalendar newCalendar = new NewCalendar(aliceCalendarId,
            "Alice Shared Calendar", "#22AA55", "shared");
        calDavClient.createNewCalendar(alice.username(), alice.id(), newCalendar).block();

        CalendarURL aliceCalendar = new CalendarURL(alice.id(), new OpenPaaSId(aliceCalendarId));
        calDavClient.updateCalendarAcl(alice.username(), aliceCalendar, PublicRight.READ).block();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        SubscribedCalendarRequest request = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(alice.id().value())
            .sourceCalendarId(aliceCalendarId)
            .name("Alice shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(bob, request);
        String subscribedCalendarUri = new CalendarURL(bob.id(), new OpenPaaSId(request.id())).asUri().toASCIIString();
        awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"subscribed\"")
            && message.contains(subscribedCalendarUri));

        calDavClient.updateCalendarAcl(alice.username(), aliceCalendar, PublicRight.HIDE_ALL_EVENT).block();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"deleted\"")
            && message.contains(subscribedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "deleted": ["%s"]
                  }
                }
                """.formatted(subscribedCalendarUri));
    }

    @Test
    void ownerShouldReceiveCalendarListUpdatedEventWhenOwnerGrantsDelegation() {
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Bob Shared", "#0055AA", "delegation update");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();
        CalendarURL bobCalendar = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));
        String bobCalendarUri = bobCalendar.asUri().toASCIIString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        davTestHelper.grantDelegation(bob, bobCalendar, alice, "dav:read-write");

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"updated\"")
            && message.contains(bobCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "updated": ["%s"]
                  }
                }
                """.formatted(bobCalendarUri));
    }

    @Test
    void delegateShouldReceiveCalendarListDelegatedEventWhenDelegationGranted() {
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Bob Delegated", "#0055AA", "delegated");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();
        CalendarURL bobCalendar = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));

        String ticket = generateTicket(alice);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        davTestHelper.grantDelegation(bob, bobCalendar, alice, "dav:read-write");
        CalendarURL delegatedCalendar = findDelegatedCalendarURL(alice);
        String delegatedCalendarUri = delegatedCalendar.asUri().toASCIIString();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"delegated\"")
            && message.contains(delegatedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "delegated": ["%s"]
                  }
                }
                """.formatted(delegatedCalendarUri));
    }

    @Test
    void ownerShouldReceiveCalendarListUpdatedEventWhenOwnerRevokesDelegation() {
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Bob Shared", "#0055AA", "delegation update");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();
        CalendarURL bobCalendar = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));
        String bobCalendarUri = bobCalendar.asUri().toASCIIString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        davTestHelper.grantDelegation(bob, bobCalendar, alice, "dav:read-write");
        awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"updated\"")
            && message.contains(bobCalendarUri));

        davTestHelper.revokeDelegation(bob, bobCalendar, alice);

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"updated\"")
            && message.contains(bobCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "updated": ["%s"]
                  }
                }
                """.formatted(bobCalendarUri));
    }

    @Test
    void delegateShouldReceiveCalendarListDeletedEventWhenDelegationRevoked() {
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Bob Delegated", "#0055AA", "delegated");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();
        CalendarURL bobCalendar = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));

        String ticket = generateTicket(alice);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        davTestHelper.grantDelegation(bob, bobCalendar, alice, "dav:read-write");
        CalendarURL delegatedCalendar = findDelegatedCalendarURL(alice);
        String delegatedCalendarUri = delegatedCalendar.asUri().toASCIIString();
        awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"delegated\"")
            && message.contains(delegatedCalendarUri));

        davTestHelper.revokeDelegation(bob, bobCalendar, alice);

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"deleted\"")
            && message.contains(delegatedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "deleted": ["%s"]
                  }
                }
                """.formatted(delegatedCalendarUri));
    }

    @Test
    void delegateShouldReceiveCalendarListDeletedEventWhenDelegateDeletesDelegatedCalendar() {
        NewCalendar newCalendar = new NewCalendar(UUID.randomUUID().toString(),
            "Bob Delegated", "#0055AA", "delegated");
        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();
        CalendarURL bobCalendar = new CalendarURL(bob.id(), new OpenPaaSId(newCalendar.id()));

        String ticket = generateTicket(alice);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        davTestHelper.grantDelegation(bob, bobCalendar, alice, "dav:read-write");
        CalendarURL delegatedCalendar = findDelegatedCalendarURL(alice);
        String delegatedCalendarUri = delegatedCalendar.asUri().toASCIIString();
        awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"delegated\"")
            && message.contains(delegatedCalendarUri));

        calDavClient.deleteCalendar(alice.username(), delegatedCalendar).block();

        String pushed = awaitMessage(messages, message -> message.contains("\"calendarList\"")
            && message.contains("\"deleted\"")
            && message.contains(delegatedCalendarUri));

        assertThatJson(pushed)
            .isEqualTo("""
                {
                  "calendarList": {
                    "deleted": ["%s"]
                  }
                }
                """.formatted(delegatedCalendarUri));
    }

    private CalendarURL findDelegatedCalendarURL(OpenPaaSUser delegate) {
        CalendarURL delegateDefaultCalendarURL = CalendarURL.from(delegate.id());
        AtomicReference<CalendarURL> delegatedCalendarURLRef = new AtomicReference<>();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<CalendarURL> delegateCalendars = calDavClient.findUserCalendars(delegate.username(), delegate.id())
                .collectList()
                .block();

            CalendarURL delegatedCalendarURL = delegateCalendars.stream()
                .filter(url -> !url.equals(delegateDefaultCalendarURL))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No delegated calendar found"));

            assertThat(delegatedCalendarURL).isNotNull();
            delegatedCalendarURLRef.set(delegatedCalendarURL);
        });

        return delegatedCalendarURLRef.get();
    }

    private String importVCardIntoAddressBook(TwakeCalendarGuiceServer server,
                                              AddressBookURL addressBookURL,
                                              OpenPaaSUser user) {

        String vcardUid = UUID.randomUUID().toString();
        byte[] vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid)
            .getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class)
            .saveUploadedFile(user.username(),
                new Upload(
                    "contact.vcf",
                    UploadedMimeType.TEXT_VCARD,
                    Instant.now(),
                    (long) vcard.length,
                    vcard));

        String requestBody = """
            {
                "fileId": "%s",
                "target": "%s.json"
            }
            """.formatted(fileId.value(), addressBookURL.asUri().toASCIIString());

        return given()
            .auth().preemptive().basic(user.username().asString(), PASSWORD)
            .port(restApiPort)
            .contentType("application/json")
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED)
            .extract()
            .jsonPath()
            .getString("importId");
    }

    private String importIcsBytesIntoCalendar(TwakeCalendarGuiceServer guiceServer,
                                              CalendarURL calendarURL,
                                              String calendarUri,
                                              OpenPaaSUser user,
                                              byte[] icsContent) {
        OpenPaaSId fileId = guiceServer.getProbe(CalendarDataProbe.class)
            .saveUploadedFile(user.username(),
                new Upload(
                    "import.ics",
                    UploadedMimeType.TEXT_CALENDAR,
                    Instant.now(),
                    (long) icsContent.length,
                    icsContent));

        // Ensure calendar directory is activated
        guiceServer.getProbe(CalendarDataProbe.class)
            .exportCalendarFromCalDav(calendarURL, MailboxSessionUtil.create(user.username()));

        return given()
            .auth().preemptive().basic(user.username().asString(), PASSWORD)
            .port(restApiPort)
            .contentType("application/json")
            .body("""
                {
                    "fileId": "%s",
                    "target": "%s.json"
                }
                """.formatted(fileId.value(), calendarUri))
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED)
            .extract()
            .jsonPath()
            .getString("importId");
    }

    private String importIcsIntoCalendar(TwakeCalendarGuiceServer guiceServer,
                                         CalendarURL calendarURL,
                                         String calendarUri,
                                         OpenPaaSUser user) {
        String uid = UUID.randomUUID().toString();
        byte[] ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Imported Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid).getBytes(StandardCharsets.UTF_8);
        return importIcsBytesIntoCalendar(guiceServer, calendarURL, calendarUri, user, ics);
    }
}
