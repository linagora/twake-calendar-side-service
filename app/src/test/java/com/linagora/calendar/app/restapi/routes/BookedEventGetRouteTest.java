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
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
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
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class BookedEventGetRouteTest {

    private static final String PASSWORD = "secret";
    private static final Duration DURATION_30_MINUTES = Duration.ofMinutes(30);
    private static final AvailabilityRules AVAILABILITY_RULE = AvailabilityRules.of(new FixedAvailabilityRule(
        ZonedDateTime.parse("2036-01-26T09:00:00Z"),
        ZonedDateTime.parse("2036-01-26T12:00:00Z")));

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
        binder -> {
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding()
                .to(BookingLinkProbe.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
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
    }

    @Test
    void shouldReturnEventJsonForValidToken(TwakeCalendarGuiceServer server) {
        String token = bookAndGetToken(server);

        String actualResponse = given()
            .auth().none()
            .queryParam("bookingConfirmationToken", token)
        .when()
            .get("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(actualResponse)
            .isEqualTo("""
                {
                  "eventJSON": "${json-unit.ignore}"
                }
                """);

        assertThatJson(actualResponse)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .inPath("eventJSON")
            .isEqualTo("""
                [
                  "vcalendar",
                  [
                    ["version", {}, "text", "2.0"],
                    ["prodid", {}, "text", "${json-unit.ignore}"],
                    ["calscale", {}, "text", "GREGORIAN"]
                  ],
                  [
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "${json-unit.ignore}"],
                        ["transp", {}, "text", "OPAQUE"],
                        ["summary", {}, "text", "30-min intro call"],
                        ["dtstamp", {}, "date-time", "${json-unit.ignore}"],
                        ["dtstart", {}, "date-time", "2036-01-26T09:00:00Z"],
                        ["duration", {}, "duration", "PT30M"],
                        ["organizer", {"cn": "%s"}, "cal-address", "mailto:%s"],
                        ["attendee", {"cn": "%s", "role": "CHAIR", "cutype": "INDIVIDUAL", "partstat": "NEEDS-ACTION", "rsvp": "TRUE"}, "cal-address", "mailto:%s"],
                        ["attendee", {"cn": "BOB", "role": "REQ-PARTICIPANT", "cutype": "INDIVIDUAL", "partstat": "ACCEPTED", "rsvp": "TRUE"}, "cal-address", "mailto:creator@example.com"],
                        ["class", {}, "text", "PUBLIC"],
                        ["x-publicly-created", {}, "boolean", true],
                        ["x-publicly-creator", {}, "text", "creator@example.com"],
                        ["x-openpaas-booking-link", {}, "text", "${json-unit.ignore}"]
                      ],
                      []
                    ]
                  ]
                ]
                """.formatted(
                openPaaSUser.fullName(), openPaaSUser.username().asString(),
                openPaaSUser.fullName(), openPaaSUser.username().asString()));
    }

    @Test
    void shouldReturn400WhenTokenIsMissing() {
        given()
            .auth().none()
        .when()
            .get("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON);
    }

    @Test
    void shouldReturn401WhenTokenIsInvalid() {
        given()
            .auth().none()
            .queryParam("bookingConfirmationToken", "not.a.valid.jwt")
        .when()
            .get("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED)
            .contentType(JSON);
    }

    private String bookAndGetToken(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        return given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": { "name": "BOB", "email": "creator@example.com" },
                  "eventTitle": "30-min intro call"
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract()
            .jsonPath()
            .getString("bookingConfirmationToken");
    }

    private BookingLink insertActiveBookingLink(TwakeCalendarGuiceServer server) {
        return server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(
                CalendarURL.from(openPaaSUser.id()),
                DURATION_30_MINUTES,
                AVAILABILITY_RULE));
    }

    private List<String> getAvailableSlots(BookingLinkPublicId bookingLinkPublicId) {
        return given()
            .pathParam("bookingLinkPublicId", bookingLinkPublicId.value())
            .queryParam("from", "2036-01-26T00:00:00Z")
            .queryParam("to", "2036-01-27T00:00:00Z")
        .when()
            .get("/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .jsonPath()
            .getList("slots.start", String.class);
    }
}
