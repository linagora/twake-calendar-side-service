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

import static com.linagora.calendar.storage.eventsearch.EventSearchQuery.MAX_LIMIT;
import static com.linagora.calendar.webadmin.service.CalendarEventsReindexService.UTC_DATE_TIME_FORMATTER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.vacation.api.AccountId;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventFields;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;
import com.linagora.calendar.storage.eventsearch.MemoryCalendarSearchService;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import reactor.core.publisher.Mono;

public class CalendarRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private CalendarSearchService calendarSearchService;
    private CalDavClient calDavClient;
    private CalendarEventsReindexService reindexService;

    private OpenPaaSUser openPaaSUser;
    private OpenPaaSUser openPaaSUser2;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        calendarSearchService = spy(new MemoryCalendarSearchService());
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
        reindexService = new CalendarEventsReindexService(userDAO, calendarSearchService, calDavClient);

        this.openPaaSUser = sabreDavExtension.newTestUser();
        this.openPaaSUser2 = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));

        webAdminServer = WebAdminUtils.createWebAdminServer(new CalendarRoutes(new JsonTransformer(),
                taskManager,
                ImmutableSet.of(new CalendarRoutes.CalendarEventsReindexRequestToTask(reindexService))),
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
            .queryParam("task", "reindexCalendarEvents")
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
            .body("additionalInformation.processedUserCount", is(2))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.failedUsers", is(ImmutableList.of()))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("reindex-calendar-events"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()))
            .body("completedDate", is(notNullValue()));
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

        // To trigger calendar directory activation
        calDavClient.export(calendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(calendarURL, eventId, openPaaSUser.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        String taskId = given()
            .queryParam("task", "reindexCalendarEvents")
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
            .body("additionalInformation.processedUserCount", is(2))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.failedUsers", is(ImmutableList.of()));

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
            .build();

        List<EventFields> actual = calendarSearchService.search(AccountId.fromUsername(openPaaSUser.username()), simpleQuery(""))
            .collectList().block();
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst()).isEqualTo(expected);
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

        // To trigger calendar directory activation
        calDavClient.export(calendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(calendarURL, uid1, openPaaSUser.username(), ics).block();
        calDavClient.importCalendar(calendarURL, uid2, openPaaSUser.username(), ics2).block();

        String taskId = given()
            .queryParam("task", "reindexCalendarEvents")
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
            .body("additionalInformation.processedUserCount", is(2))
            .body("additionalInformation.processedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.failedUsers", is(ImmutableList.of()));

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
            .build();

        EventFields expected2 = EventFields.builder()
            .uid(uid1)
            .summary("recur222")
            .location(null)
            .description(null)
            .clazz("PUBLIC")
            .start(Instant.parse("2025-05-15T05:00:00Z")) // Asia/Saigon 12:00 = UTC+7
            .end(Instant.parse("2025-05-15T05:30:00Z"))
            .dtStamp(Instant.parse("2025-05-15T07:39:30Z"))
            .allDay(false)
            .isRecurrentMaster(false)
            .organizer(person)
            .attendees(List.of(person))
            .resources(List.of())
            .calendarURL(calendarURL)
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
            .build();

        List<EventFields> actual = calendarSearchService.search(AccountId.fromUsername(openPaaSUser.username()), simpleQuery(""))
            .collectList().block();
        assertThat(actual).containsExactlyInAnyOrder(expected1, expected2, expected3);
    }

    @Test
    void reindexShouldRemoveOldEvents() {
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());
        AccountId accountId = AccountId.fromUsername(openPaaSUser.username());
        EventFields event1 = EventFields.builder()
            .uid("event-1")
            .summary("Event1")
            .calendarURL(calendarURL)
            .build();
        calendarSearchService.index(accountId, CalendarEvents.of(event1)).block();

        String taskId = given()
            .queryParam("task", "reindexCalendarEvents")
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
            .body("additionalInformation.processedUserCount", is(2))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.failedUsers", is(ImmutableList.of()));

        List<EventFields> actual = calendarSearchService.search(accountId, simpleQuery(""))
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

        // To trigger calendar directory activation
        calDavClient.export(calendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(calendarURL, uid1, openPaaSUser.username(), ics).block();
        calDavClient.importCalendar(calendarURL, uid2, openPaaSUser.username(), ics2).block();

        doAnswer(invocation -> {
            AccountId accountId = invocation.getArgument(0);
            CalendarEvents events = invocation.getArgument(1);
            if (events.eventUid().value().equals(uid2)) {
                return Mono.error(new RuntimeException("Simulated failure for uid2"));
            }
            return Mono.empty();
        }).when(calendarSearchService).index(any(), any());

        String taskId = given()
            .queryParam("task", "reindexCalendarEvents")
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
            .body("additionalInformation.processedUserCount", is(1))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(1))
            .body("additionalInformation.failedUsers", is(ImmutableList.of(openPaaSUser.username().asString())));
    }

    private EventSearchQuery simpleQuery(String query) {
        return new EventSearchQuery(query, Optional.empty(),
            Optional.empty(), Optional.empty(),
            MAX_LIMIT, 0);
    }
}
