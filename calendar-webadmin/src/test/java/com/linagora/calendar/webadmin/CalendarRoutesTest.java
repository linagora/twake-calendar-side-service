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

import static com.linagora.calendar.dav.CalDavClient.ICS_EXTENSION;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.event.EventParseUtils.UTC_DATE_TIME_FORMATTER;
import static com.linagora.calendar.storage.eventsearch.EventSearchQuery.MAX_LIMIT;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration.builder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import javax.net.ssl.SSLException;

import jakarta.mail.internet.AddressException;

import org.apache.http.HttpStatus;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.CalendarMultiGetEntry;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;
import com.linagora.calendar.storage.eventsearch.MemoryCalendarSearchService;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTask.RunningOptions;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import reactor.core.publisher.Mono;

public class CalendarRoutesTest {

    private static final int EXPECTED_MULTIGET_BATCH_SIZE = 50;

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBResourceDAO resourceDAO;
    private CalendarSearchService calendarSearchService;
    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;
    private CalendarEventsReindexService reindexService;

    private OpenPaaSUser openPaaSUser;
    private CalendarURL calendar;
    private CalendarEventsReindexService.Context reindexContext;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.systemUTC());
        calendarSearchService = spy(new MemoryCalendarSearchService());
        calDavClient = spy(new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING));
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        reindexService = new CalendarEventsReindexService(userDAO, resourceDAO, calendarSearchService, calDavClient);

        this.openPaaSUser = sabreDavExtension.newTestUser();
        calendar = CalendarURL.from(openPaaSUser.id());
        reindexContext = new CalendarEventsReindexService.Context();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));

        webAdminServer = WebAdminUtils.createWebAdminServer(new CalendarRoutes(new JsonTransformer(),
                taskManager,
                ImmutableSet.of(new CalendarRoutes.CalendarEventsReindexRequestToTask(reindexService)), Set.of()),
            new TasksRoutes(taskManager,
                new JsonTransformer(),
                new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                    .add(CalendarEventsReindexTaskAdditionalInformationDTO.module())
                    .build()))
        ).start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .setBasePath(CalendarRoutes.BASE_PATH)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void shouldShowAllInformationInResponse() {
        String taskId = given()
            .queryParam("task", "reindex")
            .queryParam("eventsPerSecond", 12)
            .queryParam("calendarsConcurrency", 3)
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.runningOptions.eventsPerSecond", is(12))
            .body("additionalInformation.runningOptions.calendarsConcurrency", is(3))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("reindex-calendar-events"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()))
            .body("completedDate", is(notNullValue()));
    }

    @Test
    void reindexShouldRejectNonPositiveEventsPerSecondWithReadableMessage() {
        // Given a reindex request with a negative eventsPerSecond
        // When the request is posted
        // Then it is rejected with the actual value substituted in the message
        given()
            .queryParam("task", "reindex")
            .queryParam("eventsPerSecond", -5)
            .when()
            .post()
            .then()
            .statusCode(400)
            .body("details", is("Query parameter 'eventsPerSecond' must be strictly positive, got: -5"));
    }

    @Test
    void reindexShouldRejectNonPositiveCalendarsConcurrencyWithReadableMessage() {
        // Given a reindex request with calendarsConcurrency set to zero
        // When the request is posted
        // Then it is rejected with the actual value substituted in the message
        given()
            .queryParam("task", "reindex")
            .queryParam("calendarsConcurrency", 0)
            .when()
            .post()
            .then()
            .statusCode(400)
            .body("details", is("Query parameter 'calendarsConcurrency' must be strictly positive, got: 0"));
    }

    @Test
    void reindexShouldIndexEvent() throws AddressException {
        String eventId = "event-1";
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CN=john doe;CUTYPE=individual:mailto:%s
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=Projector;SCHEDULE-STATUS=5.1:mailto:projector@open-paas.org
            DESCRIPTION:This is a test event
            LOCATION:office
            CLASS:PUBLIC
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventId, openPaaSUser.username().asString(), openPaaSUser.username().asString());
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        calDavClient.importCalendar(calendarURL, eventId, openPaaSUser.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        String taskId = given()
            .queryParam("task", "reindex")
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0));

        EventFields.Person person = new EventFields.Person("john doe", new MailAddress(openPaaSUser.username().asString()));
        EventFields expected = EventFields.builder()
            .uid(eventId)
            .summary("Test Event")
            .location("office")
            .description("This is a test event")
            .clazz("PUBLIC")
            .dtStamp(Instant.from(UTC_DATE_TIME_FORMATTER.parse("20250101T100000Z")))
            .start(Instant.from(UTC_DATE_TIME_FORMATTER.parse("20250102T120000Z")))
            .end(Instant.from(UTC_DATE_TIME_FORMATTER.parse("20250102T130000Z")))
            .allDay(false)
            .organizer(person)
            .attendees(List.of(person))
            .resources(List.of(new EventFields.Person("Projector", new MailAddress("projector@open-paas.org"))))
            .calendarURL(calendarURL)
            .resourceName(eventId + ICS_EXTENSION)
            .build();

        List<EventFields> actual = calendarSearchService.search(simpleQuery("", calendarURL))
            .collectList().block();
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst())
            .usingRecursiveComparison()
            .ignoringFields("attendees.partStat", "resources.partStat", "organizer.partStat")
            .isEqualTo(expected);
    }

    @Test
    void reindexShouldIndexResourceCalendarEvent() {
        OpenPaaSDomain domain = domainDAO.retrieve(openPaaSUser.username().getDomainPart().orElseThrow()).block();
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(
            openPaaSUser.id(),
            "Resource calendar used by reindex tests",
            domain.id(),
            "projector",
            "Resource calendar used by reindex tests"))
            .block();
        CalendarURL resourceCalendarURL = CalendarURL.from(resourceId.asOpenPaaSId());

        String eventId = "resource-event-" + UUID.randomUUID();
        String resourceEmail = Username.fromLocalPartWithDomain(resourceId.value(), openPaaSUser.username().getDomainPart().orElseThrow()).asString();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Resource Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=Projector:mailto:%s
            CLASS:PUBLIC
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventId, openPaaSUser.username().asString(), resourceEmail);
        davTestHelper.upsertCalendar(openPaaSUser, ics, eventId);
        URI resourceEventUri = URI.create("/calendars/" + resourceId.value() + "/" + resourceId.value() + "/" + eventId + ICS_EXTENSION);
        davTestHelper.upsertCalendar(domain.id(), resourceEventUri, ics).block();
        String resourceEventId = eventId;

        String taskId = given()
            .queryParam("task", "reindex")
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));

        List<EventFields> actual = calendarSearchService.search(simpleQuery("", resourceCalendarURL))
            .collectList().block();
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst().uid().value()).isEqualTo(eventId);
        assertThat(actual.getFirst().summary()).isEqualTo("Resource Test Event");
        assertThat(actual.getFirst().calendarURL()).isEqualTo(resourceCalendarURL);
        assertThat(actual.getFirst().resourceName()).contains(resourceEventId + ICS_EXTENSION);
    }

    @Test
    void reindexShouldIndexMultipleEvents() throws AddressException {
        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String username = openPaaSUser.username().asString();;
        byte[] ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
            X-WR-CALNAME:#default
            BEGIN:VTIMEZONE
            TZID:Asia/Saigon
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250514T130000
            DTEND;TZID=Asia/Saigon:20250514T133000
            CLASS:PUBLIC
            SUMMARY:recur111
            RRULE:FREQ=DAILY;COUNT=3
            ORGANIZER;CN=John1 Doe1:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CN=John1 Doe1;CUTYPE=INDIVIDUAL:mailto:%s
            DTSTAMP:20250515T073930Z
            END:VEVENT
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250515T120000
            DTEND;TZID=Asia/Saigon:20250515T123000
            CLASS:PUBLIC
            SUMMARY:recur222
            ORGANIZER;CN=John1 Doe1:mailto:%s
            DTSTAMP:20250515T073930Z
            RECURRENCE-ID:20250515T060000Z
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CN=John1 Doe1;CUTYPE=INDIVIDUAL:mailto:%s
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1, username, username, uid1, username, username)
            .getBytes(StandardCharsets.UTF_8);
        byte[] ics2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
            X-WR-CALNAME:#default
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;VALUE=DATE:20250513
            DTEND;VALUE=DATE:20250514
            CLASS:PUBLIC
            SUMMARY:test555
            ORGANIZER;CN=John1 Doe1:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CN=John1 Doe1;CUTYPE=INDIVIDUAL:mailto:%s
            DTSTAMP:20250515T074016Z
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid2, username, username)
            .getBytes(StandardCharsets.UTF_8);
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        calDavClient.importCalendar(calendarURL, uid1, openPaaSUser.username(), ics).block();
        calDavClient.importCalendar(calendarURL, uid2, openPaaSUser.username(), ics2).block();

        String taskId = given()
            .queryParam("task", "reindex")
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));

        EventFields.Person person = new EventFields.Person("John1 Doe1", new MailAddress(username));
        EventFields expected1 = EventFields.builder()
            .uid(uid1)
            .summary("recur111")
            .location(null)
            .description(null)
            .clazz("PUBLIC")
            .start(Instant.parse("2025-05-14T06:00:00Z")) // Asia/Saigon 13:00 = UTC+7
            .end(Instant.parse("2025-05-14T06:30:00Z"))
            .dtStamp(Instant.parse("2025-05-15T07:39:30Z"))
            .allDay(false)
            .isRecurrentMaster(true)
            .organizer(person)
            .attendees(List.of(person))
            .resources(List.of())
            .calendarURL(calendarURL)
            .resourceName(uid1 + ICS_EXTENSION)
            .build();

        EventFields expected3 = EventFields.builder()
            .uid(uid2)
            .summary("test555")
            .location(null)
            .description(null)
            .clazz("PUBLIC")
            .start(Instant.parse("2025-05-13T00:00:00Z"))
            .end(Instant.parse("2025-05-14T00:00:00Z"))
            .dtStamp(Instant.parse("2025-05-15T07:40:16Z"))
            .allDay(true)
            .organizer(person)
            .attendees(List.of(person))
            .resources(List.of())
            .calendarURL(calendarURL)
            .resourceName(uid2 + ICS_EXTENSION)
            .build();

        List<EventFields> actual = calendarSearchService.search(simpleQuery("", calendarURL))
            .collectList().block();
        // Search collapses occurrences on the uid and keeps the recurrence master as the representative:
        // the recur222 overridden occurrence is not surfaced separately.
        assertThat(actual)
            .usingRecursiveFieldByFieldElementComparator(builder()
                .withIgnoredFields("attendees.partStat", "resources.partStat", "organizer.partStat", "recurrenceId")
                .build())
            .containsExactlyInAnyOrder(expected1, expected3);
    }

    @Test
    void reindexShouldRemoveOldEvents() {
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());
        EventFields event1 = EventFields.builder()
            .uid("event-1")
            .summary("Event1")
            .calendarURL(calendarURL)
            .build();
        calendarSearchService.index(CalendarEvents.of(event1)).block();

        String taskId = given()
            .queryParam("task", "reindex")
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0));

        List<EventFields> actual = calendarSearchService.search(simpleQuery("", calendarURL))
            .collectList().block();
        assertThat(actual).isEmpty();
    }

    @Test
    void reindexShouldRemoveOldEventsForDeletedResource() {
        OpenPaaSDomain domain = domainDAO.retrieve(openPaaSUser.username().getDomainPart().orElseThrow()).block();
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(
            openPaaSUser.id(),
            "Deleted resource calendar used by reindex tests",
            domain.id(),
            "projector",
            "Deleted resource calendar used by reindex tests"))
            .block();
        CalendarURL resourceCalendarURL = CalendarURL.from(resourceId.asOpenPaaSId());

        EventFields staleEvent = EventFields.builder()
            .uid("stale-resource-event-" + UUID.randomUUID())
            .summary("Stale resource event")
            .calendarURL(resourceCalendarURL)
            .build();
        calendarSearchService.index(CalendarEvents.of(staleEvent)).block();
        resourceDAO.softDelete(resourceId).block();

        String taskId = given()
            .queryParam("task", "reindex")
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0));

        List<EventFields> actual = calendarSearchService.search(simpleQuery("", resourceCalendarURL))
            .collectList().block();
        assertThat(actual).isEmpty();
    }

    @Test
    void reindexShouldReportFailedEventCount() {
        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String username = openPaaSUser.username().asString();;
        byte[] ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
            X-WR-CALNAME:#default
            BEGIN:VTIMEZONE
            TZID:Asia/Saigon
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250514T130000
            DTEND;TZID=Asia/Saigon:20250514T133000
            CLASS:PUBLIC
            SUMMARY:recur111
            RRULE:FREQ=DAILY;COUNT=3
            ORGANIZER;CN=John1 Doe1:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CN=John1 Doe1;CUTYPE=INDIVIDUAL:mailto:%s
            DTSTAMP:20250515T073930Z
            END:VEVENT
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250515T120000
            DTEND;TZID=Asia/Saigon:20250515T123000
            CLASS:PUBLIC
            SUMMARY:recur222
            ORGANIZER;CN=John1 Doe1:mailto:%s
            DTSTAMP:20250515T073930Z
            RECURRENCE-ID:20250515T060000Z
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CN=John1 Doe1;CUTYPE=INDIVIDUAL:mailto:%s
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1, username, username, uid1, username, username)
            .getBytes(StandardCharsets.UTF_8);
        byte[] ics2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
            X-WR-CALNAME:#default
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;VALUE=DATE:20250513
            DTEND;VALUE=DATE:20250514
            CLASS:PUBLIC
            SUMMARY:test555
            ORGANIZER;CN=John1 Doe1:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CN=John1 Doe1;CUTYPE=INDIVIDUAL:mailto:%s
            DTSTAMP:20250515T074016Z
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid2, username, username)
            .getBytes(StandardCharsets.UTF_8);
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        calDavClient.importCalendar(calendarURL, uid1, openPaaSUser.username(), ics).block();
        calDavClient.importCalendar(calendarURL, uid2, openPaaSUser.username(), ics2).block();

        doAnswer(invocation -> {
            CalendarEvents events = invocation.getArgument(0);
            if (events.eventUid().value().equals(uid2)) {
                return Mono.error(new RuntimeException("Simulated failure for uid2"));
            }
            return Mono.empty();
        }).when(calendarSearchService).reindex(any(CalendarEvents.class));

        String taskId = given()
            .queryParam("task", "reindex")
            .when()
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
            .when()
            .get(taskId + "/await")
            .then()
            .body("status", is("failed"))
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(1));
    }

    @Test
    void reindexShouldIndexEventWithCorrectIcsResourceName() throws AddressException {
        // Given an event with an ics resource name that is not the same as its UID
        String eventId = "event-1";
        String icsResourceName = "sabre-event-1";
        String ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CN=john doe;CUTYPE=individual:mailto:%s
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=Projector;SCHEDULE-STATUS=5.1:mailto:projector@open-paas.org
            DESCRIPTION:This is a test event
            LOCATION:office
            CLASS:PUBLIC
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventId, openPaaSUser.username().asString(), openPaaSUser.username().asString());
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        calDavClient.importCalendar(calendarURL, icsResourceName, openPaaSUser.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        // When reindexing calendar events
        String taskId = given()
            .queryParam("task", "reindex")
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
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0));

        // Then the event should be indexed with the correct ics resource name
        EventFields.Person person = new EventFields.Person("john doe", new MailAddress(openPaaSUser.username().asString()));
        EventFields expected = EventFields.builder()
            .uid(eventId)
            .summary("Test Event")
            .location("office")
            .description("This is a test event")
            .clazz("PUBLIC")
            .dtStamp(Instant.from(UTC_DATE_TIME_FORMATTER.parse("20250101T100000Z")))
            .start(Instant.from(UTC_DATE_TIME_FORMATTER.parse("20250102T120000Z")))
            .end(Instant.from(UTC_DATE_TIME_FORMATTER.parse("20250102T130000Z")))
            .allDay(false)
            .organizer(person)
            .attendees(List.of(person))
            .resources(List.of(new EventFields.Person("Projector", new MailAddress("projector@open-paas.org"))))
            .calendarURL(calendarURL)
            .resourceName(icsResourceName + ICS_EXTENSION)
            .build();

        List<EventFields> actual = calendarSearchService.search(simpleQuery("", calendarURL))
            .collectList().block();
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst())
            .usingRecursiveComparison()
            .ignoringFields("attendees.partStat", "resources.partStat", "organizer.partStat")
            .isEqualTo(expected);
    }

    @Test
    void shouldFinishWithoutMultigetWhenCalendarIsEmpty() {
        // Given an empty calendar
        // When reindexing it
        Task.Result result = reindex();

        // Then no multiget request is needed
        assertThat(result).isEqualTo(Task.Result.COMPLETED);
        assertEventCounts(0, 0);
        verify(calDavClient, never()).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), anyList());
    }

    @Test
    void shouldReindexLargeUserCalendarAcrossBatches() {
        // Given a large calendar that needs a full batch and a smaller final batch
        int remainingEventCount = 3;
        int eventCount = EXPECTED_MULTIGET_BATCH_SIZE + remainingEventCount;
        String largeDescription = "x".repeat(8_000);
        for (URI eventHref : eventHrefs(eventCount)) {
            String largeEventIcs = eventIcs(eventHref).replace("END:VEVENT", "DESCRIPTION:" + largeDescription + "\nEND:VEVENT");
            davTestHelper.upsertCalendar(openPaaSUser.username(), eventHref, largeEventIcs).block();
        }

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then all events are indexed across a full batch and the remaining batch
        assertThat(result).isEqualTo(Task.Result.COMPLETED);
        assertEventCounts(eventCount, 0);
        verify(calDavClient, times(2)).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), anyList());
        verify(calDavClient).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), argThat(batch -> batch.size() == EXPECTED_MULTIGET_BATCH_SIZE));
        verify(calDavClient).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), argThat(batch -> batch.size() == remainingEventCount));
    }

    @Test
    void shouldReindexResourceCalendarAcrossBatches() {
        // Given a resource calendar with more events than one batch can hold
        int remainingEventCount = 1;
        int eventCount = EXPECTED_MULTIGET_BATCH_SIZE + remainingEventCount;
        OpenPaaSDomain domain = domainDAO.retrieve(openPaaSUser.username().getDomainPart().orElseThrow()).block();
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(openPaaSUser.id(), "Meeting room", domain.id(), "room", "Room"))
            .block();
        calendar = CalendarURL.from(resourceId.asOpenPaaSId());
        for (URI eventHref : eventHrefs(eventCount)) {
            davTestHelper.upsertCalendar(domain.id(), eventHref, eventIcs(eventHref)).block();
        }

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then all events are fetched in batches using the domain technical token
        assertThat(result).isEqualTo(Task.Result.COMPLETED);
        assertEventCounts(eventCount, 0);
        verify(calDavClient, times(2)).calendarMultiGet(eq(domain.id()), eq(calendar), anyList());
        verify(calDavClient).calendarMultiGet(eq(domain.id()), eq(calendar), argThat(batch -> batch.size() == EXPECTED_MULTIGET_BATCH_SIZE));
        verify(calDavClient).calendarMultiGet(eq(domain.id()), eq(calendar), argThat(batch -> batch.size() == remainingEventCount));
    }

    @Test
    void shouldIndexRemainingEventsWhenBatchFetchFails() {
        // Given a failed full batch followed by a batch that can still be indexed
        int failedEventCount = EXPECTED_MULTIGET_BATCH_SIZE;
        int remainingEventCount = 1;
        upsertEvents(failedEventCount + remainingEventCount);
        doReturn(Mono.error(new DavClientException("Sabre is unavailable")))
            .when(calDavClient).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), argThat(batch -> batch.size() == EXPECTED_MULTIGET_BATCH_SIZE));

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then the remaining event is indexed and the failed batch is counted
        assertThat(result).isEqualTo(Task.Result.PARTIAL);
        assertEventCounts(remainingEventCount, failedEventCount);
    }

    @Test
    void shouldCompleteReindexWhenListedEventWasDeleted() {
        // Given two listed events, one of which was deleted before multiget
        List<URI> eventHrefs = upsertEvents(2);
        CalendarMultiGetEntry deletedEvent = new CalendarMultiGetEntry(eventHrefs.getFirst(), HttpStatus.SC_NOT_FOUND, "");
        CalendarMultiGetEntry readableEvent = new CalendarMultiGetEntry(eventHrefs.getLast(), HttpStatus.SC_OK, eventIcs(eventHrefs.getLast()));
        doReturn(Mono.just(List.of(deletedEvent, readableEvent)))
            .when(calDavClient).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), anyList());

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then the deleted event is skipped without marking the task as partial
        assertThat(result).isEqualTo(Task.Result.COMPLETED);
        assertEventCounts(1, 0);
    }

    @Test
    void shouldReportPartialReindexWhenEventIsForbidden() {
        // Given a multiget response with one forbidden event and one readable event
        List<URI> eventHrefs = upsertEvents(2);
        CalendarMultiGetEntry forbiddenEvent = new CalendarMultiGetEntry(eventHrefs.getFirst(), HttpStatus.SC_FORBIDDEN, "");
        CalendarMultiGetEntry readableEvent = new CalendarMultiGetEntry(eventHrefs.getLast(), HttpStatus.SC_OK, eventIcs(eventHrefs.getLast()));
        doReturn(Mono.just(List.of(forbiddenEvent, readableEvent)))
            .when(calDavClient).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), anyList());

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then the readable event is indexed and the forbidden event is counted as failed
        assertThat(result).isEqualTo(Task.Result.PARTIAL);
        assertEventCounts(1, 1);
    }

    @Test
    void shouldIndexValidEventsWhenAnotherIcsCannotBeParsed() {
        // Given a multiget response with one malformed ICS and one valid ICS
        List<URI> eventHrefs = upsertEvents(2);
        CalendarMultiGetEntry malformedEvent = new CalendarMultiGetEntry(eventHrefs.getFirst(), HttpStatus.SC_OK, "invalid ICS");
        CalendarMultiGetEntry validEvent = new CalendarMultiGetEntry(eventHrefs.getLast(), HttpStatus.SC_OK, eventIcs(eventHrefs.getLast()));
        doReturn(Mono.just(List.of(malformedEvent, validEvent)))
            .when(calDavClient).calendarMultiGet(eq(openPaaSUser.username()), eq(calendar), anyList());

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then the valid event is indexed despite the parse failure
        assertThat(result).isEqualTo(Task.Result.PARTIAL);
        assertEventCounts(1, 1);
    }

    @Test
    void shouldReindexEventWhoseIcsExceedsXmlEntityLimit() {
        // Given a single ICS resource larger than the original 100,000-character limit
        URI eventHref = eventHrefs(1).getFirst();
        String oversizedDescription = "x".repeat(150_000);
        String largeEventIcs = eventIcs(eventHref).replace("END:VEVENT", "DESCRIPTION:" + oversizedDescription + "\nEND:VEVENT");
        davTestHelper.upsertCalendar(openPaaSUser.username(), eventHref, largeEventIcs).block();

        // When reindexing the calendar
        Task.Result result = reindex();

        // Then the event is indexed successfully
        assertThat(result).isEqualTo(Task.Result.COMPLETED);
        assertEventCounts(1, 0);
    }

    private List<URI> upsertEvents(int eventCount) {
        List<URI> eventHrefs = eventHrefs(eventCount);
        for (URI eventHref : eventHrefs) {
            davTestHelper.upsertCalendar(openPaaSUser.username(), eventHref, eventIcs(eventHref)).block();
        }
        return eventHrefs;
    }

    private List<URI> eventHrefs(int eventCount) {
        return IntStream.range(0, eventCount)
            .mapToObj(i -> URI.create(calendar.asUri() + "/event-" + i + ".ics"))
            .toList();
    }

    private String eventIcs(URI eventHref) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{{href}}
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Batch event
            END:VEVENT
            END:VCALENDAR
            """.replace("{{href}}", eventHref.toString());
    }

    private Task.Result reindex() {
        return reindexService.reindex(reindexContext, RunningOptions.of(1000, 1)).block(Duration.ofSeconds(10));
    }

    private void assertEventCounts(int processedCount, int failedCount) {
        assertThat(reindexContext.snapshot()).isEqualTo(new CalendarEventsReindexService.Context.Snapshot(processedCount, failedCount));
        assertThat(calendarSearchService.search(simpleQuery("", calendar)).collectList().block()).hasSize(processedCount);
    }

    private EventSearchQuery simpleQuery(String query, CalendarURL calendarURL) {
        return new EventSearchQuery(query, Optional.of(List.of(calendarURL)),
            Optional.empty(), Optional.empty(), Optional.empty(),
            MAX_LIMIT, 0);
    }
}
