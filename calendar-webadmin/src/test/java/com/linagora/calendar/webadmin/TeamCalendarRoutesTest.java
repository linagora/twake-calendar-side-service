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
import static io.restassured.RestAssured.when;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.CalDavExportException;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBTeamCalendarRepository;
import com.linagora.calendar.webadmin.service.CalendarImportService;
import com.linagora.calendar.webadmin.task.DomainCalendarImportTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import net.javacrumbs.jsonunit.core.Option;
import reactor.core.publisher.Mono;

class TeamCalendarRoutesTest {
    private static final Domain DAV_DOMAIN = Domain.of(SabreDavProvisioningService.DOMAIN);

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBTeamCalendarRepository teamCalendarRepository;
    private TeamCalendarService teamCalendarService;
    private UpdatableTickingClock clock;
    private CalDavClient calDavClient;

    @BeforeEach
    void setUp() throws SSLException {
        clock = new UpdatableTickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        Mono.from(mongoDB.getCollection(MongoDBTeamCalendarRepository.COLLECTION).deleteMany(new Document())).block();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        teamCalendarRepository = new MongoDBTeamCalendarRepository(mongoDB, clock);

        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        teamCalendarService = new TeamCalendarService(domainDAO, teamCalendarRepository, calDavClient);
        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new TeamCalendarRoutes(teamCalendarService,
                    new DomainCalendarContentHandler(calDavClient, new CalendarImportService(calDavClient), taskManager),
                    new JsonTransformer()),
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

    @Test
    void createShouldPersistTeamCalendar() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();

        String response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "name": "sales",
                  "displayName": "Sales Team"
                }
                """)
        .when()
            .post("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(201)
            .contentType(ContentType.JSON)
            .header("Location", Matchers.matchesRegex("/domains/linagora.com/team-calendars/[0-9a-f]{24}"))
            .extract()
            .body()
            .asString();

        assertThatJson(response).isEqualTo("""
            {
              "id": "${json-unit.regex}[0-9a-f]{24}",
              "domainId": "%s",
              "domainName": "linagora.com",
              "name": "sales",
              "displayName": "Sales Team",
              "creation": "2026-01-01T00:00:00Z",
              "updated": "2026-01-01T00:00:00Z"
            }""".formatted(domain.id().value()));

        assertThat(teamCalendarRepository.retrieve(domain.id(), "sales").collectList().block())
            .hasSize(1);
    }

    @Test
    void createShouldNotBeIdempotentWhenSamePayloadIsPostedTwice() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        String payload = """
            {
              "name": "sales",
              "displayName": "Sales Team"
            }
            """;

        String firstId = given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");

        String secondId = given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .post("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(teamCalendarRepository.retrieve(domain.id(), "sales").collectList().block())
            .hasSize(2)
            .extracting(TeamCalendar::displayName)
            .containsOnly("Sales Team");
    }

    @Test
    void createShouldAllowSameNameWithDifferentDisplayName() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "name": "sales",
                  "displayName": "Sales Team"
                }
                """)
        .when()
            .post("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "name": "sales",
                  "displayName": "Global Sales"
                }
                """)
        .when()
            .post("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(201);

        assertThat(teamCalendarRepository.retrieve(domain.id(), "sales").collectList().block())
            .hasSize(2)
            .extracting(TeamCalendar::displayName)
            .containsExactlyInAnyOrder("Sales Team", "Global Sales");
    }

    @Test
    void listShouldReturnTeamCalendarsOfDomain() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other.com")).block();
        teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "sales", "Sales Team")).block();
        teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "support", "Support Team")).block();
        teamCalendarRepository.create(new TeamCalendarInsertRequest(otherDomain, "sales", "Other Sales")).block();

        String response = when()
            .get("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .whenIgnoringPaths("[*].id")
            .isEqualTo("""
                [
                  {
                    "id": "ignored",
                    "domainId": "%s",
                    "domainName": "linagora.com",
                    "name": "sales",
                    "displayName": "Sales Team",
                    "creation": "2026-01-01T00:00:00Z",
                    "updated": "2026-01-01T00:00:00Z"
                  },
                  {
                    "id": "ignored",
                    "domainId": "%s",
                    "domainName": "linagora.com",
                    "name": "support",
                    "displayName": "Support Team",
                    "creation": "2026-01-01T00:00:00Z",
                    "updated": "2026-01-01T00:00:00Z"
                  }
                ]""".formatted(domain.id().value(), domain.id().value()));
    }

    @Test
    void listShouldNotReturnTeamCalendarsOfOtherDomains() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other.com")).block();
        teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "sales", "Sales Team")).block();
        teamCalendarRepository.create(new TeamCalendarInsertRequest(otherDomain, "support", "Other Support")).block();

        String response = when()
            .get("/domains/linagora.com/team-calendars")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("[0].id")
            .isEqualTo("""
                [
                  {
                    "id": "ignored",
                    "domainId": "%s",
                    "domainName": "linagora.com",
                    "name": "sales",
                    "displayName": "Sales Team",
                    "creation": "2026-01-01T00:00:00Z",
                    "updated": "2026-01-01T00:00:00Z"
                  }
                ]""".formatted(domain.id().value()));
    }

    @Test
    void getShouldReturnTeamCalendarById() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        TeamCalendarId id = teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "sales", "Sales Team"))
            .map(TeamCalendar::id)
            .block();

        String response = when()
            .get("/domains/linagora.com/team-calendars/" + id.value())
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response).isEqualTo("""
            {
              "id": "%s",
              "domainId": "%s",
              "domainName": "linagora.com",
              "name": "sales",
              "displayName": "Sales Team",
              "creation": "2026-01-01T00:00:00Z",
              "updated": "2026-01-01T00:00:00Z"
            }""".formatted(id.value(), domain.id().value()));
    }

    @Test
    void patchShouldUpdateDisplayName() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        TeamCalendarId id = teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "sales", "Sales Team"))
            .map(TeamCalendar::id)
            .block();
        clock.setInstant(Instant.parse("2026-01-01T00:01:00Z"));

        String response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "displayName": "Global Sales"
                }
                """)
        .when()
            .patch("/domains/linagora.com/team-calendars/" + id.value())
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .inPath("displayName")
            .isEqualTo("Global Sales");
    }

    @Test
    void deleteShouldRemoveTeamCalendar() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        TeamCalendarId id = teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "sales", "Sales Team"))
            .map(TeamCalendar::id)
            .block();

        when()
            .delete("/domains/linagora.com/team-calendars/" + id.value())
        .then()
            .statusCode(204);

        assertThat(teamCalendarRepository.retrieve(id).blockOptional())
            .isEmpty();
    }

    @Test
    void deleteShouldBeIdempotent() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        TeamCalendarId id = teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "sales", "Sales Team"))
            .map(TeamCalendar::id)
            .block();

        when()
            .delete("/domains/linagora.com/team-calendars/" + id.value())
        .then()
            .statusCode(204);

        when()
            .delete("/domains/linagora.com/team-calendars/" + id.value())
        .then()
            .statusCode(204);

        assertThat(teamCalendarRepository.retrieve(id).blockOptional())
            .isEmpty();
    }

    @Test
    void deleteShouldNotRemoveTeamCalendarOfOtherDomain() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other.com")).block();
        TeamCalendarId id = teamCalendarRepository.create(new TeamCalendarInsertRequest(otherDomain, "sales", "Sales Team"))
            .map(TeamCalendar::id)
            .block();

        when()
            .delete("/domains/linagora.com/team-calendars/" + id.value())
        .then()
            .statusCode(204);

        assertThat(teamCalendarRepository.retrieve(id).blockOptional())
            .hasValueSatisfying(teamCalendar -> assertThat(teamCalendar.domain()).isEqualTo(otherDomain));
        assertThat(teamCalendarRepository.listByDomain(domain.id()).collectList().block())
            .isEmpty();
    }

    @Test
    void getShouldReturn404WhenDomainDoesNotExist() {
        when()
            .get("/domains/unknown.com/team-calendars")
        .then()
            .statusCode(404)
            .contentType(ContentType.JSON);
    }

    @Test
    void getShouldReturn400WhenDomainIsInvalid() {
        when()
            .get("/domains/linagor@.com/team-calendars")
        .then()
            .statusCode(400)
            .contentType(ContentType.JSON);
    }

    @Test
    void eventCountShouldReturnZeroWhenCalendarIsEmpty() {
        String calendarId = createDavTeamCalendar();

        given()
        .when()
            .get("/domains/{domain}/team-calendars/{calendarId}/eventCount", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    @Test
    void eventCountShouldReturnTheNumberOfEventsOfTheCalendar() {
        String calendarId = createDavTeamCalendar();
        awaitTask(importCalendar(calendarId, icsOf(
            event("counted-1", "First event"),
            event("counted-2", "Second event"))));

        given()
        .when()
            .get("/domains/{domain}/team-calendars/{calendarId}/eventCount", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(200)
            .body("count", is(2));
    }

    @Test
    void eventCountShouldReturn404WhenCalendarDoesNotExist() {
        given()
        .when()
            .get("/domains/{domain}/team-calendars/{calendarId}/eventCount", DAV_DOMAIN.asString(), unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void eventCountShouldReturn404WhenDomainDoesNotExist() {
        given()
        .when()
            .get("/domains/unknown.tld/team-calendars/{calendarId}/eventCount", unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void exportShouldReturnTheEventsOfTheCalendar() {
        String calendarId = createDavTeamCalendar();
        awaitTask(importCalendar(calendarId, icsOf(event("exported-event", "Sprint review"))));

        assertThat(exportCalendar(calendarId))
            .contains("UID:exported-event")
            .contains("SUMMARY:Sprint review");
    }

    @Test
    void publicRightShouldGrantPublicReadRight() {
        String calendarId = createDavTeamCalendar();

        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}/publicRight", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(204);

        assertThat(authenticatedPrincipalPrivileges(calendarId))
            .contains("{DAV:}read");
    }

    @Test
    void publicReadRightShouldLetAnyUserOfTheDomainReadEvents() {
        String calendarId = createDavTeamCalendar();
        upsertEvent(calendarId, "public-event", "Sprint review");
        OpenPaaSUser randomUser = sabreDavExtension.newTestUser();

        updatePublicRight(calendarId, "{DAV:}read");

        assertThat(new String(calDavClient.export(teamCalendarURL(calendarId), randomUser.username()).block(), StandardCharsets.UTF_8))
            .contains("UID:public-event")
            .contains("SUMMARY:Sprint review");
    }

    @Test
    void publicReadRightShouldNotLetUsersOfTheDomainWriteEvents() {
        String calendarId = createDavTeamCalendar();
        OpenPaaSUser randomUser = sabreDavExtension.newTestUser();

        updatePublicRight(calendarId, "{DAV:}read");

        assertThatThrownBy(() -> sabreDavExtension.davTestHelper()
                .upsertCalendar(randomUser.username(), eventURI(calendarId, "intruder-event"), icsOf(event("intruder-event", "Intrusion")))
                .block())
            .isInstanceOf(DavClientException.class);
    }

    @Test
    void usersOfTheDomainShouldNotReadEventsOnceHidden() {
        String calendarId = createDavTeamCalendar();
        upsertEvent(calendarId, "hidden-event", "Sprint review");
        OpenPaaSUser randomUser = sabreDavExtension.newTestUser();
        updatePublicRight(calendarId, "{DAV:}read");

        updatePublicRight(calendarId, "");

        assertThatThrownBy(() -> calDavClient.export(teamCalendarURL(calendarId), randomUser.username()).block())
            .isInstanceOf(CalDavExportException.class);
    }

    @Test
    void publicRightShouldRemovePublicRights() {
        String calendarId = createDavTeamCalendar();
        updatePublicRight(calendarId, "{DAV:}read");

        updatePublicRight(calendarId, "");

        assertThat(authenticatedPrincipalPrivileges(calendarId))
            .doesNotContain("{DAV:}read");
    }

    @Test
    void publicRightShouldRejectUnsupportedValue() {
        String calendarId = createDavTeamCalendar();

        given()
            .body("""
                {"public_right":"{DAV:}all"}
                """)
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}/publicRight", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void publicRightShouldRejectMissingValue() {
        String calendarId = createDavTeamCalendar();

        given()
            .body("{}")
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}/publicRight", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void publicRightShouldReturn404WhenCalendarDoesNotExist() {
        createDavTeamCalendar();

        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}/publicRight", DAV_DOMAIN.asString(), unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void publicRightShouldReturn404WhenDomainDoesNotExist() {
        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/domains/unknown.tld/team-calendars/{calendarId}/publicRight", unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void exportShouldReturn404WhenCalendarDoesNotExist() {
        given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void exportShouldReturn400WhenActionIsNotSupported() {
        String calendarId = createDavTeamCalendar();

        given()
            .queryParam("action", "unsupported")
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPORT", "Export", "eXpOrT"})
    void actionShouldBeCaseInsensitive(String action) {
        String calendarId = createDavTeamCalendar();

        given()
            .queryParam("action", action)
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(200);
    }

    @Test
    void importShouldAddEventsToTheCalendar() {
        String calendarId = createDavTeamCalendar();

        awaitTask(importCalendar(calendarId, icsOf(
            event("imported-1", "First imported event"),
            event("imported-2", "Second imported event"))));

        assertThat(exportCalendar(calendarId))
            .contains("UID:imported-1")
            .contains("SUMMARY:First imported event")
            .contains("UID:imported-2")
            .contains("SUMMARY:Second imported event");
    }

    @Test
    void importShouldReturnCompletedTaskDetails() {
        String calendarId = createDavTeamCalendar();

        String taskId = importCalendar(calendarId, icsOf(
            event("imported-1", "First imported event"),
            event("imported-2", "Second imported event")));

        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("domain-calendar-import"))
            .body("additionalInformation.domain", is(DAV_DOMAIN.asString()))
            .body("additionalInformation.calendarType", is("team-calendar"))
            .body("additionalInformation.calendarId", is(calendarId))
            .body("additionalInformation.totalEventCount", is(2))
            .body("additionalInformation.importedEventCount", is(2))
            .body("additionalInformation.failedEventCount", is(0));
    }

    @Test
    void importShouldOverwriteEventsSharingTheSameUid() {
        String calendarId = createDavTeamCalendar();

        awaitTask(importCalendar(calendarId, icsOf(event("imported-1", "Initial summary"))));
        awaitTask(importCalendar(calendarId, icsOf(event("imported-1", "Updated summary"))));

        given()
        .when()
            .get("/domains/{domain}/team-calendars/{calendarId}/eventCount", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(200)
            .body("count", is(1));
        assertThat(exportCalendar(calendarId))
            .contains("SUMMARY:Updated summary")
            .doesNotContain("SUMMARY:Initial summary");
    }

    @Test
    void importShouldReturn400WhenBodyIsNotAValidIcs() {
        String calendarId = createDavTeamCalendar();

        given()
            .queryParam("action", "import")
            .body("not an ICS document")
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void importShouldReturn400WhenNoEventToImport() {
        String calendarId = createDavTeamCalendar();

        given()
            .queryParam("action", "import")
            .body(icsOf())
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Invalid request body: no event to import"));
    }

    @Test
    void importShouldReturn404WhenCalendarDoesNotExist() {
        given()
            .queryParam("action", "import")
            .body(icsOf(event("imported-1", "First imported event")))
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), unknownCalendarId())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void exportedContentShouldBeImportableAsIs() {
        String sourceCalendarId = createDavTeamCalendar();
        String targetCalendarId = createDavTeamCalendar();
        awaitTask(importCalendar(sourceCalendarId, icsOf(
            event("round-trip-1", "First event"),
            event("round-trip-2", "Second event"))));

        awaitTask(importCalendar(targetCalendarId, exportCalendar(sourceCalendarId)));

        assertThat(exportCalendar(targetCalendarId))
            .contains("UID:round-trip-1")
            .contains("UID:round-trip-2");
    }

    @Test
    void contentRoutesShouldIgnoreTeamCalendarsOfOtherDomains() {
        OpenPaaSDomain otherDomain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of("other-" + UUID.randomUUID() + ".tld"))
            .block();
        String calendarId = createDavTeamCalendar();

        given()
        .when()
            .get("/domains/{domain}/team-calendars/{calendarId}/eventCount", otherDomain.domain().asString(), calendarId)
        .then()
            .statusCode(404)
            .body("message", is("Team calendar does not exist"));
    }

    private String createDavTeamCalendar() {
        sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(DAV_DOMAIN)
            .block();

        return teamCalendarService.create(DAV_DOMAIN, "team-" + UUID.randomUUID(), "Team calendar")
            .block()
            .id()
            .value();
    }

    private void updatePublicRight(String calendarId, String publicRight) {
        given()
            .body("""
                {"public_right":"%s"}
                """.formatted(publicRight))
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}/publicRight", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(204);
    }

    private void upsertEvent(String calendarId, String eventUid, String summary) {
        OpenPaaSDomain domain = domainDAO.retrieve(DAV_DOMAIN).block();
        sabreDavExtension.davTestHelper()
            .upsertCalendar(domain.id(), eventURI(calendarId, eventUid), icsOf(event(eventUid, summary)))
            .block();
    }

    private URI eventURI(String calendarId, String eventUid) {
        return URI.create(teamCalendarURL(calendarId).asUri() + "/" + eventUid + ".ics");
    }

    private CalendarURL teamCalendarURL(String calendarId) {
        return CalendarURL.from(new OpenPaaSId(calendarId));
    }

    private List<String> authenticatedPrincipalPrivileges(String calendarId) {
        OpenPaaSDomain domain = domainDAO.retrieve(DAV_DOMAIN).block();
        String metadata = sabreDavExtension.davTestHelper()
            .getCalendarMetadata(domain.id(), teamCalendarURL(calendarId))
            .block();
        return JsonPath.from(metadata)
            .getList("acl.findAll { it.principal == '{DAV:}authenticated' }.privilege");
    }

    private String unknownCalendarId() {
        return new ObjectId().toHexString();
    }

    private String exportCalendar(String calendarId) {
        return given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), calendarId)
        .then()
            .statusCode(200)
            .extract()
            .asString();
    }

    private String importCalendar(String calendarId, String ics) {
        return given()
            .queryParam("action", "import")
            .body(ics)
        .when()
            .post("/domains/{domain}/team-calendars/{calendarId}", DAV_DOMAIN.asString(), calendarId)
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
