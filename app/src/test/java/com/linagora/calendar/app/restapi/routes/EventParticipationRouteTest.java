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

import static com.linagora.calendar.app.restapi.routes.ImportRouteTest.mailSenderConfigurationFunction;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.notNullValue;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.fge.lambdas.Throwing;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.api.Participation;
import com.linagora.calendar.api.Participation.ParticipantAction;
import com.linagora.calendar.api.ParticipationTokenSigner;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.CalendarEventReportResponse;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import net.fortuna.ical4j.model.parameter.PartStat;

public class EventParticipationRouteTest {

    static class ParticipationTokenProbe implements GuiceProbe {
        private final ParticipationTokenSigner participationTokenSigner;

        @Inject
        ParticipationTokenProbe(ParticipationTokenSigner participationTokenSigner) {
            this.participationTokenSigner = participationTokenSigner;
        }

        public String signAsJwt(Participation participation) {
            return participationTokenSigner.signAsJwt(participation)
                .block();
        }

        public Participation validateAndExtractParticipation(String jwt) {
            return participationTokenSigner.validateAndExtractParticipation(jwt).block();
        }
    }

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class).addBinding()
                .to(ParticipationTokenProbe.class);
            binder.bind(MailSenderConfiguration.class)
                .toInstance(mailSenderConfigurationFunction.apply(mockSmtpExtension));
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser attendee;
    private OpenPaaSUser organizer;

    private int restApiPort;

    private DavTestHelper davTestHelper;

    private RequestSpecification attendeeRequestSpecification;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        this.attendee = sabreDavExtension.newTestUser();
        this.organizer = sabreDavExtension.newTestUser();

        String password = "secret";
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
        server.getProbe(CalendarDataProbe.class).addUserToRepository(attendee.username(), password);
        server.getProbe(CalendarDataProbe.class).addUserToRepository(organizer.username(), password);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(attendee.username().asString());
        auth.setPassword(password);

        attendeeRequestSpecification = new RequestSpecBuilder()
            .setPort(restApiPort)
            .setAuth(auth)
            .setBasePath("")
            .setAccept(JSON)
            .setContentType(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .build();
    }

    @Test
    void shouldReturnValidResponseWhenValidLinkIsAccessed(TwakeCalendarGuiceServer server) {
        setUserLanguage(Locale.FRANCE);
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid);

        Participation participation = getParticipation(eventUid, ParticipantAction.ACCEPTED);
        URL participationTokenUrl = getParticipationTokenUrl(participation, server);

        String actualResponse = RestAssured
            .given()
            .when()
            .get(participationTokenUrl)
            .then()
            .statusCode(200)
            .contentType(JSON)
            .extract()
            .body().asString();

        assertThatJson(actualResponse)
            .isEqualTo("""
                {
                  "eventJSON": "${json-unit.ignore}",
                  "attendeeEmail": "%s",
                  "locale": "fr",
                  "links": {
                    "yes": "${json-unit.ignore}",
                    "no": "${json-unit.ignore}",
                    "maybe": "${json-unit.ignore}"
                  }
                }
                """.formatted(attendee.username().asString()));


        assertThatJson(actualResponse)
            .inPath("eventJSON")
            .isEqualTo("""
                    [
                        "vcalendar",
                        [
                            ["version", {}, "text", "2.0"],
                            ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.1.3//EN"],
                            ["calscale", {}, "text", "GREGORIAN"]
                        ],
                        [
                            [
                                "vevent",
                                [
                                    ["uid", {}, "text", "${json-unit.ignore}"],
                                    ["dtstamp", {}, "date-time", "${json-unit.ignore}"],
                                    ["sequence", {}, "integer", 2],
                                    ["dtstart", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "${json-unit.ignore}"],
                                    ["dtend", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "${json-unit.ignore}"],
                                    ["summary", {}, "text", "Twake Calendar - Sprint planning #04"],
                                    ["organizer", {"cn": "Van Tung TRAN", "schedule-status": "1.1"}, "cal-address", "${json-unit.ignore}"],
                                    ["attendee", {"cn": "Benoît TELLIER", "partstat": "ACCEPTED"}, "cal-address", "${json-unit.ignore}"]
                                ],
                                []
                            ]
                        ]
                    ]
                """);
    }

    @Test
    void shouldUpdatePartStatOnDavServerWhenParticipationLinkIsAccessed(TwakeCalendarGuiceServer server) throws Exception {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid);

        Participation participation = getParticipation(eventUid, ParticipantAction.REJECTED);
        URL participationTokenUrl = getParticipationTokenUrl(participation, server);

        RestAssured
            .given()
            .when()
            .get(participationTokenUrl)
            .then()
            .statusCode(200)
            .contentType(JSON);

        String calendarEventReportResponse = getCalendarEventReportResponse(eventUid);
        assertThat(calendarEventReportResponse).contains("\"partstat\" : \"DECLINED\"");
    }

    @Test
    void shouldReturnValidJwtLinksInResponse(TwakeCalendarGuiceServer server) {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid);

        Participation originalParticipation = getParticipation(eventUid, ParticipantAction.TENTATIVE);
        URL participationTokenUrl = getParticipationTokenUrl(originalParticipation, server);

        Map<String, String> links = RestAssured
            .given()
            .when()
            .get(participationTokenUrl)
            .then()
            .statusCode(200)
            .contentType(JSON)
            .extract()
            .jsonPath()
            .getMap("links");

        String expectedPrefix = "https://excal.linagora.com/calendar/#/calendar/participation/?jwt=";
        ParticipationTokenProbe tokenProbe = server.getProbe(ParticipationTokenProbe.class);

        assertSoftly(softly -> {
            softly.assertThat(links.get("yes")).startsWith(expectedPrefix);
            softly.assertThat(links.get("no")).startsWith(expectedPrefix);
            softly.assertThat(links.get("maybe")).startsWith(expectedPrefix);

            String jwtYes = extractJwtFromUrl(links.get("yes"));
            String jwtNo = extractJwtFromUrl(links.get("no"));
            String jwtMaybe = extractJwtFromUrl(links.get("maybe"));

            Participation actualYes = tokenProbe.validateAndExtractParticipation(jwtYes);
            Participation actualNo = tokenProbe.validateAndExtractParticipation(jwtNo);
            Participation actualMaybe = tokenProbe.validateAndExtractParticipation(jwtMaybe);

            Participation base = Throwing.supplier(() -> new Participation(
                organizer.username().asMailAddress(),
                attendee.username().asMailAddress(),
                eventUid,
                attendee.id().value(),
                ParticipantAction.ACCEPTED)).get();

            softly.assertThat(actualYes).isEqualTo(base);
            softly.assertThat(actualNo).isEqualTo(base.withAction(ParticipantAction.REJECTED));
            softly.assertThat(actualMaybe).isEqualTo(base.withAction(ParticipantAction.TENTATIVE));
        });
    }

    @Test
    void participationTokenUrlShouldReturnStableLinks(TwakeCalendarGuiceServer server) {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid);

        Participation participation = getParticipation(eventUid, ParticipantAction.ACCEPTED);
        URL participationTokenUrl = getParticipationTokenUrl(participation, server);

        for (int i = 0; i < 3; i++) {
            Map<String, String> links = RestAssured
                .given()
                .when()
                .get(participationTokenUrl)
                .then()
                .statusCode(200)
                .contentType(JSON)
                .extract()
                .jsonPath()
                .getMap("links");

            assertSoftly(softly -> {
                softly.assertThat(links.get("yes")).isNotEmpty();
                softly.assertThat(links.get("no")).isNotEmpty();
                softly.assertThat(links.get("maybe")).isNotEmpty();
            });
        }
    }

    @Test
    void shouldReturnNewJwtLinksOnEachRequest(TwakeCalendarGuiceServer server) throws InterruptedException {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid);

        Participation participation = getParticipation(eventUid, ParticipantAction.TENTATIVE);
        URL participationTokenUrl = getParticipationTokenUrl(participation, server);

        Set<String> jwtYesSet = new HashSet<>();
        Set<String> jwtNoSet = new HashSet<>();
        Set<String> jwtMaybeSet = new HashSet<>();

        for (int i = 0; i < 3; i++) {
            Map<String, String> links = RestAssured
                .given()
                .when()
                .get(participationTokenUrl)
                .then()
                .statusCode(200)
                .contentType(JSON)
                .extract()
                .jsonPath()
                .getMap("links");

            Thread.sleep(500);
            jwtYesSet.add(extractJwtFromUrl(links.get("yes")));
            jwtNoSet.add(extractJwtFromUrl(links.get("no")));
            jwtMaybeSet.add(extractJwtFromUrl(links.get("maybe")));
        }

        assertSoftly(softly -> {
            softly.assertThat(jwtYesSet).hasSize(3);
            softly.assertThat(jwtNoSet).hasSize(3);
            softly.assertThat(jwtMaybeSet).hasSize(3);
        });
    }

    @Test
    void shouldAcceptJwtFromLinksAsValidParticipationTokenUrl(TwakeCalendarGuiceServer server) {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid);

        Participation participation = getParticipation(eventUid, ParticipantAction.ACCEPTED);
        URL participationTokenUrl = getParticipationTokenUrl(participation, server);

        Map<String, String> links = RestAssured
            .given()
            .when()
            .get(participationTokenUrl)
            .then()
            .statusCode(200)
            .contentType(JSON)
            .extract()
            .jsonPath()
            .getMap("links");

        String jwt = extractJwtFromUrl(links.get("maybe"));

        String reusedUrl = String.format("http://localhost:%d/calendar/api/calendars/event/participation?jwt=%s",
            restApiPort, URLEncoder.encode(jwt, StandardCharsets.UTF_8));

        RestAssured
            .given()
            .when()
            .get(reusedUrl)
            .then()
            .statusCode(200)
            .contentType(JSON)
            .body("links.maybe", notNullValue())
            .body("links.yes", notNullValue())
            .body("links.no", notNullValue());
    }

    @ParameterizedTest(name = "{index} - {0}")
    @MethodSource("invalidJwtProvider")
    void shouldReturn401ForInvalidOrMissingJwt(String description, String url) {
        String response = RestAssured
            .given()
            .when()
            .get(url.replace("{port}", restApiPort + ""))
            .then()
            .statusCode(401)
            .contentType(ContentType.JSON)
            .extract()
            .body().asString();

        assertThatJson(response).isEqualTo("""
                {
                    "error": {
                        "code": 401,
                        "message": "Unauthorized",
                        "details": "JWT is missing or invalid"
                    }
                }
            """);
    }

    @Test
    void shouldReturn404WhenEventNotFound(TwakeCalendarGuiceServer server) {
        String eventUid = UUID.randomUUID().toString();

        Participation participation = getParticipation(eventUid, ParticipantAction.TENTATIVE);
        URL participationTokenUrl = getParticipationTokenUrl(participation, server);

        String response = RestAssured
            .given()
            .when()
            .get(participationTokenUrl)
            .then()
            .statusCode(404)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response).isEqualTo("""
            {
                "error": {
                    "code": 404,
                    "message": "Not found",
                    "details": "${json-unit.ignore}"
                }
            }
            """);
    }

    static Stream<Arguments> invalidJwtProvider() {
        String port = "{port}";
        return Stream.of(
            Arguments.of("Missing JWT", String.format("http://localhost:%s/calendar/api/calendars/event/participation", port)),
            Arguments.of("Empty JWT", String.format("http://localhost:%s/calendar/api/calendars/event/participation?jwt=", port)),
            Arguments.of("Malformed JWT", String.format("http://localhost:%s/calendar/api/calendars/event/participation?jwt=abc.def.ghi", port)),
            Arguments.of("Garbage string", String.format("http://localhost:%s/calendar/api/calendars/event/participation?jwt=this-is-not-a-jwt", port)));
    }

    private String extractJwtFromUrl(String url) {
        return URLEncodedUtils.parse(StringUtils.substringAfter(url, "?"), StandardCharsets.UTF_8)
            .stream()
            .filter(nameValuePair -> "jwt".equals(nameValuePair.getName()))
            .findFirst()
            .map(NameValuePair::getValue)
            .orElseThrow(() -> new IllegalArgumentException("JWT parameter not found in URL: " + url));
    }

    private void setUserLanguage(Locale locale) {
        given(attendeeRequestSpecification)
            .body("""
                [
                  {
                    "name": "core",
                    "configurations": [
                      {
                        "name": "language",
                        "value": "%s"
                      }
                    ]
                  }
                ]
                """.formatted(locale.getLanguage()))
            .when()
            .put("/api/configurations?scope=user")
            .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    private URL getParticipationTokenUrl(String token) {
        return Throwing.supplier(() -> URI.create(String.format("http://localhost:%d/calendar/api/calendars/event/participation?jwt=%s",
            restApiPort,
            URLEncoder.encode(token, StandardCharsets.UTF_8))).toURL()).get();
    }

    private URL getParticipationTokenUrl(Participation participation, TwakeCalendarGuiceServer server) {
        ParticipationTokenProbe participationTokenProbe = server.getProbe(ParticipationTokenProbe.class);
        String token = participationTokenProbe.signAsJwt(participation);

        return getParticipationTokenUrl(token);
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String startDateTime = LocalDateTime.now().plusDays(3).format(dateTimeFormatter);
        String endDateTime = LocalDateTime.now().plusDays(3).plusHours(1).format(dateTimeFormatter);

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:{dtStamp}Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{startDateTime}
            DTEND;TZID=Asia/Ho_Chi_Minh:{endDateTime}
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{startDateTime}", startDateTime)
            .replace("{endDateTime}", endDateTime)
            .replace("{dtStamp}", LocalDateTime.now().format(dateTimeFormatter))
            .replace("{partStat}", PartStat.NEEDS_ACTION.getValue());
    }

    private void upsertCalendarForTest(String eventUid) {
        davTestHelper.upsertCalendar(
            organizer,
            generateCalendarData(eventUid, organizer.username().asString(), attendee.username().asString()),
            eventUid);

        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(davTestHelper.findFirstEventId(attendee))
                .withFailMessage("Event not created for user: " + attendee.username())
                .isPresent());
    }

    private Participation getParticipation(String eventUid, ParticipantAction action) {
        return Throwing.supplier(() -> new Participation(
            organizer.username().asMailAddress(),
            attendee.username().asMailAddress(),
            eventUid,
            attendee.id().value(),
            action)).get();
    }

    private String getCalendarEventReportResponse(String eventUid) throws Exception {
        CalDavClient calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
        CalendarEventReportResponse response = calDavClient.calendarReportByUid(
            attendee.username(),
            attendee.id(), eventUid).block();

        assertThat(response).isNotNull();
        return response.value().toPrettyString();
    }
}
