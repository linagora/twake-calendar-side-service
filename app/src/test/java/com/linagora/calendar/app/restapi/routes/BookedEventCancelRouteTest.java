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
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
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
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

class BookedEventCancelRouteTest {

    private static final String PASSWORD = "secret";
    private static final Duration DURATION_30_MINUTES = Duration.ofMinutes(30);
    private static final AvailabilityRules AVAILABILITY_RULE = AvailabilityRules.of(new FixedAvailabilityRule(
        ZonedDateTime.parse("2036-01-26T09:00:00Z"),
        ZonedDateTime.parse("2036-01-26T12:00:00Z")));
    private static final ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();

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
    private CalDavClient calDavClient;

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

        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        given(mockSMTPRequestSpecification())
            .delete("/smtpMails")
            .then();

        given(mockSMTPRequestSpecification())
            .delete("/smtpBehaviors")
            .then();
    }

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    @Test
    void shouldCancelBookedEventAndReturn204(TwakeCalendarGuiceServer server) {
        String jwt = bookAndGetJwt(server);

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
    void cancelShouldBeIdempotent(TwakeCalendarGuiceServer server) {
        String jwt = bookAndGetJwt(server);

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

        given()
            .auth().none()
            .queryParam("bookingConfirmationToken", jwt)
        .when()
            .delete("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void shouldReturn400WhenTokenIsMissing() {
        given()
            .auth().none()
        .when()
            .delete("/api/booked-event")
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
            .delete("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED)
            .contentType(JSON);
    }

    @Test
    void cancellationShouldNotifyBookerAndOrganizer(TwakeCalendarGuiceServer server) {
        String jwt = bookAndGetJwt(server);

        // Drop the acknowledgement / proposal emails sent at booking time.
        given(mockSMTPRequestSpecification())
            .delete("/smtpMails")
            .then();

        given()
            .auth().none()
            .queryParam("bookingConfirmationToken", jwt)
        .when()
            .delete("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

        // The booker receives the friendly cancellation acknowledgement and the organizer receives an ICS
        // cancellation. Note: deleting the event on the organizer calendar also emits a standards iTIP CANCEL
        // to the attendee (pre-existing platform behaviour), hence we assert on the specific messages rather
        // than an exact mail count.
        CALMLY_AWAIT.atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                JsonPath mails = smtpMails();
                assertThat(messagesMatching(mails, "creator@example.com", "Subject: Booking request cancelled")).isNotEmpty();
                assertThat(messagesMatching(mails, openPaaSUser.username().asString(), "method=CANCEL")).isNotEmpty();
            });

        JsonPath smtpMailsResponse = smtpMails();
        String bookerMessage = messagesMatching(smtpMailsResponse, "creator@example.com", "Subject: Booking request cancelled").getFirst();
        String organizerMessage = messagesMatching(smtpMailsResponse, openPaaSUser.username().asString(), "method=CANCEL").getFirst();

        assertSoftly(softly -> {
            // The booker receives a cancellation acknowledgement.
            softly.assertThat(getHtml(bookerMessage))
                .contains("Your booking request has been cancelled")
                .contains("You have cancelled your booking request on")
                .contains("The organizer was notified about the cancellation.")
                .contains(openPaaSUser.fullName())
                .contains(openPaaSUser.username().asString());

            // The organizer receives an ICS cancellation.
            softly.assertThat(organizerMessage)
                .contains("Subject: Event 30-min intro call from %s canceled".formatted(openPaaSUser.fullName()));
            softly.assertThat(getCancelIcs(organizerMessage))
                .contains("METHOD:CANCEL")
                .contains("STATUS:CANCELLED");
        });
    }

    private JsonPath smtpMails() {
        return given(mockSMTPRequestSpecification())
            .get("/smtpMails")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .jsonPath();
    }

    private List<String> messagesMatching(JsonPath smtpMailsResponse, String recipient, String rawContent) {
        return IntStream.range(0, smtpMailsResponse.getList("").size())
            .filter(index -> recipient.equals(smtpMailsResponse.getString("[%d].recipients[0].address".formatted(index))))
            .mapToObj(index -> smtpMailsResponse.getString("[%d].message".formatted(index)))
            .filter(message -> message.contains(rawContent))
            .toList();
    }

    private String getHtml(String message) {
        Pattern htmlPattern = Pattern.compile(
            "Content-Transfer-Encoding: base64\\r?\\nContent-Type: text/html; charset=UTF-8\\r?\\nContent-Language: [^\\r\\n]+\\r?\\n\\r?\\n([A-Za-z0-9+/=\\r\\n]+)\\r?\\n---=Part",
            Pattern.DOTALL);
        Matcher matcher = htmlPattern.matcher(message);
        assertThat(matcher.find()).isTrue();
        String base64Html = matcher.group(1).replaceAll("\\s+", "");
        return new String(Base64.getDecoder().decode(base64Html), StandardCharsets.UTF_8);
    }

    private String getCancelIcs(String message) {
        Matcher matcher = Pattern.compile("\\r?\\n\\r?\\n([A-Za-z0-9+/=\\r\\n]{40,})\\r?\\n---=Part", Pattern.DOTALL)
            .matcher(message);
        while (matcher.find()) {
            String decoded = new String(Base64.getMimeDecoder().decode(matcher.group(1).replaceAll("\\s+", "")), StandardCharsets.UTF_8);
            if (decoded.contains("BEGIN:VCALENDAR")) {
                return decoded;
            }
        }
        throw new AssertionError("No VCALENDAR attachment found in message");
    }

    private String bookAndGetJwt(TwakeCalendarGuiceServer server) {
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
