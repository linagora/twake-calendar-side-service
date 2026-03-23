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
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.BookingLinkProbe;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkCreateRouteTest {

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
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding()
                .to(BookingLinkProbe.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private BookingLinkProbe bookingLinkProbe;
    private OpenPaaSUser openPaaSUser;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        openPaaSUser = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);

        bookingLinkProbe = server.getProbe(BookingLinkProbe.class);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(openPaaSUser.username().asString());
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkProbe.findBookingLink(openPaaSUser.username(), new BookingLinkPublicId(UUID.fromString(publicId)));

        ZoneId europeParis = ZoneId.of("Europe/Paris");
        LocalTime businessStart = LocalTime.of(8, 0);
        LocalTime businessEnd = LocalTime.of(19, 0);

        assertThat(stored.username()).isEqualTo(openPaaSUser.username());
        assertThat(stored.calendarUrl()).isEqualTo(CalendarURL.from(openPaaSUser.id()));
        assertThat(stored.duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(stored.active()).isTrue();

        // Default availability rules should be set when not provided, based on the default business hours and timezone
        assertThat(stored.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, businessStart, businessEnd, europeParis),
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, businessStart, businessEnd, europeParis),
            new WeeklyAvailabilityRule(DayOfWeek.WEDNESDAY, businessStart, businessEnd, europeParis),
            new WeeklyAvailabilityRule(DayOfWeek.THURSDAY, businessStart, businessEnd, europeParis),
            new WeeklyAvailabilityRule(DayOfWeek.FRIDAY, businessStart, businessEnd, europeParis)
        )));
    }

    @Test
    void shouldPersistBookingLinkWithWeeklyAvailabilityRules() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "weekly", "timeZone": "Asia/Ho_Chi_Minh" },
                        { "dayOfWeek": "MON", "start": "13:00", "end": "17:00", "type": "weekly", "timeZone": "Europe/London" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkProbe.findBookingLink(openPaaSUser.username(), new BookingLinkPublicId(UUID.fromString(publicId)));

        assertThat(stored.username()).isEqualTo(openPaaSUser.username());
        assertThat(stored.calendarUrl()).isEqualTo(CalendarURL.from(openPaaSUser.id()));
        assertThat(stored.duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(stored.active()).isTrue();
        assertThat(stored.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), ZoneId.of("Asia/Ho_Chi_Minh")),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0), ZoneId.of("Europe/London"))
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
                    "availabilityRules": [
                        { "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "type": "fixed", "timeZone": "UTC" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkProbe.findBookingLink(openPaaSUser.username(), new BookingLinkPublicId(UUID.fromString(publicId)));

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
                        { "dayOfWeek": "TUE", "start": "09:00", "end": "17:00", "type": "weekly", "timeZone": "Asia/Ho_Chi_Minh" },
                        { "start": "2026-01-26T00:00:00", "end": "2026-01-30T00:00:00", "type": "fixed", "timeZone": "Europe/London" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkProbe.findBookingLink(openPaaSUser.username(), new BookingLinkPublicId(UUID.fromString(publicId)));

        assertThat(stored.availabilityRules().orElseThrow().values()).hasSize(2);
        assertThat(stored.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), ZoneId.of("Asia/Ho_Chi_Minh")),
            new FixedAvailabilityRule(LocalDateTime.parse("2026-01-26T00:00:00").atZone(ZoneId.of("Europe/London")), LocalDateTime.parse("2026-01-30T00:00:00").atZone(ZoneId.of("Europe/London")))
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
            """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString());

        String firstPublicId = given().body(body).when().post("/api/booking-links")
            .then().statusCode(HttpStatus.SC_CREATED).extract().jsonPath().getString("bookingLinkPublicId");

        String secondPublicId = given().body(body).when().post("/api/booking-links")
            .then().statusCode(HttpStatus.SC_CREATED).extract().jsonPath().getString("bookingLinkPublicId");

        assertThat(firstPublicId).isNotEqualTo(secondPublicId);
        assertThat(bookingLinkProbe.listBookingLinks(openPaaSUser.username())).hasSize(2);
    }

    @Test
    void shouldUseTimezoneFromSettingWhenNotProvided() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "17:00", "type": "weekly" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
            .when()
            .post("/api/booking-links")
            .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkProbe.findBookingLink(openPaaSUser.username(), new BookingLinkPublicId(UUID.fromString(publicId)));

        assertThat(stored.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), ZoneId.of("Europe/Paris"))
        )));
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
            .post("/api/booking-links")
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
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
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
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "weekly", "timeZone": "Invalid/Timezone" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
            .when()
            .post("/api/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid 'timeZone' format: Invalid/Timezone"));
    }

    @Test
    void shouldReturn400WhenBodyIsEmpty() {
        given()
            .body("")
        .when()
            .post("/api/booking-links")
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
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
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
                        { "dayOfWeek": "ABC", "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
            .when()
            .post("/api/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Unknown day of week abbreviation: ABC"));
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
                        { "dayOfWeek": "MON", "start": "34545", "end": "12:00", "type": "weekly" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
            .when()
            .post("/api/booking-links")
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
            .when()
            .post("/api/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm:ss"));
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
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "unknown" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Unknown availability rule type: unknown"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRuleTypeIsMissing() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00" }
                    ]
                }
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
            .when()
            .post("/api/booking-links")
            .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("'type' is required in availability rule"));
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
                """.formatted(CalendarURL.from(openPaaSUser.id()).asUri().toString()))
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldReturn400WhenCalendarDoesNotExist() {
        given()
            .body("""
                {
                    "calendarUrl": "/calendars/nonexistentBase/nonexistentCalendar",
                    "durationMinutes": 30,
                    "active": true
                }
                """)
        .when()
            .post("/api/booking-links")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Calendar not found or access denied: /calendars/nonexistentBase/nonexistentCalendar"));
    }
}
