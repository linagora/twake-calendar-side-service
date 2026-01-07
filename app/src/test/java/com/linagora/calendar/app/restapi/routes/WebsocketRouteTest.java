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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedMimeType;

import org.apache.http.HttpStatus;

import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.utils.GuiceProbe;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.Inject;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.PublicRight;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

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
    }

    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(EventBusProbe.class));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private CalDavClient calDavClient;

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
    }

    @AfterEach
    void tearDown() {
        if (webSocket != null) {
            webSocket.close(1000, "test finished");
        }
    }

    @Test
    void websocketShouldAllowSubscriptionForOwnerWithValidTicket() throws Exception {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String registerCalendarURL = CalendarURL.from(bob.id()).asUri().toString();
        webSocket.send(String.format("""
            {
                "register": ["%s"]
            }
            """, registerCalendarURL));

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

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
    void websocketShouldReturnNotRegisteredWhenNoRights() throws Exception {
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

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();
        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "Forbidden" }
                }""".formatted(foreignCalendarUrl));
    }

    @Test
    void websocketShouldReturnNotRegisteredWhenCalendarDoesNotExist() throws Exception {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String nonExistentCalendar = new CalendarURL(bob.id(), new OpenPaaSId(UUID.randomUUID().toString())).asUri().toString();

        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(nonExistentCalendar));

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "NotFound" }
                }""".formatted(nonExistentCalendar));
    }

    @Test
    void websocketShouldRejectRequestWhenRegisterAndUnregisterSameCalendar() throws Exception {
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
        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .node("error")
            .asString()
            .startsWith("register and unregister cannot contain duplicated entries");
    }

    @Test
    void websocketShouldNoopWhenRegisterAndUnregisterAreBothEmpty() throws Exception {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            {
                "register": [],
                "unregister": []
            }
            """);

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .isEqualTo("{}");
    }

    @Test
    void websocketShouldAllowRegisterWhenUserHasRights() throws Exception {
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

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .isEqualTo("""
                {
                    "registered": ["%s"]
                }
                """.formatted(registerCalendarURL));
    }

    @Test
    void websocketShouldUnregisterSuccessfully() throws Exception {
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            {
                "unregister": ["%s"]
            }
            """.formatted(calendarUri));

        String unregJson = messages.poll(5, TimeUnit.SECONDS);
        assertThat(unregJson).isNotNull();
        assertThatJson(unregJson)
            .isEqualTo("""
                {
                    "unregistered": ["%s"]
                }
                """.formatted(calendarUri));
    }

    @Test
    void websocketShouldHandleMixedRegisterAndUnregister() throws Exception {
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

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

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
    void websocketShouldReceiveEventPushedFromEventBus(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
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
        String pushed = messages.poll(10, TimeUnit.SECONDS);
        assertThat(pushed)
            .as("WebSocket should receive pushed calendar change event")
            .isNotNull();

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
    void websocketShouldReturnUnregisteredWhenUnregisteringIdempotently() throws Exception {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        webSocket.send("""
            {
                "unregister": ["%s"]
            }
            """.formatted(calendarUri));

        String json = messages.poll(5, TimeUnit.SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .isEqualTo("""
                {
                    "unregistered": ["%s"]
                }
                """.formatted(calendarUri));
    }

    @Test
    void registeringSameCalendarTwiceShouldStillReturnRegistered() throws Exception {
        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String first = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(first).isEqualTo("""
            { "registered": ["%s"] }
            """.formatted(calendarUri));

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String second = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(second).isEqualTo("""
            { "registered": ["%s"] }
            """.formatted(calendarUri));
    }

    @Test
    void websocketShouldStopReceivingEventsAfterUnregister(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack).isEqualTo("""
            { "registered": ["%s"] }
            """.formatted(calendarUri));

        // Step 2: Dispatch event #1 → MUST receive
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        CalendarChangeEvent event1 = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event1, calendar);

        String pushed1 = messages.poll(5, TimeUnit.SECONDS);
        assertThat(pushed1).as("WebSocket should receive event after register").isNotNull();

        assertThatJson(pushed1)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarUri));

        // Step 3: Unregister A
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(calendarUri));

        String unregAck = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(unregAck)
            .isEqualTo("""
                { "unregistered": ["%s"] }
                """.formatted(calendarUri));

        // Step 4: Dispatch event #2 → MUST NOT receive anything
        CalendarChangeEvent event2 = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event2, calendar);

        // Try to read event, expect timeout → null
        String pushed2 = messages.poll(3, TimeUnit.SECONDS);
        assertThat(pushed2)
            .as("WebSocket should NOT receive events after unregister")
            .isNull();
    }

    @Test
    void websocketShouldNotReceiveDuplicateEventsWhenRegisteredTwice(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
        String ack1 = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack1)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));
        String ack2 = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack2)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: an event is dispatched
        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);
        CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
        eventBusProbe.dispatch(event, calendar);

        // Then: websocket receives exactly one event
        String pushed = messages.poll(5, TimeUnit.SECONDS);
        assertThat(pushed)
            .as("WebSocket should receive ONE event even after double registration")
            .isNotNull();

        assertThatJson(pushed).isEqualTo("""
            { "%s" : { "syncToken": "${json-unit.ignore}" } }
            """.formatted(calendarUri));

        // And: no duplicate event arrives later
        String duplicate = messages.poll(2, TimeUnit.SECONDS);
        assertThat(duplicate)
            .as("WebSocket should NOT receive duplicate event after multiple register calls")
            .isNull();
    }

    @Test
    void websocketShouldNotReceiveEventsFromUnregisteredCalendar(TwakeCalendarGuiceServer guiceServer) throws Exception {
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

        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarA));

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // When: event is dispatched for calendar B (unregistered)
        CalendarURL urlB = new CalendarURL(bob.id(), new OpenPaaSId(newCalendarB.id()));
        CalendarChangeEvent eventB = new CalendarChangeEvent(Event.EventId.random(), urlB);
        eventBusProbe.dispatch(eventB, urlB);

        // Then: websocket should NOT receive any event for B
        String pushedB = messages.poll(2, TimeUnit.SECONDS);
        assertThat(pushedB)
            .as("WebSocket must NOT receive events for unregistered calendar B")
            .isNull();
    }

    @Test
    void websocketShouldReceiveEventsFromRemainingCalendarAfterUnregister(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
        String ack = messages.poll(5, TimeUnit.SECONDS);
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

        String unregAck = messages.poll(5, TimeUnit.SECONDS);
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
        String pushedB = messages.poll(5, TimeUnit.SECONDS);
        assertThat(pushedB)
            .as("WebSocket must receive event for B")
            .isNotNull();

        assertThatJson(pushedB)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarB));
    }

    @Test
    void websocketShouldReceiveEventsForAllRegisteredCalendars(TwakeCalendarGuiceServer guiceServer) throws Exception {
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

        String ack = messages.poll(5, TimeUnit.SECONDS);
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
        String pushedA = messages.poll(5, TimeUnit.SECONDS);
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
        String pushedB = messages.poll(5, TimeUnit.SECONDS);
        assertThat(pushedB).isNotNull();
        assertThatJson(pushedB)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarB));
    }

    @Test
    void websocketShouldStopReceivingEventsWhenCalendarStopsBeingPublic(TwakeCalendarGuiceServer guiceServer) throws Exception {
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

        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarBUri));

        EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

        // When: event is dispatched for calendar B (public)
        CalendarChangeEvent event1 = new CalendarChangeEvent(Event.EventId.random(),  calendarB);
        eventBusProbe.dispatch(event1, calendarB);

        // Then: Bob MUST receive the event
        String pushed1 = messages.poll(5, TimeUnit.SECONDS);
        assertThat(pushed1).isNotNull();
        assertThatJson(pushed1)
            .isEqualTo("""
                { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                """.formatted(calendarBUri));

        // And: Alice removes public sharing
        calDavClient.updateCalendarAcl(alice.username(), calendarB, PublicRight.HIDE_ALL_EVENT).block();

        // When: event is dispatched again after access revoked
        CalendarChangeEvent event2 = new CalendarChangeEvent(Event.EventId.random(), calendarB);
        eventBusProbe.dispatch(event2, calendarB);

        // Then: Bob MUST NOT receive this event anymore
        String pushed2 = messages.poll(2, TimeUnit.SECONDS);
        assertThat(pushed2)
            .as("Bob must not receive events after calendar is no longer publicly shared")
            .isNull();
    }

    @Test
    void websocketShouldBroadcastEventsToAllSubscribedClients(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
            String ack1 = messages1.poll(5, TimeUnit.SECONDS);
            assertThatJson(ack1)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            ws2.send("""
                { "register": ["%s"] }
                """.formatted(calendarUri));

            String ack2 = messages2.poll(5, TimeUnit.SECONDS);
            assertThatJson(ack2)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

            // When: event is dispatched for A
            CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
            eventBusProbe.dispatch(event, calendar);

            // Then: BOTH WS clients must receive the event
            String pushed1 = messages1.poll(5, TimeUnit.SECONDS);
            String pushed2 = messages2.poll(5, TimeUnit.SECONDS);

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
    void websocketShouldIsolateUnregisterAcrossMultipleClients(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
            String ack1 = messages1.poll(5, TimeUnit.SECONDS);
            assertThatJson(ack1)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            // And: WS2 registers the same calendar
            ws2.send("""
                { "register": ["%s"] }
                """.formatted(calendarUri));
            String ack2 = messages2.poll(5, TimeUnit.SECONDS);
            assertThatJson(ack2)
                .isEqualTo("""
                    { "registered": ["%s"] }
                    """.formatted(calendarUri));

            // And: WS1 unregisters calendar
            ws1.send("""
                { "unregister": ["%s"] }
                """.formatted(calendarUri));
            String unregAck = messages1.poll(5, TimeUnit.SECONDS);
            assertThatJson(unregAck)
                .isEqualTo("""
                    { "unregistered": ["%s"] }
                    """.formatted(calendarUri));

            EventBusProbe eventBusProbe = guiceServer.getProbe(EventBusProbe.class);

            // When: event is dispatched for this calendar
            CalendarChangeEvent event = new CalendarChangeEvent(Event.EventId.random(), calendar);
            eventBusProbe.dispatch(event, calendar);

            // Then: WS1 MUST NOT receive any event
            String pushed1 = messages1.poll(2, TimeUnit.SECONDS);
            assertThat(pushed1)
                .as("WS1 should NOT receive event after unregister")
                .isNull();

            // And: WS2 MUST receive the event
            String pushed2 = messages2.poll(5, TimeUnit.SECONDS);
            assertThat(pushed2)
                .as("WS2 should still receive event")
                .isNotNull();

            assertThatJson(pushed2)
                .isEqualTo("""
                    { "%s" : { "syncToken" : "${json-unit.ignore}" } }
                    """.formatted(calendarUri));
        } finally {
            ws1.close(1000, "done");
            ws2.close(1000, "done");
        }
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
        return client.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                messages.offer(text);
            }
        });
    }

    @Test
    void websocketShouldReceiveEventWhenImportingIntoRegisteredCalendar(TwakeCalendarGuiceServer guiceServer) throws Exception {
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
        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: import ICS into the registered calendar using the helper
        String importId = importIcsIntoCalendar(guiceServer, calendarABC, calendarUri, bob);

        // Then: the WebSocket client should receive an import-completed notification
        String pushed = messages.poll(10, TimeUnit.SECONDS);
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
    void websocketShouldReceiveFailedImportEventWhenImportFails(TwakeCalendarGuiceServer guiceServer) throws Exception {
        // Given: Bob owns a calendar and subscribes via WebSocket
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: import an INVALID ICS file
        String importId = importIcsBytesIntoCalendar(guiceServer, calendar, calendarUri, bob, "NOT_A_VALID_ICS".getBytes(StandardCharsets.UTF_8));
        // Then: WebSocket receives FAILED import event
        String pushed = messages.poll(10, TimeUnit.SECONDS);
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
    void websocketShouldReceiveImportEventAndCalendarSyncEvent(TwakeCalendarGuiceServer guiceServer) throws Exception {
        // Given: Bob owns a calendar and subscribes to it via WebSocket
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = calendar.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(calendarUri));

        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // When: import ICS into the registered calendar
        String importId = importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);

        // Then: WebSocket receives the import-completed notification
        String importMessage = messages.poll(10, TimeUnit.SECONDS);
        assertThat(importMessage)
            .as("WebSocket should receive import-completed notification")
            .isNotNull();

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
        String syncMessage = messages.poll(5, TimeUnit.SECONDS);
        assertThat(syncMessage)
            .as("WebSocket should receive calendar syncToken notification")
            .isNotNull();

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
    void websocketShouldNotReceiveImportEventWhenCalendarNotRegistered(TwakeCalendarGuiceServer guiceServer) throws Exception {
        CalendarURL calendar = CalendarURL.from(bob.id());
        String calendarUri = CalendarURL.from(bob.id()).asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // Import without registering the calendar
        importIcsIntoCalendar(guiceServer, calendar, calendarUri, bob);

        String pushed = messages.poll(3, TimeUnit.SECONDS);
        assertThat(pushed)
            .as("WebSocket should NOT receive import event when calendar is not registered")
            .isNull();
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

        String ack = messages.poll(5, TimeUnit.SECONDS);
        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(calendarUri));

        // Unregister calendar
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(calendarUri));

        String unregAck = messages.poll(5, TimeUnit.SECONDS);
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
    void websocketShouldReceiveEventWhenImportingIntoRegisteredAddressBook(TwakeCalendarGuiceServer guiceServer) throws Exception {
        String addressBookId = "collected";
        AddressBookURL addressBook = new AddressBookURL(bob.id(), addressBookId);
        String addressBookUri = addressBook.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(addressBookUri));

        String ack = messages.poll(10, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();

        assertThatJson(ack)
            .isEqualTo("""
                { "registered": ["%s"] }
                """.formatted(addressBookUri));

        String importId = importVCardIntoAddressBook(guiceServer, addressBook, bob);

        // Then: WebSocket receives import-completed notification
        String pushed = messages.poll(10, TimeUnit.SECONDS);
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
    void websocketShouldReturnNotRegisteredWhenNoRightsOnAddressBook() throws Exception {
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
        String json = messages.poll(10, SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "Forbidden" }
                }
                """.formatted(aliceAddressBookUri));
    }

    @Test
    void websocketShouldReturnNotRegisteredWhenAddressBookDoesNotExist() throws Exception {
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

        String json = messages.poll(10, SECONDS);
        assertThat(json).isNotNull();

        assertThatJson(json)
            .isEqualTo("""
                {
                    "notRegistered": { "%s" : "NotFound" }
                }
                """.formatted(addressBookUri));
    }

    @Test
    void websocketShouldNotReceiveAddressBookEventsAfterUnregister(TwakeCalendarGuiceServer guiceServer) throws Exception {
        AddressBookURL addressBook = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBook.asUri().toString();

        String ticket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, ticket, messages);

        // register
        webSocket.send("""
            { "register": ["%s"] }
            """.formatted(addressBookUri));
        messages.poll(10, SECONDS);

        // unregister
        webSocket.send("""
            { "unregister": ["%s"] }
            """.formatted(addressBookUri));
        messages.poll(10, SECONDS);

        // import vcard
        importVCardIntoAddressBook(guiceServer, addressBook, bob);

        // must NOT receive anything
        String pushed = messages.poll(3, SECONDS);
        assertThat(pushed).isNull();
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
        String ack = messages.poll(10, SECONDS);
        assertThat(ack).isNotNull();

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
        String calendarImportEvent = messages.poll(10, SECONDS);
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
        await()
            .atMost(10, SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
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