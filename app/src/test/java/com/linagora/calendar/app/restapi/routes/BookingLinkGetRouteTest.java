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
import java.util.List;
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
import com.linagora.calendar.app.ResourceProbe;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkAlarm;
import com.linagora.calendar.storage.booking.BookingLinkAlarmAction;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.EventTransparency;
import com.linagora.calendar.storage.booking.EventVisibility;
import com.linagora.calendar.storage.booking.ExtraAttendees;
import com.linagora.calendar.storage.model.Resource;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkGetRouteTest {

    private static final boolean NOT_ACTIVE = false;
    private static final String PASSWORD = "secret";
    private static final ZoneId ZONE_HO_CHI_MINH = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ZoneId UTC = ZoneId.of("UTC");

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
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding()
                .to(BookingLinkProbe.class);
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding()
                .to(ResourceProbe.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private BookingLinkProbe bookingLinkProbe;
    private ResourceProbe resourceProbe;
    private OpenPaaSUser openPaaSUser;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        openPaaSUser = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);

        bookingLinkProbe = server.getProbe(BookingLinkProbe.class);
        resourceProbe = server.getProbe(ResourceProbe.class);

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
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithExtraAttendeesWhenSet(TwakeCalendarGuiceServer server) {
        OpenPaaSUser firstAttendee = newProvisionedUser(server);
        OpenPaaSUser secondAttendee = newProvisionedUser(server);
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .extraAttendees(ExtraAttendees.of(firstAttendee.id(), secondAttendee.id()))
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "extraAttendees": { "and": [ { "participant": "%s", "name": "%s", "email": "%s" }, { "participant": "%s", "name": "%s", "email": "%s" } ] },
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString(),
                firstAttendee.id().value(), firstAttendee.fullName(), firstAttendee.username().asString(),
                secondAttendee.id().value(), secondAttendee.fullName(), secondAttendee.username().asString()));
    }

    @Test
    void shouldReturn200WithWeeklyAvailabilityRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), ZONE_HO_CHI_MINH),
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0), ZoneId.of("Europe/London")));
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).availabilityRules(rules).build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Asia/Ho_Chi_Minh" },
                        { "type": "weekly", "dayOfWeek": "MON", "start": "13:00", "end": "17:00", "timeZone": "Europe/London" }
                    ],
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithFixedAvailabilityRule() {
        AvailabilityRules rules = AvailabilityRules.of(
            new FixedAvailabilityRule(
                LocalDateTime.parse("2026-01-26T02:00:00").atZone(UTC),
                LocalDateTime.parse("2026-01-30T02:00:00").atZone(UTC)));
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(60)).active(NOT_ACTIVE).availabilityRules(rules).build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "availabilityRules": [
                        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "timeZone": "UTC" }
                    ],
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithMixedAvailabilityRules() {
        AvailabilityRules rules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), ZONE_HO_CHI_MINH),
            new FixedAvailabilityRule(
                LocalDateTime.parse("2026-01-26T00:00:00").atZone(ZoneId.of("Europe/London")),
                LocalDateTime.parse("2026-01-30T00:00:00").atZone(ZoneId.of("Europe/London"))));
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).availabilityRules(rules).build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "TUE", "start": "09:00", "end": "17:00", "timeZone": "Asia/Ho_Chi_Minh" },
                        { "type": "fixed", "start": "2026-01-26T00:00:00", "end": "2026-01-30T00:00:00", "timeZone": "Europe/London" }
                    ],
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithNameAndDescription() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .name("Intro call")
                .description("Book a 30-minute introduction call")
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "name": "Intro call",
                    "description": "Book a 30-minute introduction call",
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithAutoAccept() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .autoAccept(true)
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": true,
                    "color": "#6B4ECC"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithCustomColor() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .color("#FF8800")
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
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
                    "autoAccept": false,
                    "color": "#FF8800"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithLocation() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .location("Room 3, Building A")
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "autoAccept": false,
                    "color": "#6B4ECC",
                    "location": "Room 3, Building A"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithVisibility() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .visibility(EventVisibility.PRIVATE)
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "autoAccept": false,
                    "color": "#6B4ECC",
                    "visibility": "PRIVATE"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithTransparency() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .transparency(EventTransparency.TRANSPARENT)
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "autoAccept": false,
                    "color": "#6B4ECC",
                    "transparency": "TRANSPARENT"
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn200WithResources() {
        Resource projector = resourceProbe.save(openPaaSUser, "Projector", "projector");
        Resource whiteboard = resourceProbe.save(openPaaSUser, "Whiteboard", "whiteboard");
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .resources(List.of(projector.id(), whiteboard.id()))
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "autoAccept": false,
                    "color": "#6B4ECC",
                    "resources": [ { "id": "%s", "name": "Projector" }, { "id": "%s", "name": "Whiteboard" } ]
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString(),
                projector.id().value(), whiteboard.id().value()));
    }

    private OpenPaaSUser newProvisionedUser(TwakeCalendarGuiceServer server) {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(user.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(user.username(), PASSWORD);
        return user;
    }

    @Test
    void shouldReturn200WithAlarm() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .alarm(List.of(new BookingLinkAlarm("-PT10M", BookingLinkAlarmAction.EMAIL)))
                .build());

        String response = given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract().body().asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "publicId": "%s",
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "autoAccept": false,
                    "color": "#6B4ECC",
                    "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ]
                }
                """.formatted(inserted.publicId().value(), CalendarURL.from(openPaaSUser.id()).asUri().toString()));
    }

    @Test
    void shouldReturn404WhenBookingLinkDoesNotExist() {
        given()
        .when()
            .get("/api/booking-links/" + UUID.randomUUID())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn404WhenBookingLinkBelongsToAnotherUser() {
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        BookingLink inserted = bookingLinkProbe.insertBookingLink(otherUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(otherUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturn401WhenUnauthenticated() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        with()
            .auth().none()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void shouldReturn400WhenPublicIdIsNotAValidUUID() {
        given()
        .when()
            .get("/api/booking-links/invalid-uuid")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }
}
