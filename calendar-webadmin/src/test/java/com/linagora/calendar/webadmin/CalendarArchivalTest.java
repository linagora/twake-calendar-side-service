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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import javax.net.ssl.SSLException;

import org.apache.james.core.Username;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.CalendarEventArchivalService;
import com.linagora.calendar.webadmin.task.CalendarArchivalTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;

public class CalendarArchivalTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private CalDavClient calDavClient;
    private CalendarEventArchivalService archivalService;
    private UpdatableTickingClock clock;

    private OpenPaaSUser userA;
    private OpenPaaSUser userB;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        clock = new UpdatableTickingClock(Instant.now());
        archivalService = new CalendarEventArchivalService(calDavClient, userDAO);

        this.userA = sabreDavExtension.newTestUser();
        this.userB = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));

        webAdminServer = WebAdminUtils.createWebAdminServer(new CalendarRoutes(new JsonTransformer(),
                taskManager,
                ImmutableSet.of(new CalendarRoutes.ArchiveRequestToTask(archivalService, clock)),
                ImmutableSet.of(new CalendarRoutes.UserArchiveRequestToTask(archivalService, clock, userDAO))),
            new TasksRoutes(taskManager,
                new JsonTransformer(),
                new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                    .add(CalendarArchivalTaskAdditionalInformationDTO.module())
                    .build()))).start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void calendarArchivalAllShouldReturnCompletedTaskDetailsByDefault() {
        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("masterDtStartBefore", "1y")
        .when()
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("calendar-archival"))
            .body("additionalInformation.archivedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()))
            .body("completedDate", is(notNullValue()));
    }

    @Test
    void calendarArchivalAllShouldArchiveAcrossMultipleUsers() {
        // User 1: old + new events
        Instant oldEventUser1 = clock.instant().minus(365, ChronoUnit.DAYS);
        Instant newEventUser1 = clock.instant().plus(1, ChronoUnit.DAYS);
        insertEvent(userA, "u1-old", oldEventUser1);
        insertEvent(userA, "u1-new", newEventUser1);

        // User 2: old + new events
        Instant oldEventUser2 = clock.instant().minus(200, ChronoUnit.DAYS);
        Instant newEventUser2 = clock.instant().plus(2, ChronoUnit.DAYS);
        insertEvent(userB, "u2-old", oldEventUser2);
        insertEvent(userB, "u2-new", newEventUser2);

        // User 3: no events
        OpenPaaSUser openPaaSUser3 = sabreDavExtension.newTestUser();

        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("masterDtStartBefore", "6m")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("additionalInformation.archivedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));

        // User 1 assertions
        CalendarURL archivalCalendarUser1 = findArchivalCalendar(userA);
        CalendarURL defaultCalendarUser1 = CalendarURL.from(userA.id());

        assertThat(listEvents(userA.username(), archivalCalendarUser1))
            .contains("UID:u1-old")
            .doesNotContain("UID:u1-new");

        assertThat(listEvents(userA.username(), defaultCalendarUser1))
            .doesNotContain("UID:u1-old")
            .contains("UID:u1-new");

        // User 2 assertions
        CalendarURL archivalCalendarUser2 = findArchivalCalendar(userB);
        CalendarURL defaultCalendarUser2 = CalendarURL.from(userB.id());

        assertThat(listEvents(userB.username(), archivalCalendarUser2))
            .contains("UID:u2-old")
            .doesNotContain("UID:u2-new");

        assertThat(listEvents(userB.username(), defaultCalendarUser2))
            .doesNotContain("UID:u2-old")
            .contains("UID:u2-new");
    }

    @Test
    void calendarArchivalAllShouldSupportCreatedBeforeParameter() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);
        Instant oldCreated = fixedNow.minus(10, ChronoUnit.DAYS);
        Instant newCreated = fixedNow.plus(1, ChronoUnit.DAYS);

        insertEventWithCreated(userA, "old", oldCreated);
        insertEventWithCreated(userA, "new", newCreated);

        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("createdBefore", "5d")
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.createdBefore", is("2025-12-27T00:00:00Z"));

        CalendarURL archival = findArchivalCalendar(userA);
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());

        assertThat(listEvents(userA.username(), archival))
            .contains("UID:old")
            .doesNotContain("UID:new");

        assertThat(listEvents(userA.username(), defaultCalendar))
            .doesNotContain("UID:old")
            .contains("UID:new");
    }

    @Test
    void calendarArchivalAllShouldSupportLastModifiedBeforeParameter() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        Instant oldLastModified = fixedNow.minus(10, ChronoUnit.DAYS);
        Instant newLastModified = fixedNow.plus(1, ChronoUnit.DAYS);

        insertEventWithLastModified(userA, "old", oldLastModified);
        insertEventWithLastModified(userA, "new", newLastModified);

        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("lastModifiedBefore", "5d")
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.lastModifiedBefore", is("2025-12-27T00:00:00Z"));

        CalendarURL archival = findArchivalCalendar(userA);
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());

        // Archival calendar: only matched event
        assertThat(listEvents(userA.username(), archival))
            .contains("UID:old")
            .doesNotContain("UID:new");

        // Default calendar: matched removed, non-matching kept
        assertThat(listEvents(userA.username(), defaultCalendar))
            .doesNotContain("UID:old")
            .contains("UID:new");
    }

    @Test
    void calendarArchivalAllShouldSupportRejectedOnlyParameter() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        insertEventWithAttendeeParStat(userA, "rejected", PartStat.DECLINED);
        insertEventWithAttendeeParStat(userA, "accepted", PartStat.ACCEPTED);

        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("isRejected", "true")
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.rejectedOnly", is(true));

        CalendarURL archival = findArchivalCalendar(userA);
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());

        // Archival calendar: only rejected event
        assertThat(listEvents(userA.username(), archival))
            .contains("UID:rejected")
            .doesNotContain("UID:accepted");

        // Default calendar: rejected removed, accepted remains
        assertThat(listEvents(userA.username(), defaultCalendar))
            .doesNotContain("UID:rejected")
            .contains("UID:accepted");
    }

    @Test
    void calendarArchivalAllShouldSupportNonRecurringParameter() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        // Recurring event (should be excluded)
        String recurringUid = "recurring-event";
        String recurringIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:%s
            DTSTART:%s
            DTEND:%s
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(recurringUid,
                formatInstant(fixedNow),
                formatInstant(fixedNow.minus(10, ChronoUnit.DAYS)),
                formatInstant(fixedNow.minus(10, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)));

        // Single non-recurring event (should be archived)
        String singleUid = "single-event";
        insertEvent(userA, singleUid, fixedNow.minus(20, ChronoUnit.DAYS));

        calDavClient.importCalendar(
            CalendarURL.from(userA.id()),
            recurringUid,
            userA.username(),
            recurringIcs.getBytes(StandardCharsets.UTF_8))
            .block();

        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("isNotRecurring", "true")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.isNotRecurring", is(true));

        CalendarURL archival = findArchivalCalendar(userA);
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());

        // Only the single, non-recurring event is archived
        assertThat(listEvents(userA.username(), archival))
            .contains("UID:" + singleUid)
            .doesNotContain("UID:" + recurringUid);

        // Recurring event remains in default calendar
        assertThat(listEvents(userA.username(), defaultCalendar))
            .contains("UID:" + recurringUid)
            .doesNotContain("UID:" + singleUid);
    }

    @Test
    void calendarArchivalAllShouldSupportMultipleCriteriaCombination() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        Instant oldCreated = fixedNow.minus(10, ChronoUnit.DAYS);
        Instant newCreated = fixedNow.plus(1, ChronoUnit.DAYS);

        // A: match NONE (new + accepted)
        insertEventWithCreatedAndAttendee(userA, "match-none", newCreated, PartStat.ACCEPTED);

        // B: match createdBefore ONLY
        insertEventWithCreatedAndAttendee(userA, "match-created-only", oldCreated, PartStat.ACCEPTED);

        // C: match rejectedOnly ONLY
        insertEventWithCreatedAndAttendee(userA, "match-rejected-only", newCreated, PartStat.DECLINED);

        // D: match BOTH
        insertEventWithCreatedAndAttendee(userA, "match-both", oldCreated, PartStat.DECLINED);

        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("createdBefore", "5d")
            .queryParam("isRejected", "true")
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.rejectedOnly", is(true))
            .body("additionalInformation.criteria.createdBefore", is("2025-12-27T00:00:00Z"));

        CalendarURL archival = findArchivalCalendar(userA);
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());

        assertThat(listEvents(userA.username(), archival))
            .contains("UID:match-both")
            .doesNotContain("UID:match-none")
            .doesNotContain("UID:match-created-only")
            .doesNotContain("UID:match-rejected-only");

        assertThat(listEvents(userA.username(), defaultCalendar))
            .contains("UID:match-none")
            .contains("UID:match-created-only")
            .contains("UID:match-rejected-only")
            .doesNotContain("UID:match-both");
    }

    @Test
    void calendarArchivalAllShouldArchiveAllEventsWhenNoCriteriaProvided() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        // Given: user has multiple events
        insertEventWithCreated(userA, "event-1", fixedNow.minus(10, ChronoUnit.DAYS));
        insertEventWithLastModified(userA, "event-2", fixedNow.minus(5, ChronoUnit.DAYS));
        insertEventWithAttendeeParStat(userA, "event-3", PartStat.ACCEPTED);

        // When: archive without any filter
        String taskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .post()
            .jsonPath()
            .get("taskId");

        // Then: task completed and all events archived
        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(3))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.createdBefore", is((String) null))
            .body("additionalInformation.criteria.lastModifiedBefore", is((String) null))
            .body("additionalInformation.criteria.masterDtStartBefore", is((String) null))
            .body("additionalInformation.criteria.rejectedOnly", is(false));

        CalendarURL archival = findArchivalCalendar(userA);
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());

        // All events are archived
        assertThat(listEvents(userA.username(), archival))
            .contains("UID:event-1")
            .contains("UID:event-2")
            .contains("UID:event-3");

        // Default calendar is empty
        assertThat(listEvents(userA.username(), defaultCalendar))
            .doesNotContain("UID:event-1")
            .doesNotContain("UID:event-2")
            .doesNotContain("UID:event-3");
    }

    @Test
    void calendarArchivalAllShouldBeIdempotentWhenCalledMultipleTimes() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        // Given: user has multiple events
        insertEventWithCreated(userA, "event-1", fixedNow.minus(10, ChronoUnit.DAYS));
        insertEventWithCreated(userA, "event-2", fixedNow.minus(5, ChronoUnit.DAYS));

        // First archival call
        String firstTaskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(firstTaskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));

        // Second archival call (idempotent behavior)
        String secondTaskId = given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(secondTaskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.archivedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0));
    }

    @Test
    void userCalendarArchivalShouldArchiveAllEventsWhenNoCriteriaProvided() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        // Given: user has multiple events
        insertEventWithCreated(userA, "event-1", fixedNow.minus(10, ChronoUnit.DAYS));
        insertEventWithLastModified(userA, "event-2", fixedNow.minus(5, ChronoUnit.DAYS));
        insertEventWithAttendeeParStat(userA, "event-3", PartStat.ACCEPTED);

        String username = userA.username().asString();

        // When: archive single user without any criteria
        String taskId = given()
            .basePath("/calendars/{userName}")
            .pathParam("userName", username)
            .queryParam("task", "archive")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        // Then: task completed with default criteria
        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("type", is("calendar-archival"))
            .body("additionalInformation.targetUser", is(username))
            .body("additionalInformation.archivedEventCount", is(3))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.criteria.createdBefore", is((String) null))
            .body("additionalInformation.criteria.lastModifiedBefore", is((String) null))
            .body("additionalInformation.criteria.masterDtStartBefore", is((String) null))
            .body("additionalInformation.criteria.rejectedOnly", is(false));
    }

    @Test
    void calendarArchivalAllShouldFailWhenCriteriaParameterIsInvalid() {
        given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("createdBefore", "not-a-duration")
        .when()
            .post()
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void calendarArchivalAllShouldFailWhenLastModifiedBeforeIsInvalid() {
        given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("lastModifiedBefore", "invalid")
        .when()
            .post()
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void calendarArchivalAllShouldFailWhenMasterDtStartBeforeIsInvalid() {
        given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("masterDtStartBefore", "foo")
        .when()
            .post()
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void calendarArchivalAllShouldFailWhenRejectedOnlyIsInvalid() {
        given()
            .basePath(CalendarRoutes.BASE_PATH)
            .queryParam("task", "archive")
            .queryParam("isRejected", "not-a-boolean")
        .when()
            .post()
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void userCalendarArchivalShouldReturnCompletedTaskDetailsWhenNoEventMatches() {
        String username = userA.username().asString();

        String taskId = given()
            .basePath("/calendars/{userName}")
            .pathParam("userName", username)
            .queryParam("task", "archive")
            .queryParam("masterDtStartBefore", "1y")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("calendar-archival"))
            .body("additionalInformation.targetUser", is(username))
            .body("additionalInformation.archivedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()))
            .body("completedDate", is(notNullValue()));
    }

    @Test
    void userCalendarArchivalShouldArchiveOnlyTargetUserEvents() {
        Instant oldEvent = clock.instant().minus(365, ChronoUnit.DAYS);
        Instant newEvent = clock.instant().plus(1, ChronoUnit.DAYS);

        insertEvent(userA, "old", oldEvent);
        insertEvent(userA, "new", newEvent);

        String taskId = given()
            .basePath("/calendars/{userName}")
            .pathParam("userName", userA.username().asString())
            .queryParam("task", "archive")
            .queryParam("masterDtStartBefore", "6m")
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("additionalInformation.archivedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0));

        CalendarURL archivalCalendar = findArchivalCalendar(userA);

        // Archival calendar: only matched event is present
        assertThat(listEvents(userA.username(), archivalCalendar))
            .contains("UID:old")
            .doesNotContain("UID:new");

        // Default calendar: matched event removed, non-matching event remains
        CalendarURL defaultCalendar = CalendarURL.from(userA.id());
        assertThat(listEvents(userA.username(), defaultCalendar))
            .doesNotContain("UID:old")
            .contains("UID:new");
    }

    @Test
    void userCalendarArchivalShouldSupportAllCriteriaParameters() {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(fixedNow);

        String username = userA.username().asString();

        String taskId = given()
            .basePath("/calendars/{userName}")
            .pathParam("userName", username)
            .queryParam("task", "archive")
            .queryParam("createdBefore", "10d")
            .queryParam("lastModifiedBefore", "7d")
            .queryParam("masterDtStartBefore", "5d")
            .queryParam("isRejected", "true")
            .queryParam("isNotRecurring", "true")
        .when()
            .post()
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("additionalInformation.targetUser", is(username))
            .body("additionalInformation.criteria.createdBefore", is("2025-12-22T00:00:00Z"))
            .body("additionalInformation.criteria.lastModifiedBefore", is("2025-12-25T00:00:00Z"))
            .body("additionalInformation.criteria.masterDtStartBefore", is("2025-12-27T00:00:00Z"))
            .body("additionalInformation.criteria.rejectedOnly", is(true))
            .body("additionalInformation.criteria.isNotRecurring", is(true));
    }

    @Test
    void userCalendarArchivalShouldFailWhenUserDoesNotExist() {
        String nonExistingUser = "ghost@linagora.com";

        given()
            .basePath("/calendars/{userName}")
            .pathParam("userName", nonExistingUser)
            .queryParam("task", "archive")
        .when()
            .post()
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    private String listEvents(Username username, CalendarURL calendar) {
        return calDavClient.export(username, calendar.asUri())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

    private CalendarURL findArchivalCalendar(OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id(), Map.of("personal", "true"))
            .flatMap(calendarList -> Mono.justOrEmpty(calendarList.findCalendarByName("Archival")))
            .block();
    }

    private void insertEvent(OpenPaaSUser user,
                             String uid,
                             Instant dtStart) {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{uid}
            DTSTART:{dtstart}
            DTEND:{dtend}
            SUMMARY:Test event
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{uid}", uid)
            .replace("{dtstart}", formatInstant(dtStart))
            .replace("{dtend}", formatInstant(dtStart.plus(1, ChronoUnit.HOURS)));

        importEvent(user, uid, ics);
    }

    private void insertEventWithCreated(OpenPaaSUser user,
                                        String uid,
                                        Instant created) {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:{dtstamp}
            DTSTART:{dtstart}
            DTEND:{dtend}
            SUMMARY:Test event
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{uid}", uid)
            .replace("{dtstamp}", formatInstant(created))
            .replace("{dtstart}", formatInstant(clock.instant()))
            .replace("{dtend}", formatInstant(clock.instant().plus(1, ChronoUnit.HOURS)));

        importEvent(user, uid, ics);
    }

    private void insertEventWithLastModified(OpenPaaSUser user,
                                             String uid,
                                             Instant lastModified) {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:{dtstamp}
            LAST-MODIFIED:{lastModified}
            DTSTART:{dtstart}
            DTEND:{dtend}
            SUMMARY:Test event
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{uid}", uid)
            .replace("{dtstamp}", formatInstant(clock.instant()))
            .replace("{lastModified}", formatInstant(lastModified))
            .replace("{dtstart}", formatInstant(clock.instant()))
            .replace("{dtend}", formatInstant(clock.instant().plus(1, ChronoUnit.HOURS)));

        importEvent(user, uid, ics);
    }

    private void insertEventWithAttendeeParStat(OpenPaaSUser user, String uid, PartStat partStat) {
        String userEmail = user.username().asString();

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:{dtstamp}
            DTSTART:{dtstart}
            DTEND:{dtend}
            ATTENDEE;PARTSTAT={partstat}:mailto:{email}
            SUMMARY:Rejected event
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{uid}", uid)
            .replace("{dtstamp}", formatInstant(clock.instant()))
            .replace("{dtstart}", formatInstant(clock.instant()))
            .replace("{dtend}", formatInstant(clock.instant().plus(1, ChronoUnit.HOURS)))
            .replace("{partstat}", partStat.getValue())
            .replace("{email}", userEmail);

        importEvent(user, uid, ics);
    }

    private void insertEventWithCreatedAndAttendee(OpenPaaSUser user,
                                                   String uid,
                                                   Instant created,
                                                   PartStat partStat) {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:{dtstamp}
            DTSTART:{dtstart}
            DTEND:{dtend}
            ATTENDEE;PARTSTAT={partstat}:mailto:{email}
            SUMMARY:Test event
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{uid}", uid)
            .replace("{dtstamp}", formatInstant(created))
            .replace("{dtstart}", formatInstant(created))
            .replace("{dtend}", formatInstant(created.plus(1, ChronoUnit.HOURS)))
            .replace("{partstat}", partStat.getValue())
            .replace("{email}", user.username().asString());

        importEvent(user, uid, ics);
    }

    private void importEvent(OpenPaaSUser user, String eventUid, String ics) {
        calDavClient.importCalendar(CalendarURL.from(user.id()), eventUid, user.username(), ics.getBytes(StandardCharsets.UTF_8))
            .block();
    }

    private String formatInstant(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(instant);
    }
}
