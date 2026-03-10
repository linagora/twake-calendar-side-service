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
import com.linagora.calendar.app.BookingLinkDataProbe;
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
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkGetRouteTest {

    private static final boolean ACTIVE = true;
    private static final boolean NOT_ACTIVE = false;
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

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
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
    }

    @Test
    void shouldReturn200WithExpectedBodyWhenNoAvailabilityRules() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        String response = given()
        .when()
            .get("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(ContentType.JSON)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithWeeklyAvailabilityRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), ZONE_HO_CHI_MINH),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0), ZONE_HO_CHI_MINH));
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(rules)));

        String response = given()
        .when()
            .get("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(ContentType.JSON)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "timeZone": "Asia/Ho_Chi_Minh",
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00" },
                        { "type": "weekly", "dayOfWeek": "MON", "start": "13:00", "end": "17:00" }
                    ]
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithFixedAvailabilityRule() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(
                LocalDateTime.parse("2026-01-26T02:00:00").atZone(UTC),
                LocalDateTime.parse("2026-01-30T02:00:00").atZone(UTC)));
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(60), NOT_ACTIVE, Optional.of(rules)));

        String response = given()
        .when()
            .get("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(ContentType.JSON)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 60,
                    "active": false,
                    "timeZone": "UTC",
                    "availabilityRules": [
                        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00" }
                    ]
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithMixedAvailabilityRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), ZONE_HO_CHI_MINH),
            new FixedAvailabilityRule(
                LocalDateTime.parse("2026-01-26T00:00:00").atZone(ZONE_HO_CHI_MINH),
                LocalDateTime.parse("2026-01-30T00:00:00").atZone(ZONE_HO_CHI_MINH)));
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.of(rules)));

        String response = given()
        .when()
            .get("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(ContentType.JSON)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "timeZone": "Asia/Ho_Chi_Minh",
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "TUE", "start": "09:00", "end": "17:00" },
                        { "type": "fixed", "start": "2026-01-26T00:00:00", "end": "2026-01-30T00:00:00" }
                    ]
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn404WhenBookingLinkDoesNotExist() {
        given()
        .when()
            .get("/booking-links/" + UUID.randomUUID())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn404WhenBookingLinkBelongsToAnotherUser() {
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        BookingLink inserted = dataProbe.insertBookingLink(otherUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(otherUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        given()
        .when()
            .get("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn401WhenUnauthenticated() {
        BookingLink inserted = dataProbe.insertBookingLink(openPaaSUser.username(),
            new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), Duration.ofMinutes(30), ACTIVE, Optional.empty()));

        with()
            .auth().none()
            .contentType(ContentType.JSON)
        .when()
            .get("/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldReturn400WhenPublicIdIsNotAValidUUID() {
        given()
        .when()
            .get("/booking-links/invalid-uuid")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }
}
