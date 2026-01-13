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
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.net.ssl.SSLException;

import jakarta.mail.internet.AddressException;

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
import com.linagora.calendar.api.EventEmailFilter;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.AlarmEventFactory;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MemoryAlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.AlarmScheduleService;
import com.linagora.calendar.webadmin.task.AlarmScheduleTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import reactor.core.publisher.Mono;

public class AlarmScheduleTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private AlarmEventDAO alarmEventDAO;
    private CalDavClient calDavClient;
    private AlarmScheduleService alarmScheduleService;
    private UpdatableTickingClock clock;

    private OpenPaaSUser openPaaSUser;
    private OpenPaaSUser openPaaSUser2;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        alarmEventDAO = spy(new MemoryAlarmEventDAO());
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        clock = new UpdatableTickingClock(Instant.now());
        alarmScheduleService = new AlarmScheduleService(userDAO, calDavClient, alarmEventDAO, new AlarmInstantFactory.Default(clock),
            new AlarmEventFactory.Default(),
            EventEmailFilter.acceptAll());

        this.openPaaSUser = sabreDavExtension.newTestUser();
        this.openPaaSUser2 = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));

        webAdminServer = WebAdminUtils.createWebAdminServer(new CalendarRoutes(new JsonTransformer(),
                taskManager,
                ImmutableSet.of(new CalendarRoutes.AlarmScheduleRequestToTask(alarmScheduleService))),
            new TasksRoutes(taskManager,
                new JsonTransformer(),
                new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                    .add(AlarmScheduleTaskAdditionalInformationDTO.module())
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
            .queryParam("task", "scheduleAlarms")
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
            .body("type", is("schedule-alarms"))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("schedule-alarms"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()))
            .body("completedDate", is(notNullValue()));
    }

    @Test
    void scheduleShouldCreateAlarmEvent() throws AddressException {
        String eventId = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventId)
            .replace("{organizerEmail}", openPaaSUser.username().asString())
            .replace("{attendeeEmail}", openPaaSUser2.username().asString());
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        // To trigger calendar directory activation
        calDavClient.export(calendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(calendarURL, eventId, openPaaSUser.username(), ics.getBytes(StandardCharsets.UTF_8)).block();

        String taskId = given()
            .queryParam("task", "scheduleAlarms")
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
            .body("type", is("schedule-alarms"))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(0));

        AlarmEvent alarmEvent = alarmEventDAO.find(new EventUid(eventId), openPaaSUser.username().asMailAddress()).block();

        assertSoftly(softly -> {
            softly.assertThat(alarmEvent.eventUid().value()).isEqualTo(eventId);
            softly.assertThat(alarmEvent.alarmTime()).isEqualTo(parse("30250801T094500Z"));
            softly.assertThat(alarmEvent.eventStartTime()).isEqualTo(parse("30250801T100000Z"));
            softly.assertThat(alarmEvent.recurring()).isFalse();
            softly.assertThat(alarmEvent.recipient().asString()).isEqualTo(openPaaSUser.username().asString());
        });
    }

    @Test
    void scheduleShouldCreateMultipleAlarmEvents() throws AddressException {
        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            RRULE:FREQ=DAILY;COUNT=3
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event With Recurrence ID
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            RECURRENCE-ID:30250801T100000Z
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", uid1)
            .replace("{organizerEmail}", openPaaSUser.username().asString())
            .replace("{attendeeEmail}", openPaaSUser2.username().asString());
        String ics2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            BEGIN:VALARM
            TRIGGER:-PT30M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", uid2)
            .replace("{organizerEmail}", openPaaSUser.username().asString())
            .replace("{attendeeEmail}", openPaaSUser2.username().asString());
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        // To trigger calendar directory activation
        calDavClient.export(calendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(calendarURL, uid1, openPaaSUser.username(), ics.getBytes(StandardCharsets.UTF_8)).block();
        calDavClient.importCalendar(calendarURL, uid2, openPaaSUser.username(), ics2.getBytes(StandardCharsets.UTF_8)).block();

        clock.setInstant(parse("30250701T100000Z"));

        String taskId = given()
            .queryParam("task", "scheduleAlarms")
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
            .body("type", is("schedule-alarms"))
            .body("additionalInformation.processedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));

        AlarmEvent alarmEvent = alarmEventDAO.find(new EventUid(uid1), openPaaSUser.username().asMailAddress()).block();
        AlarmEvent alarmEvent2 = alarmEventDAO.find(new EventUid(uid2), openPaaSUser.username().asMailAddress()).block();

        assertSoftly(softly -> {
            softly.assertThat(alarmEvent.eventUid().value()).isEqualTo(uid1);
            softly.assertThat(alarmEvent.alarmTime()).isEqualTo(parse("30250801T094500Z"));
            softly.assertThat(alarmEvent.eventStartTime()).isEqualTo(parse("30250801T100000Z"));
            softly.assertThat(alarmEvent.recurring()).isTrue();
            softly.assertThat(alarmEvent.recurrenceId().get()).isEqualTo("30250801T100000Z");
            softly.assertThat(alarmEvent.recipient().asString()).isEqualTo(openPaaSUser.username().asString());

            softly.assertThat(alarmEvent2.eventUid().value()).isEqualTo(uid2);
            softly.assertThat(alarmEvent2.alarmTime()).isEqualTo(parse("30250801T093000Z"));
            softly.assertThat(alarmEvent2.eventStartTime()).isEqualTo(parse("30250801T100000Z"));
            softly.assertThat(alarmEvent2.recurring()).isFalse();
            softly.assertThat(alarmEvent2.recipient().asString()).isEqualTo(openPaaSUser.username().asString());
        });
    }

    @Test
    void scheduleShouldReportFailedEventCount() {
        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            RRULE:FREQ=DAILY;COUNT=3
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event With Recurrence ID
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            RECURRENCE-ID:30250801T100000Z
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", uid1)
            .replace("{organizerEmail}", openPaaSUser.username().asString())
            .replace("{attendeeEmail}", openPaaSUser2.username().asString());
        String ics2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Test Attendee:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            BEGIN:VALARM
            TRIGGER:-PT30M
            ACTION:EMAIL
            ATTENDEE:mailto:{organizerEmail}
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", uid2)
            .replace("{organizerEmail}", openPaaSUser.username().asString())
            .replace("{attendeeEmail}", openPaaSUser2.username().asString());
        CalendarURL calendarURL = CalendarURL.from(openPaaSUser.id());

        // To trigger calendar directory activation
        calDavClient.export(calendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(calendarURL, uid1, openPaaSUser.username(), ics.getBytes(StandardCharsets.UTF_8)).block();
        calDavClient.importCalendar(calendarURL, uid2, openPaaSUser.username(), ics2.getBytes(StandardCharsets.UTF_8)).block();

        clock.setInstant(parse("30250701T100000Z"));

        doAnswer(invocation -> {
            AlarmEvent alarmEvent = invocation.getArgument(0);
            if (alarmEvent.eventUid().value().equals(uid2)) {
                return Mono.error(new RuntimeException("Simulated failure for uid2"));
            }
            return Mono.empty();
        }).when(alarmEventDAO).create(any());

        String taskId = given()
            .queryParam("task", "scheduleAlarms")
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
            .body("type", is("schedule-alarms"))
            .body("additionalInformation.processedEventCount", is(1))
            .body("additionalInformation.failedEventCount", is(1));
    }

    private Instant parse(String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");
        return ZonedDateTime.parse(date, formatter).toInstant();
    }
}
