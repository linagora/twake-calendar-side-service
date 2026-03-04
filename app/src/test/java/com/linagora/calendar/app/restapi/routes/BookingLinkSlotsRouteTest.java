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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

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
import com.linagora.calendar.storage.booking.MemoryBookingLinkDAO;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class BookingLinkSlotsRouteTest {
    private static final String PASSWORD = "secret";
    private static final long MAX_QUERY_RANGE_DAYS = 60;
    private static final Duration DURATION_30_MINUTES = Duration.ofMinutes(30);
    private static final String FROM_20360126 = "2036-01-26T00:00:00Z";
    private static final String TO_20360127 = "2036-01-27T00:00:00Z";
    private static final AvailabilityRules AVAILABILITY_RULE = AvailabilityRules.of(new FixedAvailabilityRule(
        ZonedDateTime.parse("2036-01-26T09:00:00Z"),
        ZonedDateTime.parse("2036-01-26T12:00:00Z")));

    static class BookingLinkSlotsProbe implements GuiceProbe {
        private final BookingLinkDAO bookingLinkDAO;

        @Inject
        BookingLinkSlotsProbe(BookingLinkDAO bookingLinkDAO) {
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
                .to(BookingLinkSlotsProbe.class);

            binder.bind(MemoryBookingLinkDAO.class).in(Scopes.SINGLETON);
            binder.bind(BookingLinkDAO.class).to(MemoryBookingLinkDAO.class);
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser openPaaSUser;
    private DavTestHelper davTestHelper;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        openPaaSUser = sabreDavExtension.newTestUser(Optional.of("owner"));
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(openPaaSUser.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(openPaaSUser.username(), PASSWORD);

        PreemptiveBasicAuthScheme basicAuthScheme = new PreemptiveBasicAuthScheme();
        basicAuthScheme.setUserName(openPaaSUser.username().asString());
        basicAuthScheme.setPassword(PASSWORD);

        int restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .setAuth(basicAuthScheme)
            .build();

        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @Test
    void shouldReturnSlotsWhenRequestIsValid(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return full slots payload for a valid request")
            .isEqualTo("""
                {
                  "durationMinutes": 30,
                  "range": {
                    "from": "2036-01-26T00:00:00Z",
                    "to": "2036-01-27T00:00:00Z"
                  },
                  "slots": [
                    { "start": "2036-01-26T09:00:00Z" },
                    { "start": "2036-01-26T09:30:00Z" },
                    { "start": "2036-01-26T10:00:00Z" },
                    { "start": "2036-01-26T10:30:00Z" },
                    { "start": "2036-01-26T11:00:00Z" },
                    { "start": "2036-01-26T11:30:00Z" }
                  ]
                }
                """);
    }

    @Test
    void shouldExcludeBusyIntervalsFromReturnedSlots(TwakeCalendarGuiceServer server) {
        // Given: an active booking link with a 09:00-12:00 availability window
        // and two opaque busy events that overlap expected 30-minute slots.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        // Given busy interval [09:30, 10:00): this should exclude slot starting at 09:30.
        String firstUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingSlotsTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DTEND:20360126T100000Z
            SUMMARY:busy-1x
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(firstUid), firstUid);

        // Given busy interval [11:00, 11:30): this should exclude slot starting at 11:00.
        String secondUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingSlotsTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T110000Z
            DTEND:20360126T113000Z
            SUMMARY:busy-2
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(secondUid), secondUid);

        // When: requesting available slots for that date range via the booking slots endpoint.
        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should exclude slots that overlap owner's busy intervals")
            .inPath("$.slots")
            .isEqualTo("""
                [
                  { "start": "2036-01-26T09:00:00Z" },
                  { "start": "2036-01-26T10:00:00Z" },
                  { "start": "2036-01-26T10:30:00Z" },
                  { "start": "2036-01-26T11:30:00Z" }
                ]
                """);
    }

    @Test
    void shouldExcludeOnlyCurrentUserBusyIntervalsFromReturnedSlots(TwakeCalendarGuiceServer server) {
        // Given: two users, where both have busy events at the same period,
        // but only user A owns the booking link being queried.
        OpenPaaSUser otherUser = createTestUser(server, "other");
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        // Given owner busy interval [09:30, 10:00): this should exclude owner slot starting at 09:30.
        String ownerBusyUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingSlotsTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T093000Z
            DTEND:20360126T100000Z
            SUMMARY:owner-busy
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(ownerBusyUid), ownerBusyUid);

        // Given other-user busy interval [09:00, 12:00): this must not affect owner's returned slots.
        String otherBusyUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(otherUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingSlotsTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T090000Z
            DTEND:20360126T120000Z
            SUMMARY:other-user-busy
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(otherBusyUid), otherBusyUid);

        // When: user A requests slots for user A's booking link.
        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should only apply owner's busy intervals and ignore another user's busy intervals")
            .inPath("$.slots")
            .isEqualTo("""
                [
                  { "start": "2036-01-26T09:00:00Z" },
                  { "start": "2036-01-26T10:00:00Z" },
                  { "start": "2036-01-26T10:30:00Z" },
                  { "start": "2036-01-26T11:00:00Z" },
                  { "start": "2036-01-26T11:30:00Z" }
                ]
                """);
    }

    @Test
    void shouldComputeSlotsWhenQueryRangeContainsHourMinute(TwakeCalendarGuiceServer server) {
        // Given a booking link with fixed availability 09:00-12:00 and 30-minute duration.
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        // Given query range containing HH:mm precision instead of full-day boundaries.
        String from = "2036-01-26T09:00:00Z";
        String to = "2036-01-26T10:45:00Z";

        // When querying slots with hour-minute range.
        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", from)
            .queryParam("to", to)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        // Then computed slots respect the hour-minute request boundaries.
        assertThatJson(response)
            .describedAs("should compute slots correctly when query range has hour-minute precision")
            .inPath("$.slots")
            .isEqualTo("""
                [
                  { "start": "2036-01-26T09:00:00Z" },
                  { "start": "2036-01-26T09:30:00Z" },
                  { "start": "2036-01-26T10:00:00Z" }
                ]
                """);
    }

    @Test
    void shouldExcludeSlotsOverlappingNonRoundedBusyIntervals(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String firstUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingSlotsTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T094000Z
            DTEND:20360126T101000Z
            SUMMARY:owner-busy-non-aligned-1
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(firstUid), firstUid);

        String secondUid = UUID.randomUUID().toString();
        davTestHelper.upsertCalendar(openPaaSUser, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//BookingSlotsTest//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20360101T000000Z
            DTSTART:20360126T110500Z
            DTEND:20360126T112000Z
            SUMMARY:owner-busy-non-aligned-2
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
            """.formatted(secondUid), secondUid);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
            .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
            .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should exclude slots overlapping busy intervals with non-rounded boundaries")
            .inPath("$.slots")
            .isEqualTo("""
                [
                  { "start": "2036-01-26T09:00:00Z" },
                  { "start": "2036-01-26T10:10:00Z" },
                  { "start": "2036-01-26T11:20:00Z" }
                ]
                """);
    }

    @Test
    void shouldReturnBadRequestWhenRangeExceedsMaximumAllowedDays(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", "2036-01-01T00:00:00Z")
            .queryParam("to", "2036-03-05T00:00:00Z")
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return bad request when requested range exceeds maximum allowed days")
            .isEqualTo("""
                {
                    "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "details": "Requested range is too large, max is %s days"
                    }
                }""".formatted(MAX_QUERY_RANGE_DAYS));
    }

    @Test
    void shouldReturnEmptySlotsWhenNoAvailabilityInRequestedRange(TwakeCalendarGuiceServer server) {
        AvailabilityRules anotherDayRule = AvailabilityRules.of(new FixedAvailabilityRule(
            ZonedDateTime.parse("2036-01-25T09:00:00Z"),
            ZonedDateTime.parse("2036-01-25T12:00:00Z")));
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, anotherDayRule);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return empty slots when no availability matches requested range")
            .inPath("$.slots")
            .isEqualTo("[]");
    }

    @Test
    void shouldUseFixedRuleFromQueryRangeWhenAvailabilityRulesIsAbsent(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, BookingLinkInsertRequest.ACTIVE, Optional.empty());
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", "2036-01-26T00:00:00Z")
            .queryParam("to", "2036-01-26T02:00:00Z")
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should compute slots from query range when availability rules are absent")
            .inPath("$.slots")
            .isEqualTo("""
                [
                  { "start": "2036-01-26T00:00:00Z" },
                  { "start": "2036-01-26T00:30:00Z" },
                  { "start": "2036-01-26T01:00:00Z" },
                  { "start": "2036-01-26T01:30:00Z" }
                ]
                """);
    }

    @Test
    void shouldReturnBadRequestWhenFromQueryParamIsMissing(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return bad request when 'from' query parameter is missing")
            .isEqualTo("""
                {
                    "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "details": "Missing or invalid query parameter 'from'. Expected ISO-8601 instant format, e.g. 2007-12-03T10:15:30.00Z"
                    }
                }""");
    }

    @Test
    void shouldReturnBadRequestWhenToQueryParamIsMissing(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return bad request when 'to' query parameter is missing")
            .isEqualTo("""
                {
                    "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "details": "Missing or invalid query parameter 'to'. Expected ISO-8601 instant format, e.g. 2007-12-03T10:15:30.00Z"
                    }
                }""");
    }

    @Test
    void shouldReturnBadRequestWhenFromIsInvalidInstant(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", "invalid")
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return bad request when 'from' is not a valid instant")
            .isEqualTo("""
                {
                    "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "details": "Missing or invalid query parameter 'from'. Expected ISO-8601 instant format, e.g. 2007-12-03T10:15:30.00Z"
                    }
                }""");
    }

    @Test
    void shouldReturnBadRequestWhenToIsInvalidInstant(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", "invalid")
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return bad request when 'to' is not a valid instant")
            .isEqualTo("""
                {
                    "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "details": "Missing or invalid query parameter 'to'. Expected ISO-8601 instant format, e.g. 2007-12-03T10:15:30.00Z"
                    }
                }""");
    }

    @Test
    void shouldReturnBadRequestWhenToIsNotAfterFrom(TwakeCalendarGuiceServer server) {
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", FROM_20360126)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return bad request when 'to' is not after 'from'")
            .isEqualTo("""
                {
                    "error": {
                        "code": 400,
                        "message": "Bad Request",
                        "details": "'to' must be after 'from'"
                    }
                }""");
    }

    @Test
    void shouldReturnNotFoundWhenBookingLinkPublicIdDoesNotExist() {
        String response = given()
            .pathParam("bookingLinkPublicId", "missing-public-id")
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return not found when booking link public id does not exist")
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not Found",
                        "details": "Cannot find booking link with publicId missing-public-id"
                    }
                }""");
    }

    @Test
    void shouldReturnNotFoundWhenBookingLinkBelongsToAnotherUser(TwakeCalendarGuiceServer server) {
        OpenPaaSUser otherUser = createTestUser(server, "other");
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(otherUser.id()), DURATION_30_MINUTES, AVAILABILITY_RULE);
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(otherUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return not found when booking link belongs to another user")
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not Found",
                        "details": "Cannot find booking link with publicId %s"
                    }
                }""".formatted(inserted.publicId().value()));
    }

    @Test
    void shouldReturnNotFoundWhenBookingLinkIsInactive(TwakeCalendarGuiceServer server) {
        boolean inactive = false;
        BookingLinkInsertRequest insertRequest = new BookingLinkInsertRequest(CalendarURL.from(openPaaSUser.id()), DURATION_30_MINUTES, inactive, Optional.of(AVAILABILITY_RULE));
        BookingLink inserted = server.getProbe(BookingLinkSlotsProbe.class).insert(openPaaSUser.username(), insertRequest);

        String response = given()
            .pathParam("bookingLinkPublicId", inserted.publicId().value())
            .queryParam("from", FROM_20360126)
            .queryParam("to", TO_20360127)
        .when()
            .get("/calendar/api/booking-links/{bookingLinkPublicId}/slots")
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .describedAs("should return not found when booking link is inactive")
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not Found",
                        "details": "Cannot find booking link with publicId %s"
                    }
                }""".formatted(inserted.publicId().value()));
    }

    private OpenPaaSUser createTestUser(TwakeCalendarGuiceServer server, String prefix) {
        OpenPaaSUser user = sabreDavExtension.newTestUser(Optional.of(prefix + "-"));

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addUserToRepository(user.username(), PASSWORD);
        return user;
    }
}
