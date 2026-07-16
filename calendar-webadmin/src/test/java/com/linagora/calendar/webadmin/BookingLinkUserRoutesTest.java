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

package com.linagora.calendar.webadmin;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static java.time.ZoneOffset.UTC;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.routes.BookingLinkExtraAttendeeResolver;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.storage.mongodb.MongoDBBookingLinkDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.BookingLinkEventDeletionService;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;

public class BookingLinkUserRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private MongoDBBookingLinkDAO bookingLinkDAO;
    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;
    private OpenPaaSUser user;
    private OpenPaaSUser otherUser;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        bookingLinkDAO = new MongoDBBookingLinkDAO(mongoDB, Clock.system(UTC));

        user = sabreDavExtension.newTestUser();
        otherUser = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        BookingLinkEventDeletionService eventDeletionService =
            new BookingLinkEventDeletionService(calDavClient);

        webAdminServer = WebAdminUtils.createWebAdminServer(
            new BookingLinkUserRoutes(userDAO, bookingLinkDAO, calDavClient, taskManager, eventDeletionService,
                new BookingLinkExtraAttendeeResolver(userDAO), new JsonTransformer()))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    private String defaultCalendarUrl(OpenPaaSUser user) {
        return CalendarURL.from(user.id()).asUri().toString();
    }

    private BookingLink insertBookingLink(OpenPaaSUser user) {
        return bookingLinkDAO.insert(user.username(),
            new BookingLinkInsertRequest(CalendarURL.from(user.id()), Duration.ofMinutes(30),
                new AvailabilityRules(List.of(new WeeklyAvailabilityRule(java.time.DayOfWeek.MONDAY,
                    java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0), UTC)))))
            .block();
    }

    @Test
    void listShouldReturnEmptyByDefault() {
        given()
        .when()
            .get("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(200)
            .body("size()", is(0));
    }

    @Test
    void listShouldReturnUserBookingLinks() {
        BookingLink bookingLink = insertBookingLink(user);

        String response = given()
        .when()
            .get("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThatJson(response).isArray().hasSize(1);
        assertThatJson(response).node("[0].publicId").isEqualTo(bookingLink.publicId().value().toString());
    }

    @Test
    void listShouldReturn404WhenUserDoesNotExist() {
        given()
        .when()
            .get("/users/{username}/booking-links", "ghost@linagora.com")
        .then()
            .statusCode(404)
            .body("message", is("User does not exist"));
    }

    @Test
    void listShouldReturn400WhenInvalidUsername() {
        given()
        .when()
            .get("/users/{username}/booking-links", "inva@lid@user")
        .then()
            .statusCode(400);
    }

    @Test
    void getShouldReturnBookingLink() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
        .when()
            .get("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(200)
            .body("publicId", is(bookingLink.publicId().value().toString()))
            .body("durationMinutes", is(30))
            .body("active", is(true));
    }

    @Test
    void getShouldReturn404WhenUnknownPublicId() {
        given()
        .when()
            .get("/users/{username}/booking-links/{publicId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404);
    }

    @Test
    void getShouldReturn400WhenInvalidPublicId() {
        given()
        .when()
            .get("/users/{username}/booking-links/{publicId}", user.username().asString(), "not-a-uuid")
        .then()
            .statusCode(400);
    }

    @Test
    void getShouldReturn404WhenBookingLinkBelongsToAnotherUser() {
        BookingLink bookingLink = insertBookingLink(otherUser);

        given()
        .when()
            .get("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(404);
    }

    @Test
    void createShouldStoreBookingLinkAndReturnPublicId() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 45,
                    "active": true
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201)
            .body("bookingLinkPublicId", not(is("")))
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkDAO.findByPublicId(user.username(), new BookingLinkPublicId(UUID.fromString(publicId))).block();
        assertThat(stored).isNotNull();
        assertThat(stored.duration()).isEqualTo(Duration.ofMinutes(45));
        assertThat(stored.calendarUrl()).isEqualTo(CalendarURL.from(user.id()));
    }

    @Test
    void createShouldStoreExtraAttendees() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 45,
                    "active": true,
                    "extraAttendees": ["%s"]
                }
                """.formatted(defaultCalendarUrl(user), otherUser.id().value()))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkDAO.findByPublicId(user.username(), new BookingLinkPublicId(UUID.fromString(publicId))).block();
        assertThat(stored.extraAttendees()).containsExactly(otherUser.id());
    }

    @Test
    void createShouldReturn400WhenExtraAttendeeDoesNotExist() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 45,
                    "active": true,
                    "extraAttendees": ["659387b9d486dc0046aeffff"]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn400WhenCalendarUrlMissing() {
        given()
            .body("""
                { "durationMinutes": 30, "active": true }
                """)
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn400WhenCalendarDoesNotExist() {
        given()
            .body("""
                {
                    "calendarUrl": "/calendars/%s/%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(user.id().value(), UUID.randomUUID()))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn403WhenCalendarIsReadOnlyDelegated() {
        CalendarURL ownerCalendar = CalendarURL.from(otherUser.id());
        davTestHelper.grantDelegation(otherUser, ownerCalendar, user, "dav:read");
        CalendarURL delegatedCalendar = findMirrorCalendar(user);

        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(delegatedCalendar.asUri().toString()))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(403);

        assertThat(bookingLinkDAO.findByUsername(user.username()).collectList().block()).isEmpty();
    }

    @Test
    void createShouldReturn201WhenCalendarIsReadWriteDelegated() {
        CalendarURL ownerCalendar = CalendarURL.from(otherUser.id());
        davTestHelper.grantDelegation(otherUser, ownerCalendar, user, "dav:read-write");
        CalendarURL delegatedCalendar = findMirrorCalendar(user);

        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(delegatedCalendar.asUri().toString()))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201);
    }

    @Test
    void createShouldReturn201WhenCalendarIsAdministrationDelegated() {
        CalendarURL ownerCalendar = CalendarURL.from(otherUser.id());
        davTestHelper.grantDelegation(otherUser, ownerCalendar, user, "dav:administration");
        CalendarURL delegatedCalendar = findMirrorCalendar(user);

        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true
                }
                """.formatted(delegatedCalendar.asUri().toString()))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201);
    }

    private CalendarURL findMirrorCalendar(OpenPaaSUser user) {
        return Fixture.awaitAtMost.until(() -> calDavClient.findUserCalendarList(user)
            .map(response -> response.calendars()
                .keySet()
                .stream()
                .filter(calendarURL -> !calendarURL.equals(CalendarURL.from(user.id())))
                .findFirst())
            .block(), Optional::isPresent).get();
    }

    @Test
    void patchShouldUpdateBookingLink() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
            .body("""
                { "durationMinutes": 60, "active": false }
                """)
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(204);

        BookingLink updated = bookingLinkDAO.findByPublicId(user.username(), bookingLink.publicId()).block();
        assertThat(updated.duration()).isEqualTo(Duration.ofMinutes(60));
        assertThat(updated.active()).isFalse();
    }

    @Test
    void createShouldStoreAutoAccept() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "autoAccept": true
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("bookingLinkPublicId");

        BookingLink stored = bookingLinkDAO.findByPublicId(user.username(), new BookingLinkPublicId(UUID.fromString(publicId))).block();
        assertThat(stored.autoAccept()).isTrue();
    }

    @Test
    void patchShouldUpdateAutoAccept() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
            .body("""
                { "autoAccept": true }
                """)
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(204);

        BookingLink updated = bookingLinkDAO.findByPublicId(user.username(), bookingLink.publicId()).block();
        assertThat(updated.autoAccept()).isTrue();
    }

    @Test
    void patchShouldReturn404WhenUnknownPublicId() {
        given()
            .body("""
                { "active": false }
                """)
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404);
    }

    @Test
    void patchShouldReturn400WhenEmptyPatch() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
            .body("{}")
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(400);
    }

    @Test
    void deleteShouldRemoveBookingLink() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
        .when()
            .delete("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(204);

        assertThat(bookingLinkDAO.findByPublicId(user.username(), bookingLink.publicId()).block()).isNull();
    }

    @Test
    void deleteShouldReturn404WhenUnknownPublicId() {
        given()
        .when()
            .delete("/users/{username}/booking-links/{publicId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404);
    }

    @Test
    void resetShouldGenerateNewPublicId() {
        BookingLink bookingLink = insertBookingLink(user);

        String newPublicId = given()
        .when()
            .post("/users/{username}/booking-links/{publicId}/reset", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("bookingLinkPublicId");

        assertThat(newPublicId).isNotEqualTo(bookingLink.publicId().value().toString());
        assertThat(bookingLinkDAO.findByPublicId(user.username(), bookingLink.publicId()).block()).isNull();
        assertThat(bookingLinkDAO.findByPublicId(user.username(), new BookingLinkPublicId(UUID.fromString(newPublicId))).block()).isNotNull();
    }

    @Test
    void resetShouldReturn404WhenUnknownPublicId() {
        given()
        .when()
            .post("/users/{username}/booking-links/{publicId}/reset", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404);
    }

    private String getBookingLinkJson(String publicId) {
        return given()
        .when()
            .get("/users/{username}/booking-links/{publicId}", user.username().asString(), publicId)
        .then()
            .statusCode(200)
            .extract().body().asString();
    }

    @Test
    void createShouldStoreComplexAvailabilityRules() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Europe/Paris" },
                        { "type": "weekly", "dayOfWeek": "WED", "start": "13:00", "end": "17:00", "timeZone": "Europe/London" },
                        { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "timeZone": "UTC" }
                    ]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("bookingLinkPublicId");

        assertThatJson(getBookingLinkJson(publicId))
            .node("availabilityRules")
            .isEqualTo("""
                [
                    { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Europe/Paris" },
                    { "type": "weekly", "dayOfWeek": "WED", "start": "13:00", "end": "17:00", "timeZone": "Europe/London" },
                    { "type": "fixed", "start": "2026-01-26T02:00:00", "end": "2026-01-30T02:00:00", "timeZone": "UTC" }
                ]
                """);
    }

    @Test
    void createShouldDefaultRuleTimeZoneToUtcWhenOmitted() {
        String publicId = given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "17:00" }
                    ]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("bookingLinkPublicId");

        assertThatJson(getBookingLinkJson(publicId))
            .node("availabilityRules[0].timeZone")
            .isEqualTo("UTC");
    }

    @Test
    void patchShouldReplaceAvailabilityRules() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
            .body("""
                {
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "FRI", "start": "14:00", "end": "18:00", "timeZone": "Europe/Paris" }
                    ]
                }
                """)
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(204);

        assertThatJson(getBookingLinkJson(bookingLink.publicId().value().toString()))
            .node("availabilityRules")
            .isEqualTo("""
                [
                    { "type": "weekly", "dayOfWeek": "FRI", "start": "14:00", "end": "18:00", "timeZone": "Europe/Paris" }
                ]
                """);
    }

    @Test
    void patchShouldRemoveAvailabilityRulesWhenNull() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
            .body("""
                { "availabilityRules": null }
                """)
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(204);

        assertThatJson(getBookingLinkJson(bookingLink.publicId().value().toString()))
            .node("availabilityRules")
            .isAbsent();
        assertThat(bookingLinkDAO.findByPublicId(user.username(), bookingLink.publicId()).block().availabilityRules())
            .isEmpty();
    }

    @Test
    void patchShouldReturn400WhenAvailabilityRulesEmptyArray() {
        BookingLink bookingLink = insertBookingLink(user);

        given()
            .body("""
                { "availabilityRules": [] }
                """)
        .when()
            .patch("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn400WhenUnknownRuleType() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "type": "monthly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00" }
                    ]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn400WhenWeeklyRuleMissingDayOfWeek() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "type": "weekly", "start": "09:00", "end": "12:00" }
                    ]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn400WhenInvalidTimeZone() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "type": "weekly", "dayOfWeek": "MON", "start": "09:00", "end": "12:00", "timeZone": "Invalid/Zone" }
                    ]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }

    @Test
    void createShouldReturn400WhenFixedRuleStartAfterEnd() {
        given()
            .body("""
                {
                    "calendarUrl": "%s",
                    "durationMinutes": 30,
                    "active": true,
                    "availabilityRules": [
                        { "type": "fixed", "start": "2026-01-30T02:00:00", "end": "2026-01-26T02:00:00", "timeZone": "UTC" }
                    ]
                }
                """.formatted(defaultCalendarUrl(user)))
        .when()
            .post("/users/{username}/booking-links", user.username().asString())
        .then()
            .statusCode(400);
    }
}
