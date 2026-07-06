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
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkPastManagementRouteTest {

    private static final String PASSWORD = "secret";
    private static final Duration DURATION_30_MINUTES = Duration.ofMinutes(30);
    private static final String FROM_20360126 = "2036-01-26T00:00:00Z";
    private static final String TO_20360127 = "2036-01-27T00:00:00Z";
    // A booking link available on 2036-01-26 from 09:00 to 12:00 UTC (30-minute slots).
    private static final AvailabilityRules AVAILABILITY_RULE = AvailabilityRules.of(new FixedAvailabilityRule(
        ZonedDateTime.parse("2036-01-26T09:00:00Z"),
        ZonedDateTime.parse("2036-01-26T12:00:00Z")));
    // "Now" sits in the middle of the availability window: 09:00, 09:30 and 10:00 are already in the past.
    private static final Instant NOW = Instant.parse("2036-01-26T10:15:00Z");

    static final UpdatableTickingClock clock = new UpdatableTickingClock(NOW);

    static class BookingLinkProbe implements GuiceProbe {
        private final BookingLinkDAO bookingLinkDAO;

        @Inject
        BookingLinkProbe(BookingLinkDAO bookingLinkDAO) {
            this.bookingLinkDAO = bookingLinkDAO;
        }

        BookingLink insert(Username username, BookingLinkInsertRequest request) {
            return bookingLinkDAO.insert(username, request).block();
        }
    }

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

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
        binder -> binder.bind(MailTemplateConfiguration.class)
            .toInstance(new MailTemplateConfiguration("classpath://templates/",
                MaybeSender.getMailSender("no-reply@openpaas.org"))),
        binder -> binder.bind(MailSenderConfiguration.class)
            .toInstance(mailSenderConfigurationFunction.apply(mockSmtpExtension)),
        binder -> binder.bind(Clock.class).toInstance(clock),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding()
            .to(BookingLinkProbe.class));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;
    private CalDavClient calDavClient;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        clock.setInstant(NOW);

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        Username ownerUsername = Username.fromLocalPartWithDomain("owner-" + UUID.randomUUID(), Domain.of("open-paas.org"));
        calendarDataProbe.addDomain(ownerUsername.getDomainPart().orElseThrow());
        calendarDataProbe.addUser(ownerUsername, PASSWORD, "Owner", "User");
        openPaaSUser = calendarDataProbe.getUser(ownerUsername);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .build();

        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @Test
    void slotsShouldExcludeSlotsStartingInThePast(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        String response = given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should drop the 09:00, 09:30 and 10:00 slots that already elapsed at 10:15")
            .inPath("$.slots")
            .isEqualTo("""
                [
                  { "start": "2036-01-26T10:30:00Z" },
                  { "start": "2036-01-26T11:00:00Z" },
                  { "start": "2036-01-26T11:30:00Z" }
                ]
                """);
    }

    @Test
    void bookingShouldBeRejectedForSlotInThePast(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bookingRequestBody("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY);
    }

    @Test
    void bookingShouldSucceedForFutureSlot(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bookingRequestBody("2036-01-26T11:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);
    }

    @Test
    void cancelShouldSucceedWhenEventIsStillInTheFuture(TwakeCalendarGuiceServer server) {
        String jwt = bookFutureEventAndGetJwt(server);

        given()
            .auth().none()
            .queryParam("bookingConfirmationToken", jwt)
        .when()
            .delete("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        List<String> eventIds = calDavClient.findUserCalendarEventIds(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()))
            .collectList()
            .block();
        assertThat(eventIds).isEmpty();
    }

    @Test
    void cancelShouldBeRejectedWhenEventAlreadyStarted(TwakeCalendarGuiceServer server) {
        String jwt = bookFutureEventAndGetJwt(server);

        // Time travels past the booked 11:00 slot.
        clock.setInstant(Instant.parse("2036-01-26T11:30:00Z"));

        given()
            .auth().none()
            .queryParam("bookingConfirmationToken", jwt)
        .when()
            .delete("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY);

        List<String> eventIds = calDavClient.findUserCalendarEventIds(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()))
            .collectList()
            .block();
        assertThat(eventIds)
            .describedAs("the past event must remain in the calendar since cancellation is rejected")
            .hasSize(1);
    }

    private String bookFutureEventAndGetJwt(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        return given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bookingRequestBody("2036-01-26T11:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract()
            .jsonPath()
            .getString("bookingConfirmationToken");
    }

    private String bookingRequestBody(String slotStartUtc) {
        return """
            {
              "startUtc": "%s",
              "creator": { "name": "BOB", "email": "creator@example.com" },
              "eventTitle": "30-min intro call"
            }
            """.formatted(slotStartUtc);
    }

    private BookingLink insertActiveBookingLink(TwakeCalendarGuiceServer server) {
        return server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(
                CalendarURL.from(openPaaSUser.id()),
                DURATION_30_MINUTES,
                AVAILABILITY_RULE));
    }
}
