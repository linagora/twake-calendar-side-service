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

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.apache.james.core.Domain;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBTeamCalendarRepository;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class TeamCalendarRoutesTest {
    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(
        MongoDBOpenPaaSDomainDAO.COLLECTION,
        MongoDBTeamCalendarRepository.COLLECTION));

    private WebAdminServer webAdminServer;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBTeamCalendarRepository teamCalendarRepository;
    private UpdatableTickingClock clock;

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        teamCalendarRepository = new MongoDBTeamCalendarRepository(mongo.getDb(), clock);

        TeamCalendarService teamCalendarService = new TeamCalendarService(domainDAO, teamCalendarRepository);
        webAdminServer = WebAdminUtils.createWebAdminServer(new TeamCalendarRoutes(teamCalendarService, new JsonTransformer()))
            .start();

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
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
}
