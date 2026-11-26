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
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import net.javacrumbs.jsonunit.core.Option;

class EventTokenRoutesTest {

    private static final String PASSWORD = "secret";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser owner;
    private OpenPaaSUser otherUser;
    private DavTestHelper davTestHelper;
    private RequestSpecification ownerSpecification;
    private RequestSpecification otherUserSpecification;
    private RequestSpecification anonymousSpecification;
    private String eventId;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        owner = sabreDavExtension.newTestUser();
        otherUser = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addUserToRepository(owner.username(), PASSWORD);
        calendarDataProbe.addUserToRepository(otherUser.username(), PASSWORD);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        int port = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        ownerSpecification = requestSpecification(port, owner);
        otherUserSpecification = requestSpecification(port, otherUser);
        anonymousSpecification = baseSpecification(port).build();

        eventId = "sabredav-" + UUID.randomUUID();
        davTestHelper.upsertCalendar(owner, eventIcs(eventId, "CONFIRMED"), eventId);
    }

    private static RequestSpecification requestSpecification(int port, OpenPaaSUser user) {
        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(user.username().asString());
        auth.setPassword(PASSWORD);
        return baseSpecification(port).setAuth(auth).build();
    }

    private static RequestSpecBuilder baseSpecification(int port) {
        return new RequestSpecBuilder()
            .setPort(port)
            .setBasePath("")
            .setAccept(JSON)
            .setContentType(JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)));
    }

    private String eventIcs(String uid, String status) {
        return eventIcs(uid, status, "ORGANIZER;CN=Owner:mailto:" + owner.username().asString() + "\n");
    }

    private String eventIcs(String uid, String status, String organizerAndAttendees) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260101T000000Z
            DTSTART:20361201T100000Z
            DTEND:20361201T110000Z
            SUMMARY:Team sync
            STATUS:%s
            %sEND:VEVENT
            END:VCALENDAR
            """.formatted(uid, status, organizerAndAttendees);
    }

    private String eventTokensPath(OpenPaaSUser calendarOwner, String eventId) {
        return "/api/events/%s/%s/%s/eventTokens".formatted(calendarOwner.id().value(), calendarOwner.id().value(), eventId);
    }

    private String createToken(String body) {
        return given(ownerSpecification)
            .body(body)
        .when()
            .post(eventTokensPath(owner, eventId))
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract()
            .jsonPath()
            .getString("eventToken");
    }

    private String createViewToken() {
        return createToken("""
            { "right": "VIEW" }
            """);
    }

    @Nested
    class CreateToken {

        @Test
        void shouldReturnTokenValidForThirtyDaysByDefault() {
            // Given the owner asks for a token without specifying its expiration
            Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            // When the token is created
            String response = given(ownerSpecification)
                .body("""
                    { "right": "VIEW" }
                    """)
            .when()
                .post(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_CREATED)
                .contentType(JSON)
                .extract()
                .body()
                .asString();

            // Then a token is returned, expiring thirty days after its creation
            assertThatJson(response).isEqualTo("""
                {
                  "eventToken": "${json-unit.any-string}",
                  "expiration": "${json-unit.any-string}"
                }
                """);
            Instant expiration = Instant.parse(JsonPath.from(response).getString("expiration"));
            assertThat(expiration).isBetween(before.plus(Duration.ofDays(30)), Instant.now().plus(Duration.ofDays(30)));
        }

        @Test
        void shouldApplyRequestedExpirationInDays() {
            // Given the owner asks for a token valid for seven days
            Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            // When the token is created
            String expiration = given(ownerSpecification)
                .body("""
                    { "right": "ADD_MYSELF", "expirationInDays": 7 }
                    """)
            .when()
                .post(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_CREATED)
                .extract()
                .jsonPath()
                .getString("expiration");

            // Then it expires seven days after its creation
            assertThat(Instant.parse(expiration)).isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)));
        }

        @Test
        void shouldReturn403WhenUserIsNotTheCalendarHomeOwner() {
            // Given another user knows the path of the owner's event

            // When that user asks for a token of the event
            // Then the request is forbidden
            given(otherUserSpecification)
                .body("""
                    { "right": "VIEW" }
                    """)
            .when()
                .post(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_FORBIDDEN);
        }

        @Test
        void shouldReturn404WhenEventDoesNotExist() {
            // Given no event is stored under the requested event id

            // When the owner asks for a token of it
            // Then the event is reported as not found
            given(ownerSpecification)
                .body("""
                    { "right": "VIEW" }
                    """)
            .when()
                .post(eventTokensPath(owner, "unknown-event"))
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }

        @ParameterizedTest
        @ValueSource(strings = {"VIEW", "ADD_MYSELF"})
        void shouldReturn403WhenMeetingIsOrganizedBySomeoneElse(String right) {
            // Given a meeting organized by another user, of which the owner holds an attendee copy
            String attendeeCopyId = "sabredav-" + UUID.randomUUID();
            davTestHelper.upsertCalendar(owner, eventIcs(attendeeCopyId, "CONFIRMED", """
                ORGANIZER;CN=Other:mailto:%s
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:%s
                """.formatted(otherUser.username().asString(), owner.username().asString())), attendeeCopyId);

            // When the owner asks for a token of it
            // Then the request is forbidden
            given(ownerSpecification)
                .body("""
                    { "right": "%s" }
                    """.formatted(right))
            .when()
                .post(eventTokensPath(owner, attendeeCopyId))
            .then()
                .statusCode(HttpStatus.SC_FORBIDDEN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"VIEW", "ADD_MYSELF"})
        void shouldReturn403WhenEventHasNoOrganizer(String right) {
            // Given a personal event without organizer
            String personalEventId = "sabredav-" + UUID.randomUUID();
            davTestHelper.upsertCalendar(owner, eventIcs(personalEventId, "CONFIRMED", ""), personalEventId);

            // When the owner asks for a token of it
            // Then the request is forbidden
            given(ownerSpecification)
                .body("""
                    { "right": "%s" }
                    """.formatted(right))
            .when()
                .post(eventTokensPath(owner, personalEventId))
            .then()
                .statusCode(HttpStatus.SC_FORBIDDEN);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{}",
            "{ \"right\": \"UNKNOWN\" }",
            "{ \"right\": \"VIEW\", \"expirationInDays\": 0 }",
            "{ \"right\": \"VIEW\", \"expirationInDays\": -1 }",
            "{ \"right\": \"VIEW\", \"expirationInDays\": \"seven\" }",
            "not json"
        })
        void shouldReturn400WhenBodyIsInvalid(String body) {
            // Given a body with a missing or invalid field

            // When the owner asks for a token with it
            // Then the request is rejected
            given(ownerSpecification)
                .body(body)
            .when()
                .post(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_BAD_REQUEST);
        }

        @Test
        void shouldReturn401WhenUnauthenticated() {
            // Given an anonymous client

            // When it asks for a token
            // Then authentication is required
            given(anonymousSpecification)
                .body("""
                    { "right": "VIEW" }
                    """)
            .when()
                .post(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_UNAUTHORIZED);
        }
    }

    @Nested
    class ListTokens {

        @Test
        void shouldReturnTokensOfTheEvent() {
            // Given two tokens of the event and one of another event of the owner
            String viewToken = createViewToken();
            String addMyselfToken = createToken("""
                { "right": "ADD_MYSELF", "expirationInDays": 7 }
                """);
            String otherEventId = "sabredav-" + UUID.randomUUID();
            davTestHelper.upsertCalendar(owner, eventIcs(otherEventId, "CONFIRMED"), otherEventId);
            given(ownerSpecification)
                .body("""
                    { "right": "VIEW" }
                    """)
                .post(eventTokensPath(owner, otherEventId))
            .then()
                .statusCode(HttpStatus.SC_CREATED);

            // When the owner lists the tokens of the event
            String response = given(ownerSpecification)
            .when()
                .get(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_OK)
                .contentType(JSON)
                .extract()
                .body()
                .asString();

            // Then only the two tokens of that event are returned with their right and expiration
            assertThatJson(response)
                .withOptions(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo("""
                    [
                      { "eventToken": "%s", "right": "VIEW", "expiration": "${json-unit.any-string}" },
                      { "eventToken": "%s", "right": "ADD_MYSELF", "expiration": "${json-unit.any-string}" }
                    ]
                    """.formatted(viewToken, addMyselfToken));
        }

        @Test
        void shouldReturnEmptyListWhenEventHasNoToken() {
            // Given the event has no token

            // When the owner lists its tokens
            String response = given(ownerSpecification)
            .when()
                .get(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_OK)
                .extract()
                .body()
                .asString();

            // Then an empty list is returned
            assertThatJson(response).isEqualTo("[]");
        }

        @Test
        void shouldReturn403WhenUserIsNotTheCalendarHomeOwner() {
            // Given the event has a token
            createViewToken();

            // When another user lists the tokens of the event
            // Then the request is forbidden
            given(otherUserSpecification)
            .when()
                .get(eventTokensPath(owner, eventId))
            .then()
                .statusCode(HttpStatus.SC_FORBIDDEN);
        }
    }

    @Nested
    class GetTokenRight {

        @Test
        void shouldReturnOnlyTheRightOfTheToken() {
            // Given a token granting to add oneself to the event
            String token = createToken("""
                { "right": "ADD_MYSELF" }
                """);

            // When an anonymous client looks the token up
            String response = given(anonymousSpecification)
            .when()
                .get("/api/eventTokens/" + token)
            .then()
                .statusCode(HttpStatus.SC_OK)
                .contentType(JSON)
                .extract()
                .body()
                .asString();

            // Then only the right is disclosed
            assertThatJson(response).isEqualTo("""
                { "right": "ADD_MYSELF" }
                """);
        }

        @Test
        void shouldReturn404WhenTokenIsUnknown() {
            // Given no token was issued with that value

            // When an anonymous client looks it up
            // Then it is reported as not found
            given(anonymousSpecification)
            .when()
                .get("/api/eventTokens/unknown")
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    class DeleteToken {

        @Test
        void shouldRevokeTheToken() {
            // Given a token of the event
            String token = createViewToken();

            // When the owner deletes it
            given(ownerSpecification)
            .when()
                .delete("/api/eventTokens/" + token)
            .then()
                .statusCode(HttpStatus.SC_NO_CONTENT);

            // Then the token no longer gives access to the event
            given(anonymousSpecification)
                .queryParam("eventToken", token)
            .when()
                .get("/api/events")
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }

        @Test
        void shouldReturn403AndKeepTheTokenWhenUserIsNotTheCalendarHomeOwner() {
            // Given a token of the owner's event
            String token = createViewToken();

            // When another user tries to delete it
            given(otherUserSpecification)
            .when()
                .delete("/api/eventTokens/" + token)
            .then()
                .statusCode(HttpStatus.SC_FORBIDDEN);

            // Then the token still exists
            given(anonymousSpecification)
            .when()
                .get("/api/eventTokens/" + token)
            .then()
                .statusCode(HttpStatus.SC_OK);
        }

        @Test
        void shouldReturn404WhenTokenIsUnknown() {
            // Given no token was issued with that value

            // When the owner deletes it
            // Then it is reported as not found
            given(ownerSpecification)
            .when()
                .delete("/api/eventTokens/unknown")
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    class GetEvent {

        @Test
        void shouldReturnTheEventAsJCal() {
            // Given a token of the event
            String token = createViewToken();

            // When an anonymous client fetches the event with it
            String response = given(anonymousSpecification)
                .queryParam("eventToken", token)
            .when()
                .get("/api/events")
            .then()
                .statusCode(HttpStatus.SC_OK)
                .contentType(JSON)
                .extract()
                .body()
                .asString();

            // Then the whole event is returned in jCal form
            assertThatJson(response)
                .withOptions(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo("""
                    {
                      "eventJSON": [
                        "vcalendar",
                        [
                          ["version", {}, "text", "2.0"],
                          ["prodid", {}, "text", "${json-unit.any-string}"],
                          ["calscale", {}, "text", "GREGORIAN"]
                        ],
                        [
                          [
                            "vevent",
                            [
                              ["uid", {}, "text", "%s"],
                              ["dtstamp", {}, "date-time", "2026-01-01T00:00:00Z"],
                              ["dtstart", {}, "date-time", "2036-12-01T10:00:00Z"],
                              ["dtend", {}, "date-time", "2036-12-01T11:00:00Z"],
                              ["summary", {}, "text", "Team sync"],
                              ["status", {}, "text", "CONFIRMED"],
                              ["organizer", {"cn": "Owner"}, "cal-address", "mailto:%s"]
                            ],
                            []
                          ]
                        ]
                      ]
                    }
                    """.formatted(eventId, owner.username().asString()));
        }

        @Test
        void shouldReturn400WhenTokenIsMissing() {
            // Given no token

            // When an anonymous client fetches an event
            // Then the request is rejected
            given(anonymousSpecification)
            .when()
                .get("/api/events")
            .then()
                .statusCode(HttpStatus.SC_BAD_REQUEST);
        }

        @Test
        void shouldReturn404WhenTokenIsUnknown() {
            // Given no token was issued with that value

            // When an anonymous client fetches an event with it
            // Then it is reported as not found
            given(anonymousSpecification)
                .queryParam("eventToken", "unknown")
            .when()
                .get("/api/events")
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }

        @Test
        void shouldReturn404WhenEventWasDeleted() {
            // Given a token of an event the owner deleted afterwards
            String token = createViewToken();
            davTestHelper.deleteCalendar(owner, eventId);

            // When an anonymous client fetches the event with it
            // Then the event is reported as not found
            given(anonymousSpecification)
                .queryParam("eventToken", token)
            .when()
                .get("/api/events")
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }

        @Test
        void shouldReturn404WhenEventWasCancelled() {
            // Given a token of an event the owner cancelled afterwards
            String token = createViewToken();
            davTestHelper.updateCalendar(owner, eventIcs(eventId, "CANCELLED"), eventId);

            // When an anonymous client fetches the event with it
            // Then the event is reported as not found
            given(anonymousSpecification)
                .queryParam("eventToken", token)
            .when()
                .get("/api/events")
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND);
        }
    }
}
