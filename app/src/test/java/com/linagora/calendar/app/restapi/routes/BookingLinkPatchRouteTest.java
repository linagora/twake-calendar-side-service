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
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

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
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkPatchRouteTest {

    private static final boolean ACTIVE = true;
    private static final String PASSWORD = "secret";
    private static final ZoneId ZONE_HO_CHI_MINH = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ZoneId UTC = ZoneId.of("UTC");

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
    private CalDavClient calDavClient;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws SSLException {
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

        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @Test
    void shouldReturn204WhenUpdatingActive() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void shouldPersistUpdatedActive() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.active()).isFalse();
    }

    @Test
    void shouldPersistUpdatedDurationMinutes() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": 60 }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.duration()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void shouldPersistUpdatedCalendarUrl() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        String newCalendarId = "custom-" + UUID.randomUUID();
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            newCalendarId,
            "Custom Calendar",
            "#00AACC",
            "Calendar created for integration test");

        // Create a custom calendar
        calDavClient.createNewCalendar(openPaaSUser.username(), openPaaSUser.id(), newCalendar).block();

        CalendarURL newCalendarUrl = new CalendarURL(openPaaSUser.id(), new OpenPaaSId(newCalendarId));

        given()
            .body("""
                { "calendarUrl": "%s" }
                """.formatted(newCalendarUrl.asUri().toString()))
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.calendarUrl()).isEqualTo(newCalendarUrl);
    }

    @Test
    void shouldPersistUpdatedWeeklyAvailabilityRules() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "weekly", "timeZone": "Asia/Ho_Chi_Minh" },
                        { "dayOfWeek": "MON", "start": "13:00", "end": "17:00", "type": "weekly", "timeZone": "Europe/London" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), ZONE_HO_CHI_MINH),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0), ZoneId.of("Europe/London")))));
    }

    @Test
    void shouldPersistUpdatedFixedAvailabilityRule() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "type": "fixed", "timeZone": "UTC" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        FixedAvailabilityRule rule = (FixedAvailabilityRule) updated.availabilityRules().orElseThrow().values().getFirst();
        assertThat(rule.start().toInstant().toString()).isEqualTo("2026-01-26T02:00:00Z");
        assertThat(rule.end().toInstant().toString()).isEqualTo("2026-01-30T02:00:00Z");
    }

    @Test
    void shouldRemoveAvailabilityRulesWhenSetToNull() {
        AvailabilityRules existingRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), UTC));
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(existingRules)));

        given()
            .body("""
                { "availabilityRules": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.availabilityRules()).isEmpty();
    }

    @Test
    void shouldNotUpdateFieldsAbsentFromRequest() {
        AvailabilityRules existingRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), UTC));
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(existingRules)));

        given()
            .body("""
                { "durationMinutes": 45 }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.duration()).isEqualTo(Duration.ofMinutes(45));
        assertThat(updated.calendarUrl()).isEqualTo(inserted.calendarUrl());
        assertThat(updated.active()).isEqualTo(inserted.active());
        assertThat(updated.availabilityRules()).isEqualTo(inserted.availabilityRules());
    }

    @Test
    void shouldReturn404WhenBookingLinkNotFound() {
        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/api/booking-links/" + UUID.randomUUID())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn404WhenBookingLinkBelongsToAnotherUser() {
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        BookingLink inserted = bookingLinkProbe.insertBookingLink(otherUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(otherUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn400WhenRequestContainsUnknownFields() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false, "unknownField": "value" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid request body"));
    }

    @Test
    void shouldReturn400WhenBodyIsEmpty() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("")
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Request body must not be empty"));
    }

    @Test
    void shouldReturn400WhenBodyIsInvalidJson() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("not-valid-json")
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid request body"));
    }

    @Test
    void shouldReturn400WhenNoFieldIsUpdated() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("{}")
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsZero() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": 0 }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'durationMinutes' must be positive"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsNegative() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": -10 }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'durationMinutes' must be positive"));
    }

    @Test
    void shouldReturn400WhenTimezoneIsInvalid() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "17:00", "type": "weekly", "timeZone": "Invalid/Timezone" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid 'timeZone' format: Invalid/Timezone"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleIsMissingDayOfWeek() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'dayOfWeek' must be provided for weekly rule"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleHasInvalidDayOfWeek() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "dayOfWeek": "ABC", "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Unknown day of week abbreviation: ABC"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleStartTimeIsInvalid() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "invalid", "end": "12:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm"));
    }

    @Test
    void shouldReturn400WhenFixedRuleStartTimeIsInvalid() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "start": "not-a-date", "end": "2026-01-30T00:00:00", "type": "fixed", "timeZone": "UTC" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm:ss"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRuleTypeIsUnknown() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "unknown" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Unknown availability rule type: unknown"));
    }

    @Test
    void shouldReturn401WhenUnauthenticated() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        with()
            .auth().none()
            .contentType(ContentType.JSON)
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldReturn400WhenPublicIdIsNotAValidUUID() {
        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/api/booking-links/not-a-uuid")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenCalendarUrlIsNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "calendarUrl": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'calendarUrl' cannot be removed"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'durationMinutes' cannot be removed"));
    }

    @Test
    void shouldReturn400WhenActiveIsNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'active' cannot be removed"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRulesIsEmptyArray() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": []
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'availabilityRules' cannot be empty if provided"));
    }

    @Test
    void shouldReturn400WhenFixedRuleStartIsAfterEnd() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "start": "2026-01-30T00:00:00", "end": "2026-01-26T00:00:00", "type": "fixed", "timeZone": "UTC" }
                    ]
                }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'start' must be before 'end' for fixed rule"));
    }

    @Test
    void shouldReturn400WhenCalendarUrlBelongsToAnotherUser() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        String otherCalendarId = "other-" + UUID.randomUUID();
        calDavClient.createNewCalendar(otherUser.username(), otherUser.id(),
            new CalDavClient.NewCalendar(otherCalendarId, "Other Calendar", "#FF0000", "")).block();

        CalendarURL otherUserCalendarUrl = new CalendarURL(otherUser.id(), new OpenPaaSId(otherCalendarId));

        given()
            .body("""
                { "calendarUrl": "%s" }
                """.formatted(otherUserCalendarUrl.asUri().toString()))
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Calendar not found or access denied: " + otherUserCalendarUrl.asUri()));
    }

    @Test
    void shouldReturn400WhenCalendarDoesNotExist() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        CalendarURL nonExistentCalendarUrl = new CalendarURL(openPaaSUser.id(), new OpenPaaSId("nonexistentCalendar"));

        given()
            .body("""
                {
                    "calendarUrl": "%s"
                }
                """.formatted(nonExistentCalendarUrl.asUri().toString()))
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Calendar not found or access denied: " + nonExistentCalendarUrl.asUri()));
    }
}
