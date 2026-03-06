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

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkCreateRouteTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);
    private static final String BASE_ID = "659387b9d486dc0046aeff91";
    private static final String CALENDAR_ID = "659387b9d486dc0046aeff92";
    private static final String CALENDAR_URL = "/calendars/" + BASE_ID + "/" + CALENDAR_ID;
    private static final CalendarURL EXPECTED_CALENDAR_URL = new CalendarURL(new OpenPaaSId(BASE_ID), new OpenPaaSId(CALENDAR_ID));

    @RegisterExtension
    @Order(1)
    private static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        AppTestHelper.BY_PASS_MODULE.apply(rabbitMQExtension));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private CalendarDataProbe dataProbe;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        dataProbe = server.getProbe(CalendarDataProbe.class);
        dataProbe.addDomain(Domain.of(DOMAIN));
        dataProbe.addUser(USERNAME, PASSWORD);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(USERNAME.asString());
        basicAuthScheme.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();
    }

    @Test
    void shouldReturn201WithBookingLinkPublicIdWhenMinimalValidRequest() {
        String response = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .contentType(ContentType.JSON)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                { "bookingLinkPublicId": "${json-unit.ignore}" }""");
    }

    @Test
    void shouldPersistBookingLinkWithExpectedFieldsForMinimalRequest() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = dataProbe.findBookingLink(USERNAME, new BookingLinkPublicId(publicId)).orElseThrow();

        assertThat(stored.username()).isEqualTo(USERNAME);
        assertThat(stored.calendarUrl()).isEqualTo(EXPECTED_CALENDAR_URL);
        assertThat(stored.duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(stored.active()).isTrue();
        assertThat(stored.availabilityRules()).isEmpty();
    }

    @Test
    void shouldPersistBookingLinkWithWeeklyAvailabilityRules() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "timeZone": "Asia/Ho_Chi_Minh",
                    "availabilityRules": [
                        { "dayOfWeek": 1, "start": "09:00", "end": "12:00", "type": "weekly" },
                        { "dayOfWeek": 1, "start": "13:00", "end": "17:00", "type": "weekly" }
                    ]
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = dataProbe.findBookingLink(USERNAME, new BookingLinkPublicId(publicId)).orElseThrow();

        assertThat(stored.username()).isEqualTo(USERNAME);
        assertThat(stored.calendarUrl()).isEqualTo(EXPECTED_CALENDAR_URL);
        assertThat(stored.duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(stored.active()).isTrue();
        assertThat(stored.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), ZoneId.of("Asia/Ho_Chi_Minh")),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0), ZoneId.of("Asia/Ho_Chi_Minh"))
        )));
    }

    @Test
    void shouldPersistBookingLinkWithFixedAvailabilityRule() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 60,
                    "active": false,
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "type": "fixed" }
                    ]
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = dataProbe.findBookingLink(USERNAME, new BookingLinkPublicId(publicId)).orElseThrow();

        assertThat(stored.duration()).isEqualTo(Duration.ofMinutes(60));
        assertThat(stored.active()).isFalse();
        FixedAvailabilityRule rule = (FixedAvailabilityRule) stored.availabilityRules().orElseThrow().values().getFirst();
        assertThat(rule.start().toInstant().toString()).isEqualTo("2026-01-26T02:00:00Z");
        assertThat(rule.end().toInstant().toString()).isEqualTo("2026-01-30T02:00:00Z");
    }

    @Test
    void shouldPersistBookingLinkWithMixedAvailabilityRules() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": 2, "start": "09:00", "end": "17:00", "type": "weekly" },
                        { "start": "2026-01-26T00:00:00", "end": "2026-01-30T00:00:00", "type": "fixed" }
                    ]
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = dataProbe.findBookingLink(USERNAME, new BookingLinkPublicId(publicId)).orElseThrow();

        assertThat(stored.availabilityRules().orElseThrow().values()).hasSize(2);
        assertThat(stored.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), ZoneId.of("UTC")),
            new FixedAvailabilityRule(LocalDateTime.parse("2026-01-26T00:00:00").atZone(ZoneId.of("UTC")), LocalDateTime.parse("2026-01-30T00:00:00").atZone(ZoneId.of("UTC")))
        )));
    }

    @Test
    void shouldReturnDistinctPublicIdsForSuccessiveCreations() {
        String body = """
            {
                "calendarUrl": "%s",
                "durationMinutes": 30,
                "active": true
            }
            """.formatted(CALENDAR_URL);

        String firstPublicId = given().body(body).when().post("/booking-links")
            .then().statusCode(HttpStatus.SC_CREATED).extract().jsonPath().getString("bookingLinkPublicId");

        String secondPublicId = given().body(body).when().post("/booking-links")
            .then().statusCode(HttpStatus.SC_CREATED).extract().jsonPath().getString("bookingLinkPublicId");

        assertThat(firstPublicId).isNotEqualTo(secondPublicId);
        assertThat(dataProbe.listBookingLinks(USERNAME)).hasSize(2);
    }

    @Test
    void shouldReturn400WhenCalendarUrlIsMissing() {
        given()
            .body("""
                {
                    "durationMinutes": 30,
                    "active": true
                }
                """)
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'calendarUrl' is required"));
    }

    @Test
    void shouldReturn400WhenCalendarUrlInvalid() {
        given()
            .body("""
                {
                    "calendarUrl": "invalid-url",
                    "durationMinutes": 30,
                    "active": true
                }
                """)
            .when()
            .post("/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid CalendarURL format, expected {base}/{calendar}: invalid-url"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsMissing() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "active": true
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'durationMinutes' is required"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsZero() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 0,
                    "active": true
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'durationMinutes' must be positive"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsNegative() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": -10,
                    "active": true
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'durationMinutes' must be positive"));
    }

    @Test
    void shouldReturn400WhenActiveIsMissing() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'active' is required"));
    }

    @Test
    void shouldReturn400WhenTimezoneIsInvalid() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "timeZone": "Invalid/Timezone"
                }
                """.formatted(CALENDAR_URL))
            .when()
            .post("/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid 'timeZone' format"));
    }

    @Test
    void shouldReturn400WhenBodyIsEmpty() {
        given()
            .body("")
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Request body must not be empty"));
    }

    @Test
    void shouldReturn400WhenBodyIsInvalidJson() {
        given()
            .body("not-valid-json")
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid request body"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleIsMissingDayOfWeek() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'dayOfWeek' must be provided for weekly rule"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleHasInvalidDayOfWeek() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": 10, "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """.formatted(CALENDAR_URL))
            .when()
            .post("/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'dayOfWeek' must be an integer between 1 (Monday) and 7 (Sunday)"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleStartTimeIsInvalid() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": 1, "start": "34545", "end": "12:00", "type": "weekly" }
                    ]
                }
                """.formatted(CALENDAR_URL))
            .when()
            .post("/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm"));
    }

    @Test
    void shouldReturn400WhenFixedRuleStartTimeIsInvalid() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "start": "2026-0100:00", "end": "2026-01-30T00:00:00", "type": "fixed" }
                    ]
                }
                """.formatted(CALENDAR_URL))
            .when()
            .post("/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRuleTypeIsUnknown() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": 1, "start": "09:00", "end": "12:00", "type": "unknown" }
                    ]
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Unknown availability rule type: unknown"));
    }

    @Test
    void shouldReturn401WhenUnauthenticated() {
        with()
            .auth().none()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(CALENDAR_URL))
        .when()
            .post("/booking-links")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }
}
