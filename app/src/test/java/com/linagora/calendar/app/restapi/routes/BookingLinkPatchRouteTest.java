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
import java.util.List;
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
import com.linagora.calendar.app.restapi.routes.PeopleSearchRouteTest.ResourceProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
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

class BookingLinkPatchRouteTest {

    private static final boolean ACTIVE = true;
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

    private OpenPaaSUser newProvisionedUser(TwakeCalendarGuiceServer server) {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        server.getProbe(CalendarDataProbe.class).addUserToRepository(user.username(), PASSWORD);
        return user;
    }

    @Test
    void shouldReturn204WhenUpdatingActive() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
    void shouldPersistUpdatedAutoAccept() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
            .body("""
                { "autoAccept": true }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.autoAccept()).isTrue();
    }

    @Test
    void shouldPersistUpdatedLocation() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "location": "Room 3" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).location()).contains("Room 3");
    }

    @Test
    void shouldRemoveLocationWhenSetToNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .location("Room 3")
                .build());

        given()
            .body("""
                { "location": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).location()).isEmpty();
    }

    @Test
    void shouldPersistUpdatedVisibility() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "visibility": "PRIVATE" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).visibility()).contains(EventVisibility.PRIVATE);
    }

    @Test
    void shouldRemoveVisibilityWhenSetToNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .visibility(EventVisibility.PRIVATE)
                .build());

        given()
            .body("""
                { "visibility": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).visibility()).isEmpty();
    }

    @Test
    void shouldReturn400WhenVisibilityIsInvalid() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "visibility": "SECRET" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldPersistUpdatedTransparency() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "transparency": "TRANSPARENT" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).transparency()).contains(EventTransparency.TRANSPARENT);
    }

    @Test
    void shouldRemoveTransparencyWhenSetToNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .transparency(EventTransparency.TRANSPARENT)
                .build());

        given()
            .body("""
                { "transparency": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).transparency()).isEmpty();
    }

    @Test
    void shouldReturn400WhenTransparencyIsInvalid() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "transparency": "INVALID" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldPersistUpdatedResources(TwakeCalendarGuiceServer server) {
        Resource resource = server.getProbe(ResourceProbe.class).save(openPaaSUser, "Projector", "projector");
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "resources": ["%s"] }
                """.formatted(resource.id().value()))
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).resources()).containsExactly(resource.id());
    }

    @Test
    void shouldRemoveResourcesWhenSetToEmptyArray(TwakeCalendarGuiceServer server) {
        Resource resource = server.getProbe(ResourceProbe.class).save(openPaaSUser, "Projector", "projector");
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .resources(List.of(resource.id()))
                .build());

        given()
            .body("""
                { "resources": [] }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).resources()).isEmpty();
    }

    @Test
    void shouldRemoveResourcesWhenSetToNull(TwakeCalendarGuiceServer server) {
        Resource resource = server.getProbe(ResourceProbe.class).save(openPaaSUser, "Projector", "projector");
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .resources(List.of(resource.id()))
                .build());

        given()
            .body("""
                { "resources": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).resources()).isEmpty();
    }

    @Test
    void shouldReturn400WhenResourceDoesNotExist() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "resources": ["659387b9d486dc0046aeffff"] }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldPersistUpdatedAlarm() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "alarm": [ { "period": "-PT10M", "action": "EMAIL" } ] }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).alarm()).contains(new BookingLinkAlarm("-PT10M", BookingLinkAlarmAction.EMAIL));
    }

    @Test
    void shouldRemoveAlarmWhenSetToNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .alarm(List.of(new BookingLinkAlarm("-PT10M", BookingLinkAlarmAction.EMAIL)))
                .build());

        given()
            .body("""
                { "alarm": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        assertThat(bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId()).alarm()).isEmpty();
    }

    @Test
    void shouldReturn400WhenAlarmPeriodIsInvalid() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "alarm": [ { "period": "not-a-duration", "action": "EMAIL" } ] }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldReturn400WhenAlarmActionIsInvalid() {
        BookingLink inserted = insertMinimal();

        given()
            .body("""
                { "alarm": [ { "period": "-PT10M", "action": "INVALID" } ] }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldPersistUpdatedNameAndDescription() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
            .body("""
                { "name": "Intro call", "description": "Book a 30-minute introduction call" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.name()).contains("Intro call");
        assertThat(updated.description()).contains("Book a 30-minute introduction call");
    }

    @Test
    void shouldRemoveNameAndDescriptionWhenSetToNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .name("Intro call")
                .description("Some description")
                .build());

        given()
            .body("""
                { "name": null, "description": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.name()).isEmpty();
        assertThat(updated.description()).isEmpty();
    }

    @Test
    void shouldPersistUpdatedColor() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
            .body("""
                { "color": "#FF8800" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.color()).contains("#FF8800");
    }

    @Test
    void shouldRemoveColorWhenSetToNull() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .color("#FF8800")
                .build());

        given()
            .body("""
                { "color": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.color()).isEmpty();
    }

    @Test
    void shouldReturn400WhenColorIsNotAValidHexColor() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
            .body("""
                { "color": "not-a-color" }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldPersistUpdatedDurationMinutes() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).availabilityRules(existingRules).build());

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
    void shouldPersistUpdatedExtraAttendees(TwakeCalendarGuiceServer server) {
        OpenPaaSUser extraAttendee = newProvisionedUser(server);
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
            .body("""
                { "extraAttendees": { "and": [ { "participant": "%s" } ] } }
                """.formatted(extraAttendee.id().value()))
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.extraAttendees()).isEqualTo(ExtraAttendees.of(extraAttendee.id()));
    }

    @Test
    void shouldRemoveExtraAttendeesWhenSetToNull(TwakeCalendarGuiceServer server) {
        OpenPaaSUser extraAttendee = newProvisionedUser(server);
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .extraAttendees(ExtraAttendees.of(extraAttendee.id()))
                .build());

        given()
            .body("""
                { "extraAttendees": null }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        BookingLink updated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(updated.extraAttendees()).isEqualTo(ExtraAttendees.NONE);
    }

    @Test
    void shouldReturn400WhenUpdatedExtraAttendeeDoesNotExist(TwakeCalendarGuiceServer server) {
        OpenPaaSUser extraAttendee = newProvisionedUser(server);
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .extraAttendees(ExtraAttendees.of(extraAttendee.id()))
                .build());

        given()
            .body("""
                { "extraAttendees": { "and": [ { "participant": "659387b9d486dc0046aeffff" } ] } }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);

        BookingLink notUpdated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(notUpdated.extraAttendees()).isEqualTo(ExtraAttendees.of(extraAttendee.id()));
    }

    @Test
    void shouldReturn400WhenUpdatedExtraAttendeesContainsTheOwner(TwakeCalendarGuiceServer server) {
        OpenPaaSUser extraAttendee = newProvisionedUser(server);
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder()
                .calendarUrl(CalendarURL.from(openPaaSUser.id()))
                .eventDuration(Duration.ofMinutes(30))
                .extraAttendees(ExtraAttendees.of(extraAttendee.id()))
                .build());

        given()
            .body("""
                { "extraAttendees": { "and": [ { "participant": "%s" } ] } }
                """.formatted(openPaaSUser.id().value()))
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);

        BookingLink notUpdated = bookingLinkProbe.findBookingLink(openPaaSUser.username(), inserted.publicId());
        assertThat(notUpdated.extraAttendees()).isEqualTo(ExtraAttendees.of(extraAttendee.id()));
    }

    @Test
    void shouldReturn400WhenUpdatedExtraAttendeeIsBlank() {
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

        given()
            .body("""
                { "extraAttendees": { "and": [ { "participant": " " } ] } }
                """)
        .when()
            .patch("/api/booking-links/" + inserted.publicId().value())
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldNotUpdateFieldsAbsentFromRequest() {
        AvailabilityRules existingRules = AvailabilityRules.of(
            new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), UTC));
        BookingLink inserted = bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).availabilityRules(existingRules).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(otherUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());

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

    private BookingLink insertMinimal() {
        return bookingLinkProbe.insertBookingLink(openPaaSUser.username(),
            BookingLinkInsertRequest.builder().calendarUrl(CalendarURL.from(openPaaSUser.id())).eventDuration(Duration.ofMinutes(30)).build());
    }
}
