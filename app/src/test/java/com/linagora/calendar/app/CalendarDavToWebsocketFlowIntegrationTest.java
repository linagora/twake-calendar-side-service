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

import static com.linagora.calendar.app.TestFixture.awaitMessage;
import static com.linagora.calendar.app.TestFixture.connectWebSocket;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.path.json.JsonPath;
import okhttp3.WebSocket;

class CalendarDavToWebsocketFlowIntegrationTest {

    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @Order(2)
    @RegisterExtension
    static DockerRedisExtension dockerExtension = new DockerRedisExtension();

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
        new AbstractModule() {
            @Provides
            @Singleton
            public RedisConfiguration redisConfiguration() {
                return StandaloneRedisConfiguration.from(dockerExtension.redisURI().toString());
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
    void bobShouldReceiveWebsocketPushWhenAliceInvitesHim() {
        // GIVEN: Bob opens WebSocket
        String bobTicket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, bobTicket, messages);
        String bobCalendarUrl = CalendarURL.from(bob.id()).asUri().toString();

        // Bob registers his calendar
        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(bobCalendarUrl));

        String registerResponse = awaitMessage(messages, msg -> msg.contains("\"registered\""));
        assertThatJson(registerResponse)
            .node("registered")
            .isArray()
            .contains(bobCalendarUrl);

        // WHEN: Alice creates ICS event and invites Bob
        String eventUid = "event-" + System.currentTimeMillis();
        String ics = buildEventICS(eventUid, alice.username().asString(), bob.username().asString());

        davTestHelper.upsertCalendar(alice, ics, eventUid);

        String pushMessage = awaitMessage(messages, msg -> msg.contains("syncToken") && msg.contains(bobCalendarUrl));

        assertThatJson(pushMessage)
            .isEqualTo("""
                {
                  "%s" : {
                    "syncToken" : "${json-unit.ignore}"
                  }
                }
                """.formatted(bobCalendarUrl));
    }

    @Test
    void bobShouldFetchEventsFromDavUsingReceivedSyncToken() {
        // GIVEN: Bob opens WebSocket
        String bobTicket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, bobTicket, messages);
        String bobCalendarUrl = CalendarURL.from(bob.id()).asUri().toString();

        webSocket.send("""
            {
                "register": ["%s"]
            }
            """.formatted(bobCalendarUrl));

        // WHEN: Alice creates an event
        String eventUid = "event-" + System.currentTimeMillis();
        String ics = buildEventICS(eventUid, alice.username().asString(), bob.username().asString());
        davTestHelper.upsertCalendar(alice, ics, eventUid);

        // Bob receives websocket notification (skip unrelated messages)
        String pushMessage = awaitMessage(messages, msg -> msg.contains("syncToken"));
        Pair<CalendarURL, String> pair = extractCalendarUrlAndSyncToken(pushMessage);
        String syncToken = pair.getRight();
        assertThat(syncToken).isNotBlank();

        // THEN: Bob fetches changes from Sabre DAV using syncToken
        String davResponse = davTestHelper.fetchEventsBySyncToken(bob, pair.getKey(), syncToken)
            .block();

        assertThat(davResponse).isNotNull();
        assertThatJson(davResponse)
            .node("sync-token")
            .isEqualTo(syncToken);

        assertThatJson(davResponse)
            .node("_links.self.href")
            .isEqualTo(bobCalendarUrl + ".json");
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

    private Pair<CalendarURL, String> extractCalendarUrlAndSyncToken(String pushMessage) {
        Map<String, Object> root = JsonPath.from(pushMessage).get();
        Map.Entry<String, Object> entry = root.entrySet().iterator().next();
        String calendarUrlString = entry.getKey();
        Map<String, Object> inner = (Map<String, Object>) entry.getValue();
        String syncToken = (String) inner.get("syncToken");
        return Pair.of(CalendarURL.deserialize(Strings.CS.removeStart(calendarUrlString, "/calendars/")), syncToken);
    }

    private String buildEventICS(String eventUid, String organizer, String attendee) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        String next = LocalDateTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        String dtStamp = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));

        String template = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:{DTSTAMP}Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:{START}
            DTEND;TZID=Asia/Ho_Chi_Minh:{END}
            SUMMARY:WebSocket Flow Test
            ORGANIZER:mailto:{ORGANIZER}
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:{ATTENDEE}
            END:VEVENT
            END:VCALENDAR
            """;
        return template
            .replace("{UID}", eventUid)
            .replace("{DTSTAMP}", dtStamp)
            .replace("{START}", now)
            .replace("{END}", next)
            .replace("{ORGANIZER}", organizer)
            .replace("{ATTENDEE}", attendee);
    }
}
