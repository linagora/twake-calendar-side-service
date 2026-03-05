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
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.JsonMatchers.jsonEquals;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.Scopes;
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
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.storage.booking.MemoryBookingLinkDAO;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkReservationRouteTest {
    private static final String PASSWORD = "secret";
    private static final Duration DURATION_30_MINUTES = Duration.ofMinutes(30);
    private static final AvailabilityRules AVAILABILITY_RULE = AvailabilityRules.of(new FixedAvailabilityRule(
        ZonedDateTime.parse("2036-01-26T09:00:00Z"),
        ZonedDateTime.parse("2036-01-26T12:00:00Z")));

    static class BookingLinkReservationProbe implements GuiceProbe {
        private final BookingLinkDAO bookingLinkDAO;

        @Inject
        BookingLinkReservationProbe(BookingLinkDAO bookingLinkDAO) {
            this.bookingLinkDAO = bookingLinkDAO;
        }

        BookingLink insert(Username username, BookingLinkInsertRequest request) {
            return bookingLinkDAO.insert(username, request).block();
        }
    }

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
                .to(BookingLinkReservationProbe.class);

            binder.bind(MemoryBookingLinkDAO.class).in(Scopes.SINGLETON);
            binder.bind(BookingLinkDAO.class).to(MemoryBookingLinkDAO.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;
    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        openPaaSUser = sabreDavExtension.newTestUser(Optional.of("owner"));
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);

        int restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .build();

        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
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

        String unfoldedCalendar = exportCalendar(openPaaSUser)
            .replace("\r\n ", "");
        assertThat(unfoldedCalendar)
            .describedAs("exported ICS should include expected public-booking properties and attendees")
            .containsSubsequence(
                "TRANSP:OPAQUE",
                "SUMMARY:30-min intro call",
                "DTSTART:20360126T093000Z",
                "DURATION:PT30M",
                "ORGANIZER;CN=BOB;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com",
                "ATTENDEE;CN=BOB;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:creator@example.com",
                "ATTENDEE;CN=Nguyen Van A;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION:mailto:vana@example.com",
                "DESCRIPTION:Please call via Zoom.",
                "X-PUBLICLY-CREATED:true",
                "X-PUBLICLY-CREATOR:creator@example.com",
                "X-OPENPAAS-VIDEOCONFERENCE:https://jitsi.linagora.com");
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
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "message": "Bad Request",
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
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "message": "Bad Request",
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
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "message": "Bad Request",
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
            .contains("SUMMARY:optional fields omitted")
            .contains("ORGANIZER;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=:mailto:creator@example.com")
            .contains("ATTENDEE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;PARTSTAT=NEEDS-ACTION;CN=:mailto:creator@example.com")
            .doesNotContain("X-OPENPAAS-VIDEOCONFERENCE")
            .doesNotContain("DESCRIPTION:");
    }

    @Test
    void shouldReturnBadRequestWhenStartUtcDoesNotMatchAnyAvailableSlot(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES,
            AvailabilityRules.of(new FixedAvailabilityRule(
                ZonedDateTime.parse("2036-01-26T09:00:00Z"),
                ZonedDateTime.parse("2036-01-26T10:00:00Z"))));
        BookingLink inserted = server.getProbe(BookingLinkReservationProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T08:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "message": "Bad Request",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc 2036-01-26T08:00:00Z"
                }
                """.formatted(inserted.publicId().value())));
    }

    @Test
    void shouldReturnBadRequestWhenRequestedSlotIsBusy(TwakeCalendarGuiceServer server) {
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
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 400,
                  "message": "Bad Request",
                  "details": "Requested slot is not available for booking link with publicId %s, slotStartUtc 2036-01-26T09:30:00Z"
                }
                """.formatted(inserted.publicId().value())));
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
                  "message": "Bad Request",
                  "details": "'eventNote' must not exceed 2000 characters"
                }
                """));
    }

    @Test
    void shouldReturnNotFoundWhenBookingLinkIsInactive(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()),
            DURATION_30_MINUTES,
            false,
            Optional.of(AVAILABILITY_RULE));
        BookingLink inserted = server.getProbe(BookingLinkReservationProbe.class)
            .insert(openPaaSUser.username(), insertRequest);

        given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .body(bodyRequest("2036-01-26T09:00:00Z"))
        .when()
            .post("/api/booking-links/{bookingLinkPublicId}/book")
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .body("error", jsonEquals("""
                {
                  "code": 404,
                  "message": "Not Found",
                  "details": "Cannot find booking link with publicId %s"
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
                  "message": "Not Found",
                  "details": "Cannot find booking link with publicId %s"
                }
                """.formatted(notFoundPublicId)));
    }

    private BookingLink insertActiveBookingLink(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(
            CalendarURL.from(openPaaSUser.id()),
            DURATION_30_MINUTES,
            AVAILABILITY_RULE);
        return server.getProbe(BookingLinkReservationProbe.class)
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
        return calDavClient.export(CalendarURL.from(user.id()), user.username())
            .map(e -> new String(e, StandardCharsets.UTF_8))
            .block();
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

}
