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

import static com.linagora.calendar.scheduling.AlarmEventSchedulerConfiguration.BATCH_SIZE_DEFAULT;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpStatus;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.apache.james.core.MailAddress;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.scheduling.AlarmEventSchedulerConfiguration;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.event.AlarmAction;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

class DisplayAlarmWebsocketFlowIntegrationTest {

    static class AlarmEventStoreProbe implements GuiceProbe {
        private final AlarmEventDAO alarmEventDAO;

        @Inject
        AlarmEventStoreProbe(AlarmEventDAO alarmEventDAO) {
            this.alarmEventDAO = alarmEventDAO;
        }

        public Optional<AlarmEvent> find(String eventUid, String recipient) {
            return alarmEventDAO.find(new EventUid(eventUid),
                Throwing.supplier(() -> new MailAddress(recipient)).get()).blockOptional();
        }
    }

    private static final String PASSWORD = "secret";
    private static final Duration TRIGGER = Duration.ofMinutes(15);

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @Order(2)
    @RegisterExtension
    static DockerRedisExtension dockerExtension = new DockerRedisExtension();

    static UpdatableTickingClock clock = new UpdatableTickingClock(Instant.now());

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

            @Override
            protected void configure() {
                bind(AlarmEventSchedulerConfiguration.class)
                    .toInstance(new AlarmEventSchedulerConfiguration(
                        Duration.ofSeconds(1),  // pollInterval
                        BATCH_SIZE_DEFAULT,
                        Duration.ofMillis(100), // jitter
                        AlarmEventSchedulerConfiguration.Mode.CLUSTER));

                bind(Clock.class).toInstance(clock);
                Multibinder.newSetBinder(binder(), GuiceProbe.class)
                    .addBinding().to(AlarmEventStoreProbe.class);
            }
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private DavTestHelper davTestHelper;
    private OpenPaaSUser bob;
    private int restApiPort;
    private WebSocket webSocket;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        this.bob = sabreDavExtension.newTestUser(Optional.of("bob"));

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(bob.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(bob.username(), PASSWORD);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        // Reset clock to current time for each test
        clock.setInstant(Instant.now());
    }

    @AfterEach
    void tearDown() {
        if (webSocket != null) {
            webSocket.close(1000, "test finished");
        }
    }

    @Test
    void shouldReceiveDisplayAlarmViaWebsocket(TwakeCalendarGuiceServer server) throws Exception {
        AlarmEventStoreProbe alarmStore = server.getProbe(AlarmEventStoreProbe.class);

        // GIVEN: Bob creates an event with a DISPLAY alarm
        String eventUid = UUID.randomUUID().toString();
        String ics = buildEventICSWithDisplayAlarm(eventUid, bob.username().asString());
        davTestHelper.upsertCalendar(bob, ics, eventUid);

        // Wait for alarm event to be created
        Fixture.awaitAtMost
            .untilAsserted(() -> {
                Optional<AlarmEvent> alarmEventOpt = alarmStore.find(eventUid, bob.username().asString());
                assertThat(alarmEventOpt).isPresent();
                assertThat(alarmEventOpt.get().action()).isEqualTo(AlarmAction.DISPLAY);
            });

        // Bob opens WebSocket and enables display notifications
        String bobTicket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, bobTicket, messages);

        // Bob enables display notifications
        webSocket.send("{\"enableDisplayNotification\": true}");

        // Validate enableDisplayNotification response
        String enableResponse = messages.poll(10, TimeUnit.SECONDS);
        assertThat(enableResponse).isNotNull();
        assertThatJson(enableResponse)
            .isEqualTo("{\"displayNotificationEnabled\":true}");

        // WHEN: Advance time to after trigger window starts (start - 15m + 1s)
        Calendar calendar = CalendarUtil.parseIcs(ics);
        ZonedDateTime start = EventParseUtils.getStartTime((VEvent) calendar.getComponents("VEVENT").getFirst());
        clock.setInstant(start.minus(TRIGGER).plusSeconds(1).toInstant());

        // THEN: Bob receives the alarm notification via WebSocket
        Fixture.awaitAtMost.untilAsserted(() -> {
            String alarmMessage = messages.poll(1, TimeUnit.SECONDS);
            assertThat(alarmMessage).isNotNull();

            String expected = """
                {
                    "alarms": [
                        {
                            "eventSummary": "Display Alarm Test",
                            "eventURL": "{eventUrl}",
                            "eventStartTime": "{eventStartTime}"
                        }
                    ]
                }
                """.replace("{eventUrl}", "/calendars/" + bob.id().value() + "/" + bob.id().value() + "/" + eventUid + ".ics")
                  .replace("{eventStartTime}", start.toInstant().toString());

            assertThatJson(alarmMessage)
                .isEqualTo(expected);
        });
    }

    @Test
    void shouldNotReceiveDisplayAlarmWithoutEnablingDisplayNotification(TwakeCalendarGuiceServer server) throws Exception {
        AlarmEventStoreProbe alarmStore = server.getProbe(AlarmEventStoreProbe.class);

        // GIVEN: Bob creates an event with a DISPLAY alarm
        String eventUid = UUID.randomUUID().toString();
        String ics = buildEventICSWithDisplayAlarm(eventUid, bob.username().asString());
        davTestHelper.upsertCalendar(bob, ics, eventUid);

        // Wait for alarm event to be created
        Fixture.awaitAtMost
            .untilAsserted(() -> {
                Optional<AlarmEvent> alarmEventOpt = alarmStore.find(eventUid, bob.username().asString());
                assertThat(alarmEventOpt).isPresent();
                assertThat(alarmEventOpt.get().action()).isEqualTo(AlarmAction.DISPLAY);
            });

        // Bob opens WebSocket but does NOT enable display notifications
        String bobTicket = generateTicket(bob);
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        webSocket = connectWebSocket(restApiPort, bobTicket, messages);

        // WHEN: Advance time to trigger window
        Calendar calendar = CalendarUtil.parseIcs(ics);
        ZonedDateTime start = EventParseUtils.getStartTime((VEvent) calendar.getComponents("VEVENT").getFirst());
        clock.setInstant(start.minus(TRIGGER).plusSeconds(1).toInstant());

        // Wait some time for the alarm scheduler to process
        Thread.sleep(3000);

        // THEN: Bob should NOT receive any alarm notification
        String alarmMessage = messages.poll(2, TimeUnit.SECONDS);
        assertThat(alarmMessage).isNull();
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
            .readTimeout(30, TimeUnit.SECONDS)
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

    private String buildEventICSWithDisplayAlarm(String eventUid, String organizer) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        // Set event start 3 days in future - alarm will trigger 15 minutes before
        LocalDateTime baseTime = clock.instant().atZone(clock.getZone()).plusDays(3).toLocalDateTime();
        String startDateTime = baseTime.format(dateTimeFormatter);
        String endDateTime = baseTime.plusHours(1).format(dateTimeFormatter);
        String dtStamp = baseTime.minusDays(3).format(dateTimeFormatter);

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:%sZ
            DTSTART;TZID=Asia/Ho_Chi_Minh:%s
            DTEND;TZID=Asia/Ho_Chi_Minh:%s
            SUMMARY:Display Alarm Test
            ORGANIZER:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:%s
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:DISPLAY
            DESCRIPTION:Browser Display Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.formatted(
                eventUid,
                dtStamp,
                startDateTime,
                endDateTime,
                organizer,
                organizer);
    }
}
