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
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;

class DownloadCalendarRouteTest {

    private static final String PASSWORD = "secret";
    private static final String SECRET_LINK_BASE_URL = "https://mocked.url/xyz";

    private static final RestApiConfiguration initialRestApiConfiguration = RestApiConfiguration.builder()
        .enableBasicAuth(Optional.of(true))
        .adminPassword(Optional.of("secret"))
        .build();
    private static final RestApiConfiguration spyRestApiConfiguration = Mockito.spy(initialRestApiConfiguration);

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
        binder -> {
            Mockito.doReturn(Throwing.supplier(() -> URI.create(SECRET_LINK_BASE_URL).toURL()).get())
                .when(spyRestApiConfiguration).getSelfUrl();
            binder.bind(RestApiConfiguration.class).toInstance(spyRestApiConfiguration);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;
    private OpenPaaSUser openPaaSUser2;

    private int restApiPort;
    private DavTestHelper davTestHelper;
    private CalDavClient calDavClient;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        this.openPaaSUser = sabreDavExtension.newTestUser(Optional.of("bob"));
        this.openPaaSUser2 = sabreDavExtension.newTestUser(Optional.of("alice"));

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);
        calendarDataProbe.addUserToRepository(openPaaSUser2.username(), PASSWORD);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(openPaaSUser.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @Test
    void downloadShouldSucceedWhenValidToken() {
        String secretLinkUrl = getSecretLink();

        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(200)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(response.getHeader("Content-Type")).isEqualTo("text/calendar; charset=utf-8");
            softly.assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=calendar.ics");
            softly.assertThat(body).startsWith("BEGIN:VCALENDAR");
            softly.assertThat(body).endsWith("END:VCALENDAR");
        }));
    }

    @Test
    void downloadShouldFailWhenInvalidToken() {
        String invalidSecretLinkUrl = String.format("http://localhost:%d/api/calendars/%s/%s/calendar.ics?token=invalid-token",
            restApiPort,
            openPaaSUser.id().value(),
            openPaaSUser.id().value());

        String response = RestAssured
            .given()
        .when()
            .get(invalidSecretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
            {
                "error": {
                    "code": 403,
                    "message": "Forbidden",
                    "details": "Token validation failed"
                }
            }
            """);
    }

    @Test
    void downloadShouldFailWhenMissingToken() {
        String urlMissingToken = String.format("http://localhost:%d/api/calendars/%s/%s/calendar.ics",
            restApiPort,
            openPaaSUser.id().value(),
            openPaaSUser.id().value());

        String response = RestAssured
            .given()
        .when()
            .get(urlMissingToken)
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
        {
            "error": {
                "code": 403,
                "message": "Forbidden",
                "details": "Token validation failed"
            }
        }
        """);
    }

    @Test
    void downloadShouldFailWhenTokenContainsSpecialCharacters() {
        String specialToken = "abc$#@!%^&*()";
        String urlWithSpecialToken = String.format(
            "http://localhost:%d/api/calendars/%s/%s/calendar.ics?token=%s",
            restApiPort,
            openPaaSUser.id().value(),
            openPaaSUser.id().value(),
            URLEncoder.encode(specialToken, StandardCharsets.UTF_8)
        );

        String response = RestAssured
            .given()
        .when()
            .get(urlWithSpecialToken)
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
        {
            "error": {
                "code": 400,
                "message": "Bad Request",
                "details": "Invalid token: only letters, digits, hyphen, and underscore are allowed."
            }
        }
        """);
    }

    @Test
    void downloadShouldFailWhenTokenDoesNotMatchCalendarId(TwakeCalendarGuiceServer server) {
        String validTokenUrlForCalendarA = getSecretLink();

        String token = new QueryStringDecoder(URI.create(validTokenUrlForCalendarA)).parameters().getOrDefault("token", List.of())
            .stream()
            .findAny().get();

        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        server.getProbe(CalendarDataProbe.class).addUserToRepository(otherUser.username(), "dummy");

        String otherCalendarId = otherUser.id().value();

        String invalidUrl = String.format(
            "http://localhost:%d/api/calendars/%s/%s/calendar.ics?token=%s",
            restApiPort,
            otherCalendarId,
            otherCalendarId,
            token);

        String response = RestAssured
            .given()
        .when()
            .get(invalidUrl)
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
        {
            "error": {
                "code": 403,
                "message": "Forbidden",
                "details": "Token validation failed"
            }
        }
        """);
    }

    @Test
    void downloadShouldFailWhenDavServerFails(TwakeCalendarGuiceServer server) {
        // New user setup does not correctly on the DAV server.
        Username newUser = Username.fromLocalPartWithDomain(UUID.randomUUID().toString(), openPaaSUser.username().getDomainPart().get());

        OpenPaaSId openPaaSId = server.getProbe(CalendarDataProbe.class).addUser(newUser, PASSWORD);

        String secretLink = getSecretLink(newUser, CalendarURL.from(openPaaSId));

        String response = RestAssured
            .given()
        .when()
            .get(secretLink)
        .then()
            .statusCode(HttpStatus.SC_SERVICE_UNAVAILABLE)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 503,
                        "message": "Service Unavailable",
                        "details": "Service Unavailable"
                    }
                }""");
    }

    @Test
    void downloadDefaultCalendarShouldReturnStoredEvent() {
        // GIVEN
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T090000Z
            DTSTART:20251008T100000Z
            DTEND:20251008T110000Z
            SUMMARY:Integration test event
            DESCRIPTION:This event should appear in downloaded ICS
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI eventURI = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value() + "/" + eventUid + ".ics");
        davTestHelper.upsertCalendar(openPaaSUser.username(), eventURI, ics).block();

        String secretLinkUrl = getSecretLink();

        // WHEN
        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        // THEN
        assertSoftly(softly -> {
            softly.assertThat(response.getHeader("Content-Type")).isEqualTo("text/calendar; charset=utf-8");
            softly.assertThat(body).contains("BEGIN:VCALENDAR");
            softly.assertThat(body).contains("UID:" + eventUid);
            softly.assertThat(body).contains("SUMMARY:Integration test event");
        });
    }

    @Test
    void downloadCustomCalendarShouldReturnStoredEvent() {
        // GIVEN
        String customCalendarId = "custom-" + UUID.randomUUID();
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            customCalendarId,
            "Custom Calendar",
            "#00AACC",
            "Calendar created for integration test");

        // Create a custom calendar
        calDavClient.createNewCalendar(openPaaSUser.username(), openPaaSUser.id(), newCalendar).block();

        // Insert an event into that custom calendar
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T090000Z
            DTSTART:20251008T100000Z
            DTEND:20251008T110000Z
            SUMMARY:Custom calendar event
            DESCRIPTION:This event should appear in downloaded ICS from custom calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI eventURI = URI.create("/calendars/" + openPaaSUser.id().value() + "/" + customCalendarId + "/" + eventUid + ".ics");
        davTestHelper.upsertCalendar(openPaaSUser.username(), eventURI, ics).block();

        // Generate the secret link for the custom calendar
        String secretLinkUrl = getSecretLink(openPaaSUser.username(),
            new CalendarURL(openPaaSUser.id(), new OpenPaaSId(customCalendarId)));

        // WHEN
        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        // THEN
        assertSoftly(softly -> {
            softly.assertThat(response.getHeader("Content-Type")).isEqualTo("text/calendar; charset=utf-8");
            softly.assertThat(body).contains("BEGIN:VCALENDAR");
            softly.assertThat(body).contains("UID:" + eventUid);
            softly.assertThat(body).contains("SUMMARY:Custom calendar event");
            softly.assertThat(body).contains("END:VCALENDAR");
        });
    }

    @Test
    void downloadDelegatedCalendarShouldReturnStoredEvent() {
        // GIVEN
        OpenPaaSUser bob = openPaaSUser;     // Bob
        OpenPaaSUser alice = openPaaSUser2; // Alice

        // Create an event in Bob's default calendar
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T090000Z
            DTSTART:20251008T100000Z
            DTEND:20251008T110000Z
            SUMMARY:Delegated calendar event
            DESCRIPTION:This event should appear when alice downloads shared calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI eventURI = URI.create("/calendars/" + bob.id().value() + "/" + bob.id().value() + "/" + eventUid + ".ics");
        davTestHelper.upsertCalendar(bob.username(), eventURI, ics).block();

        // Delegate Bob's calendar to Alice with read right
        davTestHelper.grantDelegation(bob, CalendarURL.from(bob.id()), alice, "dav:read");

        // Find delegated calendar (where baseId != calendarId)
        CalendarURL aliceDelegatedCalendar = findFirstDelegatedCalendarURL(alice);

        // Generate a secret link for Alice’s delegated calendar
        String secretLinkUrl = getSecretLink(alice.username(), aliceDelegatedCalendar);

        // WHEN
        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        // THEN
        assertSoftly(softly -> {
            softly.assertThat(body).contains("BEGIN:VCALENDAR");
            softly.assertThat(body).contains("UID:" + eventUid);
            softly.assertThat(body).contains("SUMMARY:Delegated calendar event");
            softly.assertThat(body).contains("END:VCALENDAR");
        });
    }

    @Test
    void downloadDelegatedCalendarShouldFailAfterRevoked() {
        // GIVEN
        OpenPaaSUser bob = openPaaSUser;     // Owner
        OpenPaaSUser alice = openPaaSUser2; // Delegate

        CalendarURL bobDefaultCalendar = new CalendarURL(bob.id(), bob.id());

        // Bob grants delegation to Alice (read-only)
        davTestHelper.grantDelegation(bob, bobDefaultCalendar, alice, "dav:read");

        // Alice lists her calendars and identifies the delegated one
        CalendarURL aliceDelegatedCalendar = findFirstDelegatedCalendarURL(alice);

        // Step 4: Generate a secret link for Alice’s delegated calendar
        String secretLinkUrl = getSecretLink(alice.username(), aliceDelegatedCalendar);

        // Step 5: Verify Alice can initially download the delegated calendar successfully
        Supplier<ValidatableResponse> downloadBySecretLinkSupplier = () -> RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then();

        downloadBySecretLinkSupplier.get()
            .statusCode(HttpStatus.SC_OK);

        // Step 6: Bob revokes delegation from Alice
        davTestHelper.revokeDelegation(bob, bobDefaultCalendar, alice);

        // Step 7: Alice attempts to download again after revocation
        String response = downloadBySecretLinkSupplier.get()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not Found",
                        "details": "${json-unit.ignore}"
                    }
                }""");
    }

    @Test
    void downloadDelegatedCustomCalendarShouldReturnStoredEvent() {
        // GIVEN
        OpenPaaSUser bob = openPaaSUser;     // Owner
        OpenPaaSUser alice = openPaaSUser2; // Delegate

        // Bob creates a custom calendar
        String customCalendarId = "custom-" + UUID.randomUUID();
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            customCalendarId,
            "Custom Delegated Calendar",
            "#3366FF",
            "Custom calendar for delegation test");

        calDavClient.createNewCalendar(bob.username(), bob.id(), newCalendar).block();

        // Bob creates an event in that custom calendar
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T090000Z
            DTSTART:20251008T100000Z
            DTEND:20251008T110000Z
            SUMMARY:Delegated custom calendar event
            DESCRIPTION:This event should appear when Alice downloads the delegated custom calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI eventURI = URI.create("/calendars/" + bob.id().value() + "/" + customCalendarId + "/" + eventUid + ".ics");
        davTestHelper.upsertCalendar(bob.username(), eventURI, ics).block();

        // Bob grants delegation to Alice (read-only)
        CalendarURL bobCustomCalendar = new CalendarURL(bob.id(), new OpenPaaSId(customCalendarId));
        davTestHelper.grantDelegation(bob, bobCustomCalendar, alice, "dav:read");

        // Alice lists her calendars and identifies the delegated one (baseId != calendarId)
        CalendarURL aliceDelegatedCalendar = findFirstDelegatedCalendarURL(alice);

        // Generate a secret link for Alice’s delegated custom calendar
        String secretLinkUrl = getSecretLink(alice.username(), aliceDelegatedCalendar);

        // WHEN
        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        // THEN
        assertSoftly(softly -> {
            softly.assertThat(body).contains("BEGIN:VCALENDAR");
            softly.assertThat(body).contains("UID:" + eventUid);
            softly.assertThat(body).contains("SUMMARY:Delegated custom calendar event");
        });
    }

    @Test
    void downloadSubscribedCalendarShouldReturnStoredEvent() {
        // GIVEN
        OpenPaaSUser bob = openPaaSUser;     // Owner
        OpenPaaSUser alice = openPaaSUser2; // Subscriber

        // Create an event in Bob's default calendar
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T090000Z
            DTSTART:20251008T100000Z
            DTEND:20251008T110000Z
            SUMMARY:Subscribed calendar event
            DESCRIPTION:This event should appear when Alice downloads the subscribed calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI eventURI = URI.create("/calendars/" + bob.id().value() + "/" + bob.id().value() + "/" + eventUid + ".ics");
        davTestHelper.upsertCalendar(bob.username(), eventURI, ics).block();

        // Bob updates ACL: make calendar publicly readable
        URI bobCalendarUri = URI.create("/calendars/" + bob.id().value() + "/" + bob.id().value() + ".json");
        davTestHelper.updateCalendarAcl(bob, bobCalendarUri, "{DAV:}read");

        // Alice subscribes to Bob's shared calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id().value())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // Generate a secret link for the subscribed calendar
        CalendarURL subscribedCalendar = new CalendarURL(alice.id(), new OpenPaaSId(subscribedCalendarRequest.id()));
        String secretLinkUrl = getSecretLink(alice.username(), subscribedCalendar);

        // WHEN
        Response response = RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        String body = StringUtils.trim(response.getBody().asString());

        // THEN
        assertSoftly(softly -> {
            softly.assertThat(body).contains("BEGIN:VCALENDAR");
            softly.assertThat(body).contains("UID:" + eventUid);
            softly.assertThat(body).contains("SUMMARY:Subscribed calendar event");
            softly.assertThat(body).contains("END:VCALENDAR");
        });
    }

    @Test
    void downloadSubscribedCalendarShouldFailAfterOwnerHidesCalendar() {
        // GIVEN
        OpenPaaSUser bob = openPaaSUser;     // Owner
        OpenPaaSUser alice = openPaaSUser2; // Subscriber

        // Bob sets calendar ACL to readable so Alice can subscribe
        URI bobCalendarUri = URI.create("/calendars/" + bob.id().value() + "/" + bob.id().value() + ".json");
        davTestHelper.updateCalendarAcl(bob, bobCalendarUri, "{DAV:}read");

        // Alice subscribes to Bob's calendar successfully
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id().value())
            .name("Bob readonly shared")
            .color("#FF8800")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // Step 4: Generate secret link for Alice’s subscribed calendar
        CalendarURL subscribedCalendar = new CalendarURL(alice.id(), new OpenPaaSId(subscribedCalendarRequest.id()));
        String secretLinkUrl = getSecretLink(alice.username(), subscribedCalendar);

        // Sanity check: download initially succeeds (before ACL change)
        Supplier<ValidatableResponse> downloadBySecretLinkSupplier = () -> RestAssured
            .given()
        .when()
            .get(secretLinkUrl)
        .then();

        downloadBySecretLinkSupplier.get().statusCode(HttpStatus.SC_OK);

        // Step 5: Bob hides his calendar (no longer public)
        davTestHelper.updateCalendarAcl(bob, bobCalendarUri, "");

        // WHEN – Alice tries to download again after ACL revoked
        String response = downloadBySecretLinkSupplier.get()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not Found",
                        "details": "${json-unit.ignore}"
                    }
                }""");
    }

    private String getSecretLink() {
        return getSecretLink(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()));
    }

    private String getSecretLink(Username username, CalendarURL calendarURL) {
        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(username.asString());
        basicAuthScheme.setPassword(DownloadCalendarRouteTest.PASSWORD);

        String secretLink = given(new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build())
            .get(String.format("/calendar/api/calendars/%s/secret-link", calendarURL.serialize()))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        return Strings.CS.replace(secretLink, SECRET_LINK_BASE_URL, "http://localhost:" + restApiPort);
    }

    private CalendarURL findFirstDelegatedCalendarURL(OpenPaaSUser openPaaSUser) {
        return calDavClient.findUserCalendars(openPaaSUser.username(), openPaaSUser.id())
            .filter(url -> !url.base().equals(url.calendarId()))
            .next()
            .blockOptional()
            .orElseThrow(() -> new AssertionError("No delegated custom calendar found"));
    }

}
