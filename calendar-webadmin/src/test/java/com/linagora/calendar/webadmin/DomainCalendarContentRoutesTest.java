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

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.ResourceService;
import com.linagora.calendar.dav.ResourceService.ResourceAdministrator;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.linagora.calendar.storage.mongodb.MongoDBTeamCalendarRepository;
import com.linagora.calendar.webadmin.service.CalendarImportService;
import com.linagora.calendar.webadmin.task.DomainCalendarImportTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;

class DomainCalendarContentRoutesTest {

    /**
     * The two families of calendars a domain owns. They are served by the same handlers, hence the tests
     * covering their shared behaviour run against both.
     */
    enum CalendarFamily {
        TEAM_CALENDAR("team-calendars", "team-calendar"),
        RESOURCE("resources", "resource");

        private final String pathSegment;
        private final String taskCalendarType;

        CalendarFamily(String pathSegment, String taskCalendarType) {
            this.pathSegment = pathSegment;
            this.taskCalendarType = taskCalendarType;
        }
    }

    private static final Domain DOMAIN = Domain.of(SabreDavProvisioningService.DOMAIN);

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private OpenPaaSDomain domain;
    private TeamCalendarService teamCalendarService;
    private ResourceService resourceService;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(DOMAIN)
            .block();

        CalDavClient calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        teamCalendarService = new TeamCalendarService(domainDAO,
            new MongoDBTeamCalendarRepository(mongoDB, Clock.systemUTC()), calDavClient);
        resourceService = new ResourceService(userDAO, new MongoDBResourceDAO(mongoDB, Clock.systemUTC()), calDavClient);

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainCalendarContentRoutes(domainDAO, teamCalendarService, resourceService, calDavClient,
                    new CalendarImportService(calDavClient), taskManager),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(DomainCalendarImportTaskAdditionalInformationDTO.module())
                        .build())))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void eventCountShouldReturnZeroWhenCalendarIsEmpty(CalendarFamily family) {
        String calendarId = createCalendar(family);

        given()
        .when()
            .get("/domains/{domain}/{family}/{calendarId}/eventCount", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void eventCountShouldReturnTheNumberOfEventsOfTheCalendar(CalendarFamily family) {
        String calendarId = createCalendar(family);
        awaitTask(importCalendar(family, calendarId, icsOf(
            event("counted-1", "First event"),
            event("counted-2", "Second event"))));

        given()
        .when()
            .get("/domains/{domain}/{family}/{calendarId}/eventCount", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(200)
            .body("count", is(2));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void eventCountShouldReturn404WhenCalendarDoesNotExist(CalendarFamily family) {
        given()
        .when()
            .get("/domains/{domain}/{family}/{calendarId}/eventCount", DOMAIN.asString(), family.pathSegment, unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void eventCountShouldReturn404WhenDomainDoesNotExist(CalendarFamily family) {
        given()
        .when()
            .get("/domains/unknown.tld/{family}/{calendarId}/eventCount", family.pathSegment, unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void exportShouldReturnTheEventsOfTheCalendar(CalendarFamily family) {
        String calendarId = createCalendar(family);
        awaitTask(importCalendar(family, calendarId, icsOf(event("exported-event", "Sprint review"))));

        assertThat(exportCalendar(family, calendarId))
            .contains("UID:exported-event")
            .contains("SUMMARY:Sprint review");
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void exportShouldReturn404WhenCalendarDoesNotExist(CalendarFamily family) {
        given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void exportShouldReturn400WhenActionIsNotSupported(CalendarFamily family) {
        String calendarId = createCalendar(family);

        given()
            .queryParam("action", "unsupported")
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void importShouldAddEventsToTheCalendar(CalendarFamily family) {
        String calendarId = createCalendar(family);

        awaitTask(importCalendar(family, calendarId, icsOf(
            event("imported-1", "First imported event"),
            event("imported-2", "Second imported event"))));

        assertThat(exportCalendar(family, calendarId))
            .contains("UID:imported-1")
            .contains("SUMMARY:First imported event")
            .contains("UID:imported-2")
            .contains("SUMMARY:Second imported event");
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void importShouldReturnCompletedTaskDetails(CalendarFamily family) {
        String calendarId = createCalendar(family);

        String taskId = importCalendar(family, calendarId, icsOf(
            event("imported-1", "First imported event"),
            event("imported-2", "Second imported event")));

        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("domain-calendar-import"))
            .body("additionalInformation.domain", is(DOMAIN.asString()))
            .body("additionalInformation.calendarType", is(family.taskCalendarType))
            .body("additionalInformation.calendarId", is(calendarId))
            .body("additionalInformation.totalEventCount", is(2))
            .body("additionalInformation.importedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void importShouldOverwriteEventsSharingTheSameUid(CalendarFamily family) {
        String calendarId = createCalendar(family);

        awaitTask(importCalendar(family, calendarId, icsOf(event("imported-1", "Initial summary"))));
        awaitTask(importCalendar(family, calendarId, icsOf(event("imported-1", "Updated summary"))));

        given()
        .when()
            .get("/domains/{domain}/{family}/{calendarId}/eventCount", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(200)
            .body("count", is(1));
        assertThat(exportCalendar(family, calendarId))
            .contains("SUMMARY:Updated summary")
            .doesNotContain("SUMMARY:Initial summary");
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void importShouldReturn400WhenBodyIsNotAValidIcs(CalendarFamily family) {
        String calendarId = createCalendar(family);

        given()
            .queryParam("action", "import")
            .body("not an ICS document")
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void importShouldReturn400WhenNoEventToImport(CalendarFamily family) {
        String calendarId = createCalendar(family);

        given()
            .queryParam("action", "import")
            .body(icsOf())
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Invalid request body: no event to import"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void importShouldReturn404WhenCalendarDoesNotExist(CalendarFamily family) {
        given()
            .queryParam("action", "import")
            .body(icsOf(event("imported-1", "First imported event")))
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @ParameterizedTest
    @EnumSource(CalendarFamily.class)
    void exportedContentShouldBeImportableAsIs(CalendarFamily family) {
        String sourceCalendarId = createCalendar(family);
        String targetCalendarId = createCalendar(family);
        awaitTask(importCalendar(family, sourceCalendarId, icsOf(
            event("round-trip-1", "First event"),
            event("round-trip-2", "Second event"))));

        awaitTask(importCalendar(family, targetCalendarId, exportCalendar(family, sourceCalendarId)));

        assertThat(exportCalendar(family, targetCalendarId))
            .contains("UID:round-trip-1")
            .contains("UID:round-trip-2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPORT", "Export", "eXpOrT"})
    void actionShouldBeCaseInsensitive(String action) {
        String calendarId = createCalendar(CalendarFamily.TEAM_CALENDAR);

        given()
            .queryParam("action", action)
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DOMAIN.asString(), calendarId)
        .then()
            .statusCode(200);
    }

    @Test
    void exportShouldSucceedWhenResourceIsMarkedAsDeleted() {
        String resourceId = createCalendar(CalendarFamily.RESOURCE);
        awaitTask(importCalendar(CalendarFamily.RESOURCE, resourceId, icsOf(event("kept-event", "Kept event"))));
        deleteResource(resourceId);

        assertThat(exportCalendar(CalendarFamily.RESOURCE, resourceId))
            .contains("UID:kept-event");
    }

    @Test
    void importShouldReturn404WhenResourceIsMarkedAsDeleted() {
        String resourceId = createCalendar(CalendarFamily.RESOURCE);
        deleteResource(resourceId);

        given()
            .queryParam("action", "import")
            .body(icsOf(event("imported-1", "First imported event")))
        .when()
            .post("/domains/{domain}/resources/{calendarId}", DOMAIN.asString(), resourceId)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Resource does not exist"));
    }

    @Test
    void teamCalendarRoutesShouldIgnoreTeamCalendarsOfOtherDomains() {
        OpenPaaSDomain otherDomain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of("other-" + UUID.randomUUID() + ".tld"))
            .block();
        String calendarId = createCalendar(CalendarFamily.TEAM_CALENDAR);

        given()
        .when()
            .get("/domains/{domain}/team-calendars/{calendarId}/eventCount", otherDomain.domain().asString(), calendarId)
        .then()
            .statusCode(404)
            .body("message", is("Team calendar does not exist"));
    }

    @Test
    void resourceRoutesShouldIgnoreResourcesOfOtherDomains() {
        OpenPaaSDomain otherDomain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of("other-" + UUID.randomUUID() + ".tld"))
            .block();
        String resourceId = createCalendar(CalendarFamily.RESOURCE);

        given()
        .when()
            .get("/domains/{domain}/resources/{calendarId}/eventCount", otherDomain.domain().asString(), resourceId)
        .then()
            .statusCode(404)
            .body("message", is("Resource does not exist"));
    }

    private String createCalendar(CalendarFamily family) {
        return switch (family) {
            case TEAM_CALENDAR -> createTeamCalendar().id().value();
            case RESOURCE -> createResource().value();
        };
    }

    private TeamCalendar createTeamCalendar() {
        return teamCalendarService.create(DOMAIN, "team-" + UUID.randomUUID(), "Team calendar").block();
    }

    private ResourceId createResource() {
        OpenPaaSUser administrator = sabreDavExtension.newTestUser();
        return resourceService.create(
                new ResourceInsertRequest(administrator.id(), "A meeting room", domain.id(), "laptop", "Room " + UUID.randomUUID()),
                List.of(new ResourceAdministrator(administrator.username(), DavRight.ADMINISTRATION)))
            .block();
    }

    private void deleteResource(String resourceId) {
        Resource resource = resourceService.retrieve(new ResourceId(resourceId), ResourceService.ONLY_ACTIVE).block();
        resourceService.delete(resource).block();
    }

    private String unknownCalendarId() {
        return new ObjectId().toHexString();
    }

    private String exportCalendar(CalendarFamily family, String calendarId) {
        return given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(200)
            .extract()
            .asString();
    }

    private String importCalendar(CalendarFamily family, String calendarId, String ics) {
        return given()
            .queryParam("action", "import")
            .body(ics)
        .when()
            .post("/domains/{domain}/{family}/{calendarId}", DOMAIN.asString(), family.pathSegment, calendarId)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
    }

    private void awaitTask(String taskId) {
        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"));
    }

    private String icsOf(String... vEvents) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Linagora//Twake Calendar//EN
            %sEND:VCALENDAR
            """.formatted(String.join("", vEvents));
    }

    private String event(String eventUid, String summary) {
        return """
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260601T080000Z
            DTSTART:20260601T100000Z
            DTEND:20260601T110000Z
            SUMMARY:%s
            END:VEVENT
            """.formatted(eventUid, summary);
    }
}
