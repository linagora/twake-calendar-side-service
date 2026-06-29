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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.net.ssl.SSLException;

import org.apache.james.core.Username;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
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
import com.linagora.calendar.webadmin.task.BookingLinkEventDeletionTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;

public class BookingLinkEventDeletionTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private MongoDBBookingLinkDAO bookingLinkDAO;
    private CalDavClient calDavClient;
    private OpenPaaSUser user;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        bookingLinkDAO = new MongoDBBookingLinkDAO(mongoDB, Clock.system(UTC));

        user = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        BookingLinkEventDeletionService eventDeletionService = new BookingLinkEventDeletionService(calDavClient);

        webAdminServer = WebAdminUtils.createWebAdminServer(
                new BookingLinkUserRoutes(userDAO, bookingLinkDAO, calDavClient, taskManager, eventDeletionService, new JsonTransformer()),
                new TasksRoutes(taskManager,
                    new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(BookingLinkEventDeletionTaskAdditionalInformationDTO.module())
                        .build())))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void deleteEventsShouldDeleteEventsOfTheBookingLink() {
        BookingLink bookingLink = insertBookingLink();
        Instant now = Instant.now();

        importBookingLinkEvent("evt-1", bookingLink.publicId(), now);
        importBookingLinkEvent("evt-2", bookingLink.publicId(), now);
        importEventWithoutBookingLink("evt-other", now);

        String taskId = given()
            .queryParam("action", "deleteEvents")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("type", is("booking-link-event-deletion"))
            .body("additionalInformation.deletedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.bookingLinkPublicId", is(bookingLink.publicId().value().toString()))
            .body("additionalInformation.timestamp", is(notNullValue()));

        assertThat(listEvents(CalendarURL.from(user.id())))
            .doesNotContain("UID:evt-1")
            .doesNotContain("UID:evt-2")
            .contains("UID:evt-other");
    }

    @Test
    void deleteEventsShouldDeleteAllEventsWhenManyEvents() {
        BookingLink bookingLink = insertBookingLink();
        Instant now = Instant.now();

        int eventCount = 10;
        for (int i = 0; i < eventCount; i++) {
            importBookingLinkEvent("evt-" + i, bookingLink.publicId(), now);
        }
        importEventWithoutBookingLink("evt-other", now);

        String taskId = given()
            .queryParam("action", "deleteEvents")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.deletedEventCount", is(eventCount))
            .body("additionalInformation.failedEventCount", is(0));

        String remainingEvents = listEvents(CalendarURL.from(user.id()));
        for (int i = 0; i < eventCount; i++) {
            assertThat(remainingEvents).doesNotContain("UID:evt-" + i);
        }
        assertThat(remainingEvents).contains("UID:evt-other");
    }

    @Test
    void deleteEventsShouldOnlyDeleteEventsSinceWhenProvided() {
        BookingLink bookingLink = insertBookingLink();
        Instant fixedNow = Instant.parse("2026-01-10T00:00:00Z");

        importBookingLinkEvent("evt-old", bookingLink.publicId(), fixedNow.minus(10, ChronoUnit.DAYS));
        importBookingLinkEvent("evt-new", bookingLink.publicId(), fixedNow.plus(1, ChronoUnit.DAYS));

        String taskId = given()
            .queryParam("action", "deleteEvents")
            .queryParam("since", "2026-01-09T00:00:00Z")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.deletedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.since", is("2026-01-09T00:00:00Z"));

        assertThat(listEvents(CalendarURL.from(user.id())))
            .contains("UID:evt-old")
            .doesNotContain("UID:evt-new");
    }

    @Test
    void deleteEventsShouldReturnCompletedTaskWhenNoEventMatches() {
        BookingLink bookingLink = insertBookingLink();

        String taskId = given()
            .queryParam("action", "deleteEvents")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(201)
            .extract().jsonPath().getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.deletedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0));
    }

    @Test
    void deleteEventsShouldReturn404WhenUnknownPublicId() {
        given()
            .queryParam("action", "deleteEvents")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), java.util.UUID.randomUUID().toString())
        .then()
            .statusCode(404);
    }

    @Test
    void deleteEventsShouldReturn400WhenUnknownAction() {
        BookingLink bookingLink = insertBookingLink();

        given()
            .queryParam("action", "unknown")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(400);
    }

    @Test
    void deleteEventsShouldReturn400WhenInvalidSince() {
        BookingLink bookingLink = insertBookingLink();

        given()
            .queryParam("action", "deleteEvents")
            .queryParam("since", "not-an-instant")
        .when()
            .post("/users/{username}/booking-links/{publicId}", user.username().asString(), bookingLink.publicId().value().toString())
        .then()
            .statusCode(400);
    }

    private BookingLink insertBookingLink() {
        return bookingLinkDAO.insert(user.username(),
            new BookingLinkInsertRequest(CalendarURL.from(user.id()), Duration.ofMinutes(30),
                new AvailabilityRules(List.of(new WeeklyAvailabilityRule(java.time.DayOfWeek.MONDAY,
                    java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0), UTC)))))
            .block();
    }

    private void importBookingLinkEvent(String uid, BookingLinkPublicId bookingLinkPublicId, Instant dtStamp) {
        importEvent(uid, dtStamp, bookingLinkPublicId.value().toString());
    }

    private void importEventWithoutBookingLink(String uid, Instant dtStamp) {
        importEvent(uid, dtStamp, null);
    }

    private void importEvent(String uid, Instant dtStamp, String bookingLinkId) {
        String bookingLinkProperty = bookingLinkId == null ? "" : "X-OPENPAAS-BOOKING-LINK:" + bookingLinkId + "\n";
        String ics = ("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:{dtstamp}
            DTSTART:{dtstart}
            DTEND:{dtend}
            {bookingLink}SUMMARY:Booking event
            END:VEVENT
            END:VCALENDAR
            """)
            .replace("{uid}", uid)
            .replace("{dtstamp}", formatInstant(dtStamp))
            .replace("{dtstart}", formatInstant(dtStamp))
            .replace("{dtend}", formatInstant(dtStamp.plus(1, ChronoUnit.HOURS)))
            .replace("{bookingLink}", bookingLinkProperty);

        calDavClient.importCalendar(CalendarURL.from(user.id()), uid, user.username(), ics.getBytes(StandardCharsets.UTF_8))
            .block();
    }

    private String listEvents(CalendarURL calendar) {
        return calDavClient.export(user.username(), calendar.asUri())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

    private String formatInstant(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(instant);
    }
}
