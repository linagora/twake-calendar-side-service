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
import com.linagora.calendar.app.BookingLinkDataProbe;
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
                .to(BookingLinkDataProbe.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private BookingLinkDataProbe dataProbe;
    private OpenPaaSUser openPaaSUser;
    private CalDavClient calDavClient;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws SSLException {
        openPaaSUser = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);

        dataProbe = server.getProbe(BookingLinkDataProbe.class);

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
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void shouldPersistUpdatedActive() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.active()).isFalse();
    }

    @Test
    void shouldPersistUpdatedDurationMinutes() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": 60 }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.duration()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void shouldPersistUpdatedCalendarUrl() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
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
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.calendarUrl()).isEqualTo(newCalendarUrl);
    }

    @Test
    void shouldPersistUpdatedWeeklyAvailabilityRules() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "Asia/Ho_Chi_Minh",
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "weekly" },
                        { "dayOfWeek": "MON", "start": "13:00", "end": "17:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.availabilityRules()).isEqualTo(Optional.of(AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), ZONE_HO_CHI_MINH),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0), ZONE_HO_CHI_MINH))));
    }

    @Test
    void shouldPersistUpdatedFixedAvailabilityRule() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "type": "fixed" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        FixedAvailabilityRule rule = (FixedAvailabilityRule) updated.availabilityRules().orElseThrow().values().getFirst();
        assertThat(rule.start().toInstant().toString()).isEqualTo("2026-01-26T02:00:00Z");
        assertThat(rule.end().toInstant().toString()).isEqualTo("2026-01-30T02:00:00Z");
    }

    @Test
    void shouldRemoveAvailabilityRulesWhenSetToNull() {
        AvailabilityRules existingRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), UTC));
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(existingRules)));

        given()
            .body("""
                { "availabilityRules": null }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.availabilityRules()).isEmpty();
    }

    @Test
    void shouldNotUpdateFieldsAbsentFromRequest() {
        AvailabilityRules existingRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), UTC));
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(existingRules)));

        given()
            .body("""
                { "durationMinutes": 45 }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = dataProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
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
            .patch("/booking-links/" + UUID.randomUUID())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn404WhenBookingLinkBelongsToAnotherUser() {
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        BookingLink inserted = dataProbe.insertBookingLink(otherUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(otherUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn400WhenRequestContainsUnknownFields() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": false, "unknownField": "value" }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid request body"));
    }

    @Test
    void shouldReturn400WhenBodyIsEmpty() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("")
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Request body must not be empty"));
    }

    @Test
    void shouldReturn400WhenBodyIsInvalidJson() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("not-valid-json")
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid request body"));
    }

    @Test
    void shouldReturn400WhenNoFieldIsUpdated() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("{}")
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsZero() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": 0 }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'durationMinutes' must be positive"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsNegative() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": -10 }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'durationMinutes' must be positive"));
    }

    @Test
    void shouldReturn400WhenTimezoneIsInvalid() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "Invalid/Timezone",
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "17:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid 'timeZone' format"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleIsMissingDayOfWeek() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'dayOfWeek' must be provided for weekly rule"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleHasInvalidDayOfWeek() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "dayOfWeek": "ABC", "start": "09:00", "end": "12:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Unknown day of week abbreviation: ABC"));
    }

    @Test
    void shouldReturn400WhenWeeklyRuleStartTimeIsInvalid() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "invalid", "end": "12:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm"));
    }

    @Test
    void shouldReturn400WhenFixedRuleStartTimeIsInvalid() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "start": "not-a-date", "end": "2026-01-30T00:00:00", "type": "fixed" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm:ss"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRuleTypeIsUnknown() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "type": "unknown" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Unknown availability rule type: unknown"));
    }

    @Test
    void shouldReturn401WhenUnauthenticated() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        with()
            .auth().none()
            .contentType(ContentType.JSON)
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
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
            .patch("/booking-links/not-a-uuid")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenTimeZoneIsProvidedWithoutAvailabilityRules() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "active": false
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'timeZone' cannot be provided if 'availabilityRules' is not being updated"));
    }

    @Test
    void shouldReturn400WhenTimeZoneIsProvidedWhenAvailabilityRulesIsRemoved() {
        AvailabilityRules existingRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), UTC));
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(existingRules)));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": null
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'timeZone' cannot be provided if 'availabilityRules' is being removed"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRulesAreUpdatedWithoutTimeZone() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "dayOfWeek": "MON", "start": "09:00", "end": "17:00", "type": "weekly" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'timeZone' must be provided when updating 'availabilityRules'"));
    }

    @Test
    void shouldReturn400WhenCalendarUrlIsNull() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "calendarUrl": null }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'calendarUrl' can not be removed"));
    }

    @Test
    void shouldReturn400WhenDurationMinutesIsNull() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "durationMinutes": null }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'eventDuration' can not be removed"));
    }

    @Test
    void shouldReturn400WhenActiveIsNull() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                { "active": null }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'active' can not be removed"));
    }

    @Test
    void shouldReturn400WhenAvailabilityRulesIsEmptyArray() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": []
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'availabilityRules' cannot be empty if provided"));
    }

    @Test
    void shouldReturn400WhenFixedRuleStartIsAfterEnd() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
            .body("""
                {
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "start": "2026-01-30T00:00:00", "end": "2026-01-26T00:00:00", "type": "fixed" }
                    ]
                }
                """)
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("'start' must be before 'end' for fixed rule"));
    }

    @Test
    void shouldReturn400WhenCalendarUrlBelongsToAnotherUser() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
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
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.details", equalTo("Calendar not found or access denied: " + otherUserCalendarUrl.asUri()));
    }

    @Test
    void shouldReturn400WhenCalendarDoesNotExist() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        CalendarURL nonExistentCalendarUrl = new CalendarURL(openPaaSUser.id(), new OpenPaaSId("nonexistentCalendar"));

        given()
            .body("""
                {
                    "calendarUrl": "%s"
                }
                """.formatted(nonExistentCalendarUrl.asUri().toString()))
        .when()
            .patch("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Calendar not found or access denied: " + nonExistentCalendarUrl.asUri()));
    }
}
