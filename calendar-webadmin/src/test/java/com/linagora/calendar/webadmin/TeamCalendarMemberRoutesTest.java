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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the    *
 *  GNU Affero General Public License for more details.             *
 ********************************************************************/

package com.linagora.calendar.webadmin;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.time.Clock;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBTeamCalendarRepository;
import com.linagora.calendar.webadmin.service.TeamCalendarMemberService;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class TeamCalendarMemberRoutesTest {
    private static final Domain DOMAIN = Domain.of(SabreDavProvisioningService.DOMAIN);

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private OpenPaaSDomain domain;
    private MongoDBTeamCalendarRepository teamCalendarRepository;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB());
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), domainDAO);
        domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(DOMAIN)
            .block();
        teamCalendarRepository = new MongoDBTeamCalendarRepository(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), Clock.systemUTC());

        CalDavClient calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        TeamCalendarService teamCalendarService = new TeamCalendarService(domainDAO, teamCalendarRepository);
        TeamCalendarMemberService teamCalendarMemberService = new TeamCalendarMemberService(calDavClient);

        webAdminServer = WebAdminUtils.createWebAdminServer(
                new TeamCalendarMemberRoutes(teamCalendarService, teamCalendarMemberService, userDAO, new JsonTransformer()))
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
    void listShouldReturnTeamCalendarMembers() {
        // Given a team calendar with one member for each supported DAV right.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser viewer = sabreDavExtension.newTestUser();
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSUser manager = sabreDavExtension.newTestUser();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${viewer}", "dav:read": true},
                      {"dav:href": "mailto:${member}", "dav:read-write": true},
                      {"dav:href": "mailto:${manager}", "dav:administration": true}
                    ]
                  }
                }
                """
                .replace("${viewer}", viewer.username().asString())
                .replace("${member}", member.username().asString())
                .replace("${manager}", manager.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // When the WebAdmin route lists the team calendar members.
        String response = when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        // Then the response exposes both the member role and the DAV right.
        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  {
                    "username": "${viewer}",
                    "role": "viewer",
                    "davRight": "dav:read"
                  },
                  {
                    "username": "${member}",
                    "role": "member",
                    "davRight": "dav:read-write"
                  },
                  {
                    "username": "${manager}",
                    "role": "manager",
                    "davRight": "dav:administration"
                  }
                ]
                """
                .replace("${viewer}", viewer.username().asString())
                .replace("${member}", member.username().asString())
                .replace("${manager}", manager.username().asString()));
    }

    @Test
    void inviteeShouldSupportAddNewMemberRequest() {
        // Given a team calendar already shared with one member.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser existingMember = sabreDavExtension.newTestUser();
        OpenPaaSUser newMember = sabreDavExtension.newTestUser();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${existingMember}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${existingMember}", existingMember.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // When the WebAdmin route receives a set-only invitee update.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${newMember}", "dav:read-write": true}
                    ]
                  }
                }
                """
                .replace("${newMember}", newMember.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // Then the existing member remains and the new member is appended.
        String response = when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  {
                    "username": "${existingMember}",
                    "role": "viewer",
                    "davRight": "dav:read"
                  },
                  {
                    "username": "${newMember}",
                    "role": "member",
                    "davRight": "dav:read-write"
                  }
                ]
                """
                .replace("${existingMember}", existingMember.username().asString())
                .replace("${newMember}", newMember.username().asString()));
    }

    @Test
    void inviteeShouldSupportRemoveMemberRequest() {
        // Given a team calendar already shared with two members.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser kept = sabreDavExtension.newTestUser();
        OpenPaaSUser removed = sabreDavExtension.newTestUser();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${kept}", "dav:read-write": true},
                      {"dav:href": "mailto:${removed}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${kept}", kept.username().asString())
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // When the WebAdmin route receives a remove-only invitee update.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "remove": [
                      {"dav:href": "mailto:${removed}"}
                    ]
                  }
                }
                """
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // Then only the untouched member remains.
        String response = when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                [
                  {
                    "username": "${kept}",
                    "role": "member",
                    "davRight": "dav:read-write"
                  }
                ]
                """
                .replace("${kept}", kept.username().asString()));
    }

    @Test
    void inviteeShouldSupportIdempotentRemoveMemberRequest() {
        // Given a team calendar already shared with one member.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser removed = sabreDavExtension.newTestUser();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${removed}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "remove": [
                      {"dav:href": "mailto:${removed}"}
                    ]
                  }
                }
                """
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // When the same remove-only invitee update is retried.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "remove": [
                      {"dav:href": "mailto:${removed}"}
                    ]
                  }
                }
                """
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);
    }

    @Test
    void inviteeShouldSupportSetAndRemoveInTheSameRequest() {
        // Given a team calendar already shared with two members.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser viewer = sabreDavExtension.newTestUser();
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSUser removed = sabreDavExtension.newTestUser();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read-write": true},
                      {"dav:href": "mailto:${removed}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString())
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // When one request adds a new member and removes an existing member.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${viewer}", "dav:read": true}
                    ],
                    "remove": [
                      {"dav:href": "mailto:${removed}"}
                    ]
                  }
                }
                """
                .replace("${viewer}", viewer.username().asString())
                .replace("${removed}", removed.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(204);

        // Then the previous untouched member remains and the removed member is gone.
        String response = when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  {
                    "username": "${viewer}",
                    "role": "viewer",
                    "davRight": "dav:read"
                  },
                  {
                    "username": "${member}",
                    "role": "member",
                    "davRight": "dav:read-write"
                  }
                ]
                """
                .replace("${viewer}", viewer.username().asString())
                .replace("${member}", member.username().asString()));
    }

    @Test
    void listShouldReturn404WhenDomainDoesNotExist() {
        // Given a valid team calendar and a missing requested domain.
        TeamCalendar teamCalendar = createTeamCalendar();
        String notFoundDomain = "missing.org";

        // When listing members, then the WebAdmin route reports the missing domain.
        when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", notFoundDomain, teamCalendar.id().value())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is(notFoundDomain + " does not exist"));
    }

    @Test
    void listShouldReturn400WhenDomainIsInvalid() {
        // Given a valid team calendar and an invalid domain path parameter.
        TeamCalendar teamCalendar = createTeamCalendar();
        String invalidDomain = "invalid@domain";

        // When listing members, then the WebAdmin route rejects the domain syntax.
        when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", invalidDomain, teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Invalid domain: " + invalidDomain));
    }

    @Test
    void listShouldReturn404WhenTeamCalendarDoesNotExist() {
        // When listing members for an unknown team calendar, then the route returns not found.
        String unknownTeamCalendarId = "64f1c2000000000000000000";

        when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", DOMAIN.asString(), unknownTeamCalendarId)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Team calendar does not exist: " + unknownTeamCalendarId));
    }

    @Test
    void listShouldReturn404WhenTeamCalendarIdIsInvalid() {
        // When listing members with an invalid team calendar id, then the route returns not found.
        String invalidTeamCalendarId = "not-an-object-id";

        when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", DOMAIN.asString(), invalidTeamCalendarId)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Team calendar does not exist: " + invalidTeamCalendarId));
    }

    @Test
    void listShouldReturn404WhenTeamCalendarDoesNotBelongToDomain() {
        // Given a team calendar owned by another domain.
        TeamCalendar teamCalendar = createTeamCalendar();
        Domain otherDomain = Domain.of("other.org");
        createDomain(otherDomain);

        // When listing members through the wrong domain, then the route hides the team calendar.
        when()
            .get("/domains/{domain}/team-calendars/{teamCalendarId}/members", otherDomain.asString(), teamCalendar.id().value())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Team calendar does not exist: " + teamCalendar.id().value()));
    }

    @Test
    void inviteeShouldReturn404WhenDomainDoesNotExist() {
        // Given a valid update payload targeting a missing domain.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        String notFoundDomain = "missing.org";

        // When updating invitees, then the WebAdmin route reports the missing domain.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", notFoundDomain, teamCalendar.id().value())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is(notFoundDomain + " does not exist"));
    }

    @Test
    void inviteeShouldReturn400WhenDomainIsInvalid() {
        // Given a valid update payload and an invalid domain path parameter.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        String invalidDomain = "invalid@domain";

        // When updating invitees, then the WebAdmin route rejects the domain syntax.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", invalidDomain, teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Invalid domain: " + invalidDomain));
    }

    @Test
    void inviteeShouldReturn404WhenTeamCalendarDoesNotExist() {
        // Given a valid update payload for an unknown team calendar.
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        String unknownTeamCalendarId = "64f1c2000000000000000000";

        // When updating invitees, then the WebAdmin route returns not found.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), unknownTeamCalendarId)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Team calendar does not exist: " + unknownTeamCalendarId));
    }

    @Test
    void inviteeShouldReturn404WhenTeamCalendarIdIsInvalid() {
        // Given a valid update payload and an invalid team calendar id.
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        String invalidTeamCalendarId = "not-an-object-id";

        // When updating invitees, then the WebAdmin route returns not found.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), invalidTeamCalendarId)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Team calendar does not exist: " + invalidTeamCalendarId));
    }

    @Test
    void inviteeShouldReturn404WhenTeamCalendarDoesNotBelongToDomain() {
        // Given a team calendar owned by a different domain than the request.
        TeamCalendar teamCalendar = createTeamCalendar();
        Domain otherDomain = Domain.of("other.org");
        createDomain(otherDomain);
        OpenPaaSUser member = sabreDavExtension.newTestUser();

        // When updating invitees, then the route hides the team calendar.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", otherDomain.asString(), teamCalendar.id().value())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Team calendar does not exist: " + teamCalendar.id().value()));
    }

    @Test
    void inviteeShouldReturn400WhenMemberDoesNotBelongToDomain() {
        // Given an update payload for a member from another domain.
        TeamCalendar teamCalendar = createTeamCalendar();
        Domain otherDomain = Domain.of("other.org");
        OpenPaaSUser otherDomainUser = createUser(otherDomain);

        // When updating invitees, then the WebAdmin route rejects the member.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:${member}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${member}", otherDomainUser.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Member must belong to domain: " + DOMAIN.asString()));
    }

    @Test
    void inviteeShouldReturn400WhenMemberUsernameHasNoDomainPart() {
        // Given an update payload for a member username without domain part.
        TeamCalendar teamCalendar = createTeamCalendar();

        // When updating invitees, then the WebAdmin route rejects the member username.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:member-without-domain", "dav:read": true}
                    ]
                  }
                }
                """)
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Member username must contain a domain"));
    }

    @Test
    void inviteeShouldReturn400WhenMemberDoesNotExist() {
        // Given an update payload for a same-domain member missing from OpenPaaS.
        TeamCalendar teamCalendar = createTeamCalendar();

        // When updating invitees, then the WebAdmin route reports the missing member.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {"dav:href": "mailto:missing@${domain}", "dav:read": true}
                    ]
                  }
                }
                """
                .replace("${domain}", DOMAIN.asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Candidate member not found: missing@" + DOMAIN.asString()));
    }

    @Test
    void inviteeShouldReturn400WhenMemberHasConflictingDavRights() {
        // Given an update payload enabling more than one DAV right for one member.
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser member = sabreDavExtension.newTestUser();

        // When updating invitees, then the WebAdmin route rejects the ambiguous role.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "share": {
                    "set": [
                      {
                        "dav:href": "mailto:${member}",
                        "dav:read": true,
                        "dav:administration": true
                      }
                    ]
                  }
                }
                """
                .replace("${member}", member.username().asString()))
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Exactly one of 'dav:read', 'dav:read-write', 'dav:administration' must be true"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        not-json | Invalid request body:
        {} | Invalid request body:
        {"share":{"set":[{"dav:href":"http://member@open-paas.org","dav:read":true}]}} | Invalid request body:
        """)
    void inviteeShouldReturn400WhenPayloadIsInvalid(String body, String expectedMessage) {
        // Given a malformed or semantically invalid invitee update payload.
        TeamCalendar teamCalendar = createTeamCalendar();

        // When updating invitees, then the WebAdmin route rejects the payload.
        given()
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendar.id().value())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", containsString(expectedMessage));
    }

    private TeamCalendar createTeamCalendar() {
        return teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, "team-" + UUID.randomUUID(), "Team calendar"))
            .block();
    }

    private OpenPaaSDomain createDomain(Domain domain) {
        return sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(domain)
            .block();
    }

    private OpenPaaSUser createUser(Domain domain) {
        createDomain(domain);
        return sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createUser(Username.fromLocalPartWithDomain("user_" + UUID.randomUUID(), domain))
            .block();
    }
}
