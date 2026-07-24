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
import static net.javacrumbs.jsonunit.JsonMatchers.jsonEquals;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.assertj.core.groups.Tuple;
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
import com.linagora.calendar.app.BookingLinkProbe;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarTestUtil;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.storage.booking.ExtraAttendees;
import com.linagora.calendar.storage.event.EventFields.Person;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.model.TeamCalendarId;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

class BookingLinkReservationRouteTest {
    private static final String PASSWORD = "secret";
    private static final String BOOKING_CONFIRMED_LINK_PREFIX = "https://excal.linagora.com/booking/confirmed/";
    private static final Duration DURATION_30_MINUTES = Duration.ofMinutes(30);
    private static final AvailabilityRules AVAILABILITY_RULE = AvailabilityRules.of(new FixedAvailabilityRule(
        ZonedDateTime.parse("2036-01-26T09:00:00Z"),
        ZonedDateTime.parse("2036-01-26T12:00:00Z")));
    private static final ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();

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
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding()
            .to(BookingLinkProbe.class));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;
    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;
    private int restApiPort;

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        Username ownerUsername = Username.fromLocalPartWithDomain("owner-" + UUID.randomUUID(), Domain.of("open-paas.org"));
        calendarDataProbe.addDomain(ownerUsername.getDomainPart().orElseThrow());
        calendarDataProbe.addUser(ownerUsername, PASSWORD, "Owner", "User");
        openPaaSUser = calendarDataProbe.getUser(ownerUsername);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .build();

        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        given(mockSMTPRequestSpecification())
            .delete("/smtpMails")
            .then();

        given(mockSMTPRequestSpecification())
            .delete("/smtpBehaviors")
            .then();
    }

    @Test
    void shouldReturnBookingConfirmationTokenOnSuccessfulBooking(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        String bookingConfirmationToken = given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .contentType(JSON)
            .extract()
            .jsonPath()
            .getString("bookingConfirmationToken");

        String eventId = calDavClient.findUserCalendarEventIds(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()))
            .collectList()
            .block()
            .getFirst();

        String payload = new String(Base64.getUrlDecoder().decode(bookingConfirmationToken.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThatJson(payload)
            .withOptions(net.javacrumbs.jsonunit.core.Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                  "publicBookingLinkId": "%s",
                  "calendarId": "%s",
                  "ownerId": "%s",
                  "eventId": "%s"
                }
                """.formatted(inserted.publicId().value(),
                openPaaSUser.id().value(),
                openPaaSUser.id().value(),
                eventId));
    }

    @Test
    void shouldCreateBookingWithoutAuthentication(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        given()
            .auth().none()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "additional_attendees": [
                    {
                      "name": "Nguyen Van A",
                      "email": "vana@example.com"
                    }
                  ],
                  "eventTitle": "30-min intro call",
                  "visioLink": false,
                  "notes": ""
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);
    }
    
    @Test
    void shouldCreateBookingAndPersistEventToSabre(TwakeCalendarGuiceServer server) {
        // Given: an active public booking link owned by the test user.
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).get(1);

        // When: a valid reservation request is posted.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "additional_attendees": [
                    {
                      "name": "Nguyen Van A",
                      "email": "vana@example.com"
                    }
                  ],
                  "eventTitle": "30-min intro call",
                  "visioLink": true,
                  "notes": "Please call via Zoom."
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then: one event is persisted and the exported ICS contains the expected booking metadata.
        List<String> eventIds = calDavClient.findUserCalendarEventIds(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()))
            .collectList()
            .block();

        assertThat(eventIds)
            .describedAs("a successful booking should persist exactly one event")
            .hasSize(1);

        Calendar exportedCalendar = CalendarTestUtil.parseIcs(exportCalendar(openPaaSUser));
        VEvent event = (VEvent) exportedCalendar.getComponent(Component.VEVENT).orElseThrow();
        Function<String, String> getPropertyValue = property -> event.getProperty(property).get().getValue();

        assertSoftly(softly -> {
            softly.assertThat(getPropertyValue.apply(Property.TRANSP))
                .isEqualTo("OPAQUE");
            softly.assertThat(getPropertyValue.apply(Property.SUMMARY))
                .isEqualTo("30-min intro call");
            softly.assertThat(getPropertyValue.apply(Property.DTSTART))
                .isEqualTo("20360126T093000Z");
            softly.assertThat(getPropertyValue.apply(Property.DURATION))
                .isEqualTo("PT30M");
            softly.assertThat(EventParseUtils.getOrganizer(event))
                .get()
                .extracting(Person::cn, person -> person.email().asString(), Person::partStat)
                .containsExactly(openPaaSUser.fullName(), openPaaSUser.username().asString(), Optional.empty());
            softly.assertThat(EventParseUtils.getAttendees(event))
                .extracting(Person::cn, person -> person.email().asString(), Person::partStat)
                .containsExactly(
                    Tuple.tuple(openPaaSUser.fullName(), openPaaSUser.username().asString(), Optional.of(PartStat.NEEDS_ACTION)),
                    Tuple.tuple("BOB", "creator@example.com", Optional.of(PartStat.ACCEPTED)),
                    Tuple.tuple("Nguyen Van A", "vana@example.com", Optional.of(PartStat.ACCEPTED)));
            softly.assertThat(getPropertyValue.apply("X-OPENPAAS-VIDEOCONFERENCE"))
                .startsWith("https://jitsi.linagora.com/")
                .matches("https://jitsi\\.linagora\\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}");
            softly.assertThat(getPropertyValue.apply(Property.DESCRIPTION))
                .isEqualTo("Please call via Zoom.\nVisio: " + getPropertyValue.apply("X-OPENPAAS-VIDEOCONFERENCE"));
            softly.assertThat(getPropertyValue.apply("X-PUBLICLY-CREATED"))
                .isEqualTo("TRUE");
            softly.assertThat(getPropertyValue.apply("X-PUBLICLY-CREATOR"))
                .isEqualTo("creator@example.com");
            softly.assertThat(getPropertyValue.apply("X-OPENPAAS-BOOKING-LINK"))
                .isEqualTo(inserted.publicId().value().toString());
        });
    }

    @Test
    void shouldSendProposalEmailToOrganizerWhenBookingCreated(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "eventTitle": "30-min intro call"
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        JsonPath smtpMailsResponse = awaitBookingEmails();
        int organizerMailIndex = mailIndexOfRecipient(smtpMailsResponse, openPaaSUser.username().asString());

        assertSoftly(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[%d].from".formatted(organizerMailIndex)))
                .isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[%d].recipients[0].address".formatted(organizerMailIndex)))
                .isEqualTo(openPaaSUser.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[%d].message".formatted(organizerMailIndex)))
                .contains("Subject: New event proposition from BOB: 30-min intro call");
        });
    }

    @Test
    void proposalEmailShouldNotCarryIcsAttachment(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "eventTitle": "30-min intro call"
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        String message = messageForRecipient(awaitBookingEmails(), openPaaSUser.username().asString());

        assertThat(message)
            .doesNotContain("text/calendar")
            .doesNotContain("application/ics");
    }

    @Test
    void shouldSendAcknowledgementEmailToBookerWhenBookingCreated(TwakeCalendarGuiceServer server) {
        // Given: an active booking link with a description and an available slot.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, BookingLinkInsertRequest.ACTIVE,
            Optional.of(AVAILABILITY_RULE), Optional.of("Intro call"), Optional.of("A short call to get to know each other"));
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        // When: an unauthenticated booker submits a booking request with custom content.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "additional_attendees": [
                    {
                      "name": "Nguyen Van A",
                      "email": "vana@example.com"
                    }
                  ],
                  "eventTitle": "30-min intro call",
                  "notes": "Please call via Zoom."
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        JsonPath smtpMailsResponse = awaitBookingEmails();
        int bookerMailIndex = mailIndexOfRecipient(smtpMailsResponse, "creator@example.com");
        String rawMessage = smtpMailsResponse.getString("[%d].message".formatted(bookerMailIndex));
        String html = getHtml(rawMessage);

        // Then: the booker receives a generic acknowledgement without unauthenticated request content.
        assertSoftly(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[%d].from".formatted(bookerMailIndex)))
                .isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[%d].recipients[0].address".formatted(bookerMailIndex)))
                .isEqualTo("creator@example.com");
            softly.assertThat(rawMessage).contains("Subject: Booking request received");
            softly.assertThat(html)
                .contains("Your booking request has been received")
                .contains("We have registered your booking request on")
                .contains("The organizer was notified about it and will validate it in a timely manner.")
                .contains("<strong>Saturday, 26 January 2036");
            softly.assertThat(html)
                .contains(openPaaSUser.fullName())
                .contains(openPaaSUser.username().asString())
                .contains("A short call to get to know each other");
            softly.assertThat(html)
                .doesNotContain("Please call via Zoom.");
        });
    }

    @Test
    void acknowledgementEmailShouldContainAWorkingLinkToTheReservationPage(TwakeCalendarGuiceServer server) {
        // Given: an active booking link with an available slot.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, BookingLinkInsertRequest.ACTIVE,
            Optional.of(AVAILABILITY_RULE), Optional.of("Intro call"), Optional.empty());
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        // When: a booker submits a booking request.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        String html = getHtml(messageForRecipient(awaitBookingEmails(), "creator@example.com"));
        List<String> reservationLinks = extractLinksStartingWith(html, BOOKING_CONFIRMED_LINK_PREFIX);

        // Then: the acknowledgement email carries a link to the reservation page...
        assertThat(reservationLinks).hasSize(1);

        // ... and the JWT it embeds grants access to the booked event.
        given()
            .auth().none()
            .queryParam("bookingConfirmationToken", reservationLinks.getFirst().substring(BOOKING_CONFIRMED_LINK_PREFIX.length()))
        .when()
            .get("/api/booked-event")
        .then()
            .statusCode(HttpStatus.SC_OK);
    }

    @Test
    void shouldAutoAcceptOrganizerAndSkipNotificationsWhenAutoAcceptEnabled(TwakeCalendarGuiceServer server) {
        // Given: an active booking link that auto-accepts bookings.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, BookingLinkInsertRequest.ACTIVE, true,
            Optional.of(AVAILABILITY_RULE), Optional.empty(), Optional.empty());
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        // When: a booking is created.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then: the organizer auto-accepts on the persisted event.
        String unfoldedCalendar = exportCalendar(openPaaSUser).replace("\r\n ", "");
        assertThat(unfoldedCalendar)
            .contains("ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED;CN=%s:mailto:%s"
                .formatted(openPaaSUser.fullName(), openPaaSUser.username().asString()));

        // And: no proposal nor acknowledgement email is sent.
        JsonPath smtpMails = given(mockSMTPRequestSpecification())
            .get("/smtpMails")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .jsonPath();
        assertThat(smtpMails.getList("")).isEmpty();
    }

    @Test
    void shouldIncludeThreeParticipationActionLinksInProposalEmail(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "eventTitle": "30-min intro call"
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        String rawMessage = messageForRecipient(awaitBookingEmails(), openPaaSUser.username().asString());
        List<String> actionLinks = extractParticipationActionLinks(getHtml(rawMessage));

        assertSoftly(softly -> {
            softly.assertThat(actionLinks).hasSize(3).doesNotHaveDuplicates();
            softly.assertThat(actionLinks).allMatch(link -> link.startsWith("https://excal.linagora.com/excal/?jwt="));
        });

        String actualResponse = given()
            .when()
            .get(toParticipationEndpoint(actionLinks.getFirst()))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(actualResponse).isEqualTo("""
            {
              "eventJSON": "${json-unit.ignore}",
              "attendeeEmail": "%s",
              "locale": "en",
              "links": {
                "yes": "${json-unit.ignore}",
                "no": "${json-unit.ignore}",
                "maybe": "${json-unit.ignore}"
              }
            }
            """.formatted(openPaaSUser.username().asString()));
    }

    @Test
    void shouldUpdateOrganizerPartStatWhenProposalActionLinksAreAccessed(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "name": "BOB",
                    "email": "creator@example.com"
                  },
                  "eventTitle": "30-min intro call"
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        List<String> actionLinks = extractParticipationActionLinks(getHtml(
            messageForRecipient(awaitBookingEmails(), openPaaSUser.username().asString())));
        Map<String, String> linksByLabel = Map.of(
            "yes", actionLinks.get(0),
            "maybe", actionLinks.get(1),
            "no", actionLinks.get(2));
        Consumer<String> awaitOrganizerPartStat = expectedPartStat ->
            CALMLY_AWAIT
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(exportCalendar(openPaaSUser)
                    .replace("\r\n ", ""))
                    .contains("ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=%s;PARTSTAT=%s:mailto:%s"
                        .formatted(openPaaSUser.fullName(), expectedPartStat, openPaaSUser.username().asString())));

        given().when().get(toParticipationEndpoint(linksByLabel.get("yes")))
            .then().statusCode(HttpStatus.SC_OK).contentType(JSON);
        awaitOrganizerPartStat.accept("ACCEPTED");

        given().when().get(toParticipationEndpoint(linksByLabel.get("maybe")))
            .then().statusCode(HttpStatus.SC_OK).contentType(JSON);
        awaitOrganizerPartStat.accept("TENTATIVE");

        given().when().get(toParticipationEndpoint(linksByLabel.get("no")))
            .then().statusCode(HttpStatus.SC_OK).contentType(JSON);
        awaitOrganizerPartStat.accept("DECLINED");
    }

    @Test
    void shouldCreateBookingUsingSlotReturnedBySlotsEndpoint(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        List<String> availableSlots = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", "2036-01-26T00:00:00Z")
            .queryParam("to", "2036-01-27T00:00:00Z")
        .when()
            .get("/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .jsonPath()
            .getList("slots.start", String.class);

        assertThat(availableSlots)
            .describedAs("slots endpoint should return at least one available slot")
            .isNotEmpty();

        String availableSlotStart = availableSlots.get(ThreadLocalRandom.current().nextInt(availableSlots.size()));

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(availableSlotStart))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);
    }

    @Test
    void shouldRejectBookingUsingSlotNotReturnedBySlotsEndpoint(TwakeCalendarGuiceServer server) {
        // Given: an active booking link with availability window [09:00, 12:00].
        BookingLink inserted = insertActiveBookingLink(server);
        List<String> availableSlots = getAvailableSlots(inserted.publicId());

        // Given: 08:00 is not in returned slots and is outside the configured rule window.
        String unavailableSlotStart = "2036-01-26T08:00:00Z";
        assertThat(availableSlots)
            .describedAs("sanity check: candidate slot should not be returned by slots endpoint")
            .doesNotContain(unavailableSlotStart);

        // When/Then: booking with this slot should be rejected as not available.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(unavailableSlotStart))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 422,
                  "type": "UnavailableBookingSlot",
                  "message": "Unprocessable Entity",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc %s"
                }
                """.formatted(inserted.publicId().value(), unavailableSlotStart)));
    }

    @Test
    void shouldRejectBookingWhenStartIsInsideRuleButNotOnComputedSlotBoundary(TwakeCalendarGuiceServer server) {
        // Given: a 30-minute booking link with rule window [09:00, 12:00].
        // Computed slot starts are aligned on 30-minute boundaries, e.g. 09:00, 09:30, 10:00...
        BookingLink inserted = insertActiveBookingLink(server);
        List<String> availableSlots = getAvailableSlots(inserted.publicId());

        // Given: 09:15 is inside the free rule window and not busy, but it is not a computed slot boundary.
        String unavailableSlotStart = "2036-01-26T09:15:00Z";
        assertThat(availableSlots)
            .describedAs("09:15 is inside rule window but must not be a computed 30-minute slot")
            .doesNotContain(unavailableSlotStart);

        // When/Then: booking with 09:15 should be rejected because startUtc must match a computed available slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(unavailableSlotStart))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 422,
                  "type": "UnavailableBookingSlot",
                  "message": "Unprocessable Entity",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc %s"
                }
                """.formatted(inserted.publicId().value(), unavailableSlotStart)));
    }

    @Test
    void shouldRejectSecondBookingWhenPostingSameSlotTwice(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        // Given/When: first booking succeeds for the selected slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then: second booking on the same slot is rejected as no longer available.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 422,
                  "type": "UnavailableBookingSlot",
                  "message": "Unprocessable Entity",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc %s"
                }
                """.formatted(inserted.publicId().value(), slotStartUtc)));

        List<String> eventIds = calDavClient.findUserCalendarEventIds(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()))
            .collectList()
            .block();
        assertThat(eventIds)
            .describedAs("second booking attempt should not create another event")
            .hasSize(1);
    }

    @Test
    void shouldCreateSecondBookingWhenPostingNextSlot(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        List<String> availableSlots = getAvailableSlots(inserted.publicId());
        assertThat(availableSlots)
            .describedAs("test requires at least two available slots")
            .hasSizeGreaterThanOrEqualTo(2);

        String firstSlotStartUtc = availableSlots.get(0);
        String nextSlotStartUtc = availableSlots.get(1);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(firstSlotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest(nextSlotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        List<String> eventIds = calDavClient.findUserCalendarEventIds(openPaaSUser.username(), CalendarURL.from(openPaaSUser.id()))
            .collectList()
            .block();
        assertThat(eventIds)
            .describedAs("booking adjacent available slots should create two events")
            .hasSize(2);
    }

    @Test
    void shouldCreateBookingWhenOptionalFieldsAreMissing(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);
        String slotStartUtc = getAvailableSlots(inserted.publicId()).getFirst();

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "%s",
                  "creator": {
                    "email": "creator@example.com"
                  },
                  "eventTitle": "optional fields omitted"
                }
                """.formatted(slotStartUtc))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        String unfoldedCalendar = exportCalendar(openPaaSUser)
            .replace("\r\n ", "");
        assertThat(unfoldedCalendar)
            .describedAs("when optional fields are omitted, ICS should keep defaults and avoid optional properties")
            .containsSubsequence(
                "SUMMARY:optional fields omitted",
                "ORGANIZER;CN=%s:mailto:%s"
                    .formatted(openPaaSUser.fullName(), openPaaSUser.username().asString()),
                "ATTENDEE;RSVP=TRUE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=%s:mailto:%s"
                    .formatted(openPaaSUser.fullName(), openPaaSUser.username().asString()),
                "ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=ACCEPTED:mailto:creator@example.com")
            .doesNotContain("X-OPENPAAS-VIDEOCONFERENCE")
            .doesNotContain("DESCRIPTION:");
    }

    @Test
    void shouldReturnUnprocessableEntityWhenStartUtcDoesNotMatchAnyAvailableSlot(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
            AvailabilityRules.of(new FixedAvailabilityRule(
                ZonedDateTime.parse("2036-01-26T09:00:00Z"),
                ZonedDateTime.parse("2036-01-26T10:00:00Z"))));
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T08:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 422,
                  "type": "UnavailableBookingSlot",
                  "message": "Unprocessable Entity",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc 2036-01-26T08:00:00Z"
                }
                """.formatted(inserted.publicId().value())));
    }

    @Test
    void shouldReturnUnprocessableEntityWhenRequestedSlotIsBusy(TwakeCalendarGuiceServer server) {
        // Given:
        // - Booking rule allows slots from 09:00 to 12:00 UTC with 30-minute duration.
        BookingLink inserted = insertActiveBookingLink(server);

        // - Calendar already has an opaque busy event in range [09:30, 10:00] UTC.
        String busyEventUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingReservationTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DTEND:20360126T100000Z
            SUMMARY:busy-window
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(busyEventUid), busyEventUid);

        // When: booking the exact busy slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:30:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 422,
                  "type": "UnavailableBookingSlot",
                  "message": "Unprocessable Entity",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc 2036-01-26T09:30:00Z"
                }
                """.formatted(inserted.publicId().value())));
    }

    @Test
    void shouldReturnUnprocessableEntityWhenRequestedSlotIsBusyAndAvailabilityRulesAreEmpty(TwakeCalendarGuiceServer server) {
        // Given: a booking link without availability rules. Such a link is "effectively open"
        // (any 30-minute aligned slot is bookable), but busy intervals must still be honoured.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
            BookingLinkInsertRequest.ACTIVE, Optional.empty());
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        // - Calendar already has an opaque busy event in range [09:30, 10:00] UTC.
        String busyEventUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingReservationTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DTEND:20360126T100000Z
            SUMMARY:busy-window
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(busyEventUid), busyEventUid);

        // When: booking the exact busy slot on a link with no availability rules.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:30:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_UNPROCESSABLE_ENTITY)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 422,
                  "type": "UnavailableBookingSlot",
                  "message": "Unprocessable Entity",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc 2036-01-26T09:30:00Z"
                }
                """.formatted(inserted.publicId().value())));
    }

    @Test
    void shouldCreateBookingWhenSlotIsFreeAndAvailabilityRulesAreEmpty(TwakeCalendarGuiceServer server) {
        // Given: a booking link without availability rules and a free (non-busy) aligned slot.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
            BookingLinkInsertRequest.ACTIVE, Optional.empty());
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        // When/Then: booking a free aligned slot succeeds (empty rules are effectively open).
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:30:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);
    }

    @Test
    void shouldReturnBadRequestWhenRequestBodyIsInvalidJson(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("{ invalid-json")
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "Missing or invalid request body"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenStartUtcIsMissing(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "30-min intro call"
            }
            """)
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "Missing or invalid request body"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenCreatorIsMissing(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "eventTitle": "30-min intro call"
            }
            """)
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "Missing or invalid request body"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenCreatorEmailHasInvalidFormat(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "invalid-email"
              },
              "eventTitle": "30-min intro call"
            }
            """)
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'email' has invalid format: invalid-email"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenAdditionalAttendeesContainCreatorEmail(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [
                {
                  "name": "BOB duplicate",
                  "email": "Creator@example.com"
                }
              ],
              "eventTitle": "30-min intro call"
            }
            """)
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'additional_attendees' must not contain creator email"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenAdditionalAttendeesContainDuplicateEmailsIgnoreCase(TwakeCalendarGuiceServer server) {
        // Given: a request containing duplicate attendee emails differing only by letter case.
        BookingLink inserted = insertActiveBookingLink(server);

        // When: posting reservation.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [
                {
                  "name": "A",
                  "email": "vana@example.com"
                },
                {
                  "name": "A duplicate",
                  "email": "VANA@example.com"
                }
              ],
              "eventTitle": "30-min intro call"
            }
            """)
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'additional_attendees' contains duplicate email: VANA@example.com"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenAdditionalAttendeesExceedLimit(TwakeCalendarGuiceServer server) {
        // Given: a request where additional attendees exceed MAX_ADDITIONAL_ATTENDEES.
        BookingLink inserted = insertActiveBookingLink(server);
        String additionalAttendees = IntStream.range(0, 21)
            .mapToObj(i -> """
                {
                  "name": "user-%d",
                  "email": "user-%d@example.com"
                }
                """.formatted(i, i))
            .reduce((left, right) -> left + "," + right)
            .orElse("");

        // When: posting reservation.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [%s],
              "eventTitle": "30-min intro call"
            }
            """.formatted(additionalAttendees))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'additional_attendees' must not exceed 20 items"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenTitleIsBlank(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "   "
            }
            """)
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'eventTitle' must not be blank"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenTitleExceedsMaxLength(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "%s"
            }
            """.formatted("a".repeat(256)))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'eventTitle' must not exceed 255 characters"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenNotesExceedMaxLength(TwakeCalendarGuiceServer server) {
        BookingLink inserted = insertActiveBookingLink(server);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
            {
              "startUtc": "2036-01-26T09:00:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "30-min intro call",
              "notes": "%s"
            }
            """.formatted("n".repeat(2001)))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "BadRequest",
                  "message": "Bad Request",
                  "details": "'notes' must not exceed 2000 characters"
                }
                """));
    }

    @Test
    void shouldReturnBadRequestWhenBookingLinkIsInactive(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()),
            DURATION_30_MINUTES,
            false,
            Optional.of(AVAILABILITY_RULE));
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "type": "InactiveBookingLink",
                  "message": "Bad Request",
                  "details": "The booking link with public id %s is not available"
                }
                """.formatted(inserted.publicId().value())));
    }

    @Test
    void shouldReturnNotFoundWhenBookingLinkDoesNotExist() {
        String notFoundPublicId = UUID.randomUUID().toString();

        given()
            .pathParam("bookingLinkPublicId", notFoundPublicId)
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 404,
                  "type": "NotFound",
                  "message": "Not Found",
                  "details": "Cannot find booking link with publicId %s"
                }
                """.formatted(notFoundPublicId)));
    }

    @Test
    void shouldReturnForbiddenWhenBookingLinkTargetsReadOnlyCalendar(TwakeCalendarGuiceServer server) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        Username delegateUsername = Username.fromLocalPartWithDomain("delegate-" + UUID.randomUUID(), Domain.of("open-paas.org"));
        calendarDataProbe.addUser(delegateUsername, PASSWORD, "Delegate", "User");
        OpenPaaSUser delegate = calendarDataProbe.getUser(delegateUsername);

        // Owner delegates its calendar to the booking link owner in read-only mode.
        CalendarURL ownerCalendar = CalendarURL.from(openPaaSUser.id());
        davTestHelper.grantDelegation(openPaaSUser, ownerCalendar, delegate, "dav:read");
        CalendarURL delegatedCalendar = CALMLY_AWAIT.until(() -> calDavClient.findUserCalendarList(delegate)
            .map(response -> response.calendars().keySet().stream()
                .filter(url -> !url.equals(CalendarURL.from(delegate.id())))
                .findFirst())
            .block(), Optional::isPresent).get();

        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(delegate.username(), new BookingLinkInsertRequest(delegatedCalendar, DURATION_30_MINUTES, AVAILABILITY_RULE));

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN);
    }

    @Test
    void shouldUseTeamCalendarAvailabilityAndCreateEventInTeamCalendar(TwakeCalendarGuiceServer server) {
        // Given Bob is a read-write member of the "Software" team calendar.
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "software-" + UUID.randomUUID(), "Software");
        grantMember(server, teamCalendarId, openPaaSUser, "dav:read-write");
        CalendarURL teamCalendar = findVisibleTeamCalendar(openPaaSUser, "Software");
        String busyEventUid = "team-busy-" + UUID.randomUUID();

        // And the team calendar already has a busy event in the 09:30 slot.
        upsertCalendarEvent(openPaaSUser, teamCalendar, busyEventUid, "team-busy-window",
            "20360126T093000Z", "20360126T100000Z");

        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(teamCalendar, DURATION_30_MINUTES, AVAILABILITY_RULE));

        // When Bob requests the booking link slots.
        List<String> availableSlots = getAvailableSlots(inserted.publicId());

        // Then availability is computed from the team calendar busy intervals.
        assertThat(availableSlots)
            .describedAs("busy intervals from the team calendar should be excluded from booking link availability")
            .contains("2036-01-26T09:00:00Z")
            .doesNotContain("2036-01-26T09:30:00Z");

        // When a requester books an available slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then the event is created in the team calendar, not Bob's personal calendar.
        assertThat(findEventIds(CalendarURL.from(openPaaSUser.id())))
            .describedAs("the booking should not be created in Bob's personal calendar")
            .isEmpty();
        assertThat(findEventIds(teamCalendar))
            .describedAs("the team calendar should contain the existing busy event and the newly booked event")
            .hasSize(2)
            .contains(busyEventUid);

        // And Bob is the organizer of the booking event.
        assertThat(EventParseUtils.getOrganizer(findEventBySummary(teamCalendar, "30-min intro call")))
            .get()
            .extracting(Person::cn, person -> person.email().asString(), Person::partStat)
            .containsExactly(openPaaSUser.fullName(), openPaaSUser.username().asString(), Optional.empty());
    }

    @Test
    void shouldAutoAcceptOrganizerWhenTeamCalendarBookingLinkAutoAccepts(TwakeCalendarGuiceServer server) {
        // Given Bob owns an auto-accept booking link backed by a read-write team calendar.
        CalendarURL teamCalendar = createVisibleTeamCalendar(server, openPaaSUser, "Auto Accept Team", "dav:read-write");
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(teamCalendar, DURATION_30_MINUTES,
                true, true, Optional.of(AVAILABILITY_RULE), Optional.empty(), Optional.empty()));

        // When a requester books an available slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then the event is created in the team calendar and Bob auto-accepts it.
        assertThat(findEventIds(CalendarURL.from(openPaaSUser.id()))).isEmpty();
        assertThat(findEventIds(teamCalendar)).hasSize(1);
        assertThat(EventParseUtils.getAttendees(findEventBySummary(teamCalendar, "30-min intro call")))
            .extracting(Person::cn, person -> person.email().asString(), Person::partStat)
            .contains(Tuple.tuple(openPaaSUser.fullName(), openPaaSUser.username().asString(), Optional.of(PartStat.ACCEPTED)));
    }

    @Test
    void shouldIntersectTeamCalendarAndExtraAttendeeAvailability(TwakeCalendarGuiceServer server) {
        // Given Bob owns a booking link backed by a team calendar and carrying an extra attendee.
        OpenPaaSUser extraAttendee = createTestUser(server, "extra");
        CalendarURL teamCalendar = createVisibleTeamCalendar(server, openPaaSUser, "Team With Extra", "dav:read-write");
        String extraBusyEventUid = "extra-busy-" + UUID.randomUUID();
        upsertCalendarEvent(extraAttendee, CalendarURL.from(extraAttendee.id()), extraBusyEventUid, "extra-attendee-busy",
            "20360126T093000Z", "20360126T100000Z");

        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(teamCalendar, DURATION_30_MINUTES,
                true, BookingLinkInsertRequest.AUTO_ACCEPT, Optional.of(AVAILABILITY_RULE), ExtraAttendees.of(extraAttendee.id()),
                Optional.empty(), Optional.empty(), Optional.empty()));

        // When Bob requests the booking link slots.
        List<String> availableSlots = getAvailableSlots(inserted.publicId());

        // Then the extra attendee busy slot is excluded from the team calendar booking link availability.
        assertThat(availableSlots)
            .contains("2036-01-26T09:00:00Z")
            .doesNotContain("2036-01-26T09:30:00Z");

        // When a requester books an available slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then the booking event is created in the team calendar.
        assertThat(findEventIds(CalendarURL.from(openPaaSUser.id()))).isEmpty();
        assertThat(findEventIds(teamCalendar)).hasSize(1);
    }

    @Test
    void shouldUseTeamCalendarBusyEventCreatedByAnotherMember(TwakeCalendarGuiceServer server) {
        // Given Bob and Alice both own booking links backed by the same read-write team calendar.
        OpenPaaSUser alice = createTestUser(server, "alice");
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "shared-team-" + UUID.randomUUID(), "Shared Team");
        grantMember(server, teamCalendarId, openPaaSUser, "dav:read-write");
        grantMember(server, teamCalendarId, alice, "dav:read-write");
        CalendarURL bobTeamCalendar = findVisibleTeamCalendar(openPaaSUser, "Shared Team");
        CalendarURL aliceTeamCalendar = findVisibleTeamCalendar(alice, "Shared Team");

        BookingLink bobBookingLink = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(bobTeamCalendar, DURATION_30_MINUTES, AVAILABILITY_RULE));
        BookingLink aliceBookingLink = server.getProbe(BookingLinkProbe.class)
            .insert(alice.username(), new BookingLinkInsertRequest(aliceTeamCalendar, DURATION_30_MINUTES, AVAILABILITY_RULE));

        // When a requester books Bob's link at 09:00.
        given()
            .pathParam("bookingLinkPublicId", bobBookingLink.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then Alice's link no longer offers that team calendar slot.
        assertThat(getAvailableSlots(aliceBookingLink.publicId()))
            .doesNotContain("2036-01-26T09:00:00Z")
            .contains("2036-01-26T09:30:00Z");
    }

    @Test
    void shouldUseBookingLinkOwnerAsOrganizerWhenBackedByTeamCalendar(TwakeCalendarGuiceServer server) {
        // Given Alice owns a booking link backed by a team calendar shared with Bob.
        OpenPaaSUser alice = createTestUser(server, "alice");
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "organizer-team-" + UUID.randomUUID(), "Organizer Team");
        grantMember(server, teamCalendarId, openPaaSUser, "dav:read-write");
        grantMember(server, teamCalendarId, alice, "dav:read-write");
        CalendarURL aliceTeamCalendar = findVisibleTeamCalendar(alice, "Organizer Team");

        BookingLink aliceBookingLink = server.getProbe(BookingLinkProbe.class)
            .insert(alice.username(), new BookingLinkInsertRequest(aliceTeamCalendar, DURATION_30_MINUTES, AVAILABILITY_RULE));

        // When a requester books Alice's link.
        given()
            .pathParam("bookingLinkPublicId", aliceBookingLink.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        // Then Alice, as the booking link owner, is the organizer of the event created in the team calendar.
        assertThat(EventParseUtils.getOrganizer(findEventBySummary(alice, aliceTeamCalendar, "30-min intro call")))
            .get()
            .extracting(Person::cn, person -> person.email().asString(), Person::partStat)
            .containsExactly(alice.fullName(), alice.username().asString(), Optional.empty());
    }

    @Test
    void shouldReturnForbiddenWhenBookingLinkTargetsReadOnlyTeamCalendar(TwakeCalendarGuiceServer server) {
        // Given Bob owns a booking link backed by a read-only team calendar.
        CalendarURL teamCalendar = createVisibleTeamCalendar(server, openPaaSUser, "Read Only Team", "dav:read");
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(teamCalendar, DURATION_30_MINUTES, AVAILABILITY_RULE));

        // When a requester books an available slot.
        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_FORBIDDEN);
    }

    @Test
    void shouldAddBookingLinkExtraAttendeesToTheCreatedEvent(TwakeCalendarGuiceServer server) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        Username extraAttendeeUsername = Username.fromLocalPartWithDomain("extra-" + UUID.randomUUID(), Domain.of("open-paas.org"));
        calendarDataProbe.addUser(extraAttendeeUsername, PASSWORD, "Extra", "Attendee");
        OpenPaaSUser extraAttendee = calendarDataProbe.getUser(extraAttendeeUsername);

        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
                true, BookingLinkInsertRequest.AUTO_ACCEPT, Optional.of(AVAILABILITY_RULE), ExtraAttendees.of(extraAttendee.id()),
                Optional.empty(), Optional.empty(), Optional.empty()));

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        String unfoldedCalendar = exportCalendar(openPaaSUser).replace("\r\n ", "");

        assertThat(unfoldedCalendar)
            .describedAs("extra attendees are invited and still have to answer")
            .contains("ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=%s:mailto:%s"
                .formatted(extraAttendee.fullName(), extraAttendee.username().asString()));
    }

    @Test
    void shouldStillBookWhenAnExtraAttendeeNoLongerExists(TwakeCalendarGuiceServer server) {
        // An extra attendee deleted after the booking link creation must not make the link unbookable.
        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
                true, BookingLinkInsertRequest.AUTO_ACCEPT, Optional.of(AVAILABILITY_RULE), ExtraAttendees.of(new OpenPaaSId("659387b9d486dc0046aeffff")),
                Optional.empty(), Optional.empty(), Optional.empty()));

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);
    }

    @Test
    void shouldNotDuplicateAttendeeWhenAnExtraAttendeeBooksTheSlotThemselves(TwakeCalendarGuiceServer server) {
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        Username extraAttendeeUsername = Username.fromLocalPartWithDomain("extra-" + UUID.randomUUID(), Domain.of("open-paas.org"));
        calendarDataProbe.addUser(extraAttendeeUsername, PASSWORD, "Extra", "Attendee");
        OpenPaaSUser extraAttendee = calendarDataProbe.getUser(extraAttendeeUsername);

        BookingLink inserted = server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
                true, BookingLinkInsertRequest.AUTO_ACCEPT, Optional.of(AVAILABILITY_RULE), ExtraAttendees.of(extraAttendee.id()),
                Optional.empty(), Optional.empty(), Optional.empty()));

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body("""
                {
                  "startUtc": "2036-01-26T09:00:00Z",
                  "creator": { "email": "%s" },
                  "eventTitle": "booked by an extra attendee"
                }
                """.formatted(extraAttendee.username().asString()))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_CREATED);

        String unfoldedCalendar = exportCalendar(openPaaSUser).replace("\r\n ", "");

        assertThat(unfoldedCalendar.lines()
            .filter(line -> line.startsWith("ATTENDEE") && line.contains(extraAttendee.username().asString())))
            .describedAs("the booker already is an attendee: no duplicate ATTENDEE with a conflicting PARTSTAT")
            .hasSize(1);
    }

    private BookingLink insertActiveBookingLink(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()),
            DURATION_30_MINUTES,
            AVAILABILITY_RULE);
        return server.getProbe(BookingLinkProbe.class)
            .insert(openPaaSUser.username(), insertRequest);
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
            .contentType(JSON)
            .extract()
            .jsonPath()
            .getList("slots.start", String.class);
    }

    private String exportCalendar(OpenPaaSUser user) {
        return exportCalendar(user, CalendarURL.from(user.id()));
    }

    private String exportCalendar(OpenPaaSUser user, CalendarURL calendarURL) {
        return calDavClient.export(calendarURL, user.username())
            .map(e -> new String(e, StandardCharsets.UTF_8))
            .block();
    }

    private OpenPaaSUser createTestUser(TwakeCalendarGuiceServer server, String prefix) {
        OpenPaaSUser user = sabreDavExtension.newTestUser(Optional.of(prefix + "-"));
        server.getProbe(CalendarDataProbe.class).addUserToRepository(user.username(), PASSWORD);
        return user;
    }

    private CalendarURL createVisibleTeamCalendar(TwakeCalendarGuiceServer server, OpenPaaSUser member, String displayName, String davRight) {
        TeamCalendarId teamCalendarId = createTeamCalendar(server, displayName.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID(), displayName);
        grantMember(server, teamCalendarId, member, davRight);
        return findVisibleTeamCalendar(member, displayName);
    }

    private void upsertCalendarEvent(OpenPaaSUser user, CalendarURL calendarURL, String eventUid, String summary, String dtStart, String dtEnd) {
        davTestHelper.upsertCalendar(user.username(),
            URI.create(calendarURL.asUri().toASCIIString() + "/" + eventUid + ".ics"),
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingReservationTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:%s
            DTEND:%s
            SUMMARY:%s
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, dtStart, dtEnd, summary)).block();
    }

    private List<String> findEventIds(CalendarURL calendarURL) {
        return calDavClient.findUserCalendarEventIds(openPaaSUser.username(), calendarURL)
            .collectList()
            .block();
    }

    private VEvent findEventBySummary(CalendarURL calendarURL, String summary) {
        return findEventBySummary(openPaaSUser, calendarURL, summary);
    }

    private VEvent findEventBySummary(OpenPaaSUser user, CalendarURL calendarURL, String summary) {
        return CalendarTestUtil.toExtractor(exportCalendar(user, calendarURL))
            .extractEventBySummary(summary);
    }

    private TeamCalendarId createTeamCalendar(TwakeCalendarGuiceServer server, String name, String displayName) {
        String payload = """
            {
              "name": "{name}",
              "displayName": "{displayName}"
            }
            """.replace("{name}", name)
            .replace("{displayName}", displayName);

        return new TeamCalendarId(given(webAdminSpecification(server))
            .body(payload)
        .when()
            .post("/domains/{domain}/team-calendars", openPaaSUser.username().getDomainPart().orElseThrow().asString())
        .then()
            .statusCode(201)
            .extract()
            .path("id"));
    }

    private void grantMember(TwakeCalendarGuiceServer server, TeamCalendarId teamCalendarId, OpenPaaSUser member, String davRight) {
        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:{member}",
                    "{davRight}": true
                  }
                ],
                "remove": []
              }
            }
            """.replace("{member}", member.username().asString())
            .replace("{davRight}", davRight);

        given(webAdminSpecification(server))
            .body(payload)
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee",
                member.username().getDomainPart().orElseThrow().asString(), teamCalendarId.value())
        .then()
            .statusCode(204);
    }

    private CalendarURL findVisibleTeamCalendar(OpenPaaSUser user, String displayName) {
        return CALMLY_AWAIT.atMost(Duration.ofSeconds(10))
            .until(() -> calDavClient.findUserCalendarList(user)
                .map(response -> response.findCalendarByName(displayName))
                .block(), Optional::isPresent)
            .get();
    }

    private RequestSpecification webAdminSpecification(TwakeCalendarGuiceServer server) {
        return new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .build();
    }

    private String bodyRequest(String slotStartUtc) {
        return """
            {
              "startUtc": "%s",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [
                {
                  "name": "Nguyen Van A",
                  "email": "vana@example.com"
                }
              ],
              "eventTitle": "30-min intro call",
              "visioLink": false,
              "notes": ""
            }
            """.formatted(slotStartUtc);
    }

    private JsonPath awaitBookingEmails() {
        return awaitMails(2);
    }

    private JsonPath awaitMails(int count) {
        java.util.function.Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
            .get("/smtpMails")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .jsonPath();

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(count));

        return smtpMailsResponseSupplier.get();
    }

    private String messageForRecipient(JsonPath smtpMailsResponse, String recipient) {
        return smtpMailsResponse.getString("[%d].message".formatted(mailIndexOfRecipient(smtpMailsResponse, recipient)));
    }

    private int mailIndexOfRecipient(JsonPath smtpMailsResponse, String recipient) {
        return IntStream.range(0, smtpMailsResponse.getList("").size())
            .filter(index -> recipient.equals(smtpMailsResponse.getString("[%d].recipients[0].address".formatted(index))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Could not find email sent to " + recipient));
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

    private List<String> extractParticipationActionLinks(String html) {
        return extractLinksStartingWith(html, "https://excal.linagora.com/excal/?jwt=");
    }

    private List<String> extractLinksStartingWith(String html, String prefix) {
        List<String> links = new ArrayList<>();
        Pattern pattern = Pattern.compile("href\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        while (matcher.find()) {
            String link = matcher.group(1);
            if (link.startsWith(prefix)) {
                links.add(link);
            }
        }
        return links;
    }

    private String toParticipationEndpoint(String excalLink) {
        String jwt = excalLink.substring("https://excal.linagora.com/excal/?jwt=".length());
        return String.format("http://localhost:%d/calendar/api/calendars/event/participation?jwt=%s",
            restApiPort, URLEncoder.encode(jwt, StandardCharsets.UTF_8));
    }

}
