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

package com.linagora.calendar.app.restapi.routes;

import static com.linagora.calendar.app.AppTestHelper.OPENSEARCH_TEST_MODULE;
import static com.linagora.calendar.dav.DavModuleTestHelper.FROM_SABRE_EXTENSION;
import static com.linagora.calendar.dav.Fixture.awaitAtMost;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarNotFoundException;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.model.TeamCalendarId;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

class TeamCalendarFlowIntegrationTest {
    private static final String PASSWORD = "secret";
    private static final Domain DOMAIN = Domain.of(SabreDavProvisioningService.DOMAIN);

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @RegisterExtension
    @Order(2)
    static DockerOpenSearchExtension openSearchExtension = new DockerOpenSearchExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .calendarEventSearchChoice(TwakeCalendarConfiguration.CalendarEventSearchChoice.OPENSEARCH),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        OPENSEARCH_TEST_MODULE.apply(openSearchExtension));

    private static CalDavClient calDavClient;

    private OpenPaaSUser bob;
    private OpenPaaSUser alice;
    private OpenPaaSUser nonMember;

    @BeforeAll
    static void beforeAll() throws Exception {
        DavConfiguration davConfiguration = sabreDavExtension.dockerSabreDavSetup().davConfiguration();
        calDavClient = new CalDavClient(davConfiguration, TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        bob = provisionUser(server, "bob");
        alice = provisionUser(server, "alice");
        nonMember = provisionUser(server, "non_member");
    }

    @Test
    void createTeamCalendarShouldCreateDefaultDavCalendarWithDisplayName(TwakeCalendarGuiceServer server) {
        // Given a team calendar is created from WebAdmin
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "technical-name", "Custom Team Name");

        // When the canonical team calendar DAV collection is requested
        String responseBody = propfindDisplayName(technicalTokenDavRequestSpecification(server),
            CalendarURL.from(teamCalendarId.asOpenPaaSId()).asUri().toASCIIString());

        // Then the collection already exists and exposes the WebAdmin display name
        assertThat(extractDisplayName(responseBody))
            .isEqualTo("Custom Team Name");
    }

    @Test
    void teamCalendarMemberShouldSeeDelegatedCalendarWithTeamCalendarDisplayName(TwakeCalendarGuiceServer server) {
        // Given Bob is added as a member after the team calendar is created
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "delegated-technical-name", "Delegated Custom Team");
        grantMember(server, teamCalendarId, bob, "dav:read");
        CalendarURL delegatedCalendar = findVisibleTeamCalendar(bob, teamCalendarId);

        // When Bob requests the delegated team calendar properties
        String responseBody = propfindDisplayName(davRequestSpecification(bob), delegatedCalendar.asUri().toASCIIString());

        // Then Bob sees the display name copied from the canonical team calendar
        assertThat(extractDisplayName(responseBody))
            .isEqualTo("Delegated Custom Team");
    }

    @Test
    void updateTeamCalendarDisplayNameShouldUpdateDefaultDavCalendarDisplayName(TwakeCalendarGuiceServer server) {
        // Given a team calendar default DAV collection exists
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "rename-technical-name", "Initial Team Name");

        // When WebAdmin updates the team calendar display name
        updateTeamCalendarDisplayName(server, teamCalendarId, "Renamed Team Calendar");

        // Then the canonical team calendar DAV collection exposes the updated display name
        String responseBody = propfindDisplayName(technicalTokenDavRequestSpecification(server),
            CalendarURL.from(teamCalendarId.asOpenPaaSId()).asUri().toASCIIString());
        assertThat(extractDisplayName(responseBody))
            .isEqualTo("Renamed Team Calendar");
    }

    @Test
    void writeEnabledMemberShouldCreateEventAndDeliverInvitation(TwakeCalendarGuiceServer server) {
        // Given Bob is a read-write delegated member of a team calendar
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "engineering", "Engineering Team");
        grantMember(server, teamCalendarId, bob, "dav:read-write");
        CalendarURL teamCalendar = findVisibleTeamCalendar(bob, teamCalendarId);
        String eventUid = "team-write-" + UUID.randomUUID();

        // When Bob creates an event with Alice as attendee from his delegated calendar
        putCalendarEvent(bob, teamCalendar, eventUid, eventData(eventUid, "Team write event", bob, alice))
            .statusCode(201);

        // Then the invitation is delivered to Alice's calendar
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.calendarReportByUid(alice.username(), alice.id(), eventUid).blockOptional())
            .isPresent());
    }

    @Test
    void viewerMemberShouldNotCreateEventNorDeliverInvitation(TwakeCalendarGuiceServer server) {
        // Given Bob is a read-only delegated member of a team calendar
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "support", "Support Team");
        grantMember(server, teamCalendarId, bob, "dav:read");
        CalendarURL teamCalendar = findVisibleTeamCalendar(bob, teamCalendarId);
        String eventUid = "team-viewer-" + UUID.randomUUID();

        // When Bob tries to create an event from his delegated calendar
        putCalendarEvent(bob, teamCalendar, eventUid, eventData(eventUid, "Viewer rejected event", bob, alice))
            .statusCode(403);

        // Then no invitation is delivered to Alice
        assertThat(calDavClient.calendarReportByUid(alice.username(), alice.id(), eventUid).blockOptional())
            .isEmpty();
    }

    @Test
    void nonMemberShouldNotSeeDelegatedTeamCalendar(TwakeCalendarGuiceServer server) {
        // Given Bob is a delegated member of a team calendar
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "product", "Product Team");
        grantMember(server, teamCalendarId, bob, "dav:read");

        // When Bob and a non-member list their visible calendars
        findVisibleTeamCalendar(bob, teamCalendarId);

        // Then the team calendar is not delegated to the non-member
        assertThat(calDavClient.findUserCalendarList(nonMember).block().calendars().entrySet())
            .noneMatch(entry -> isTeamCalendar(entry, teamCalendarId));
    }

    @Test
    void deletingTeamCalendarShouldCleanupMetadataAndDavData(TwakeCalendarGuiceServer server) {
        // Given Bob is a delegated member of a team calendar
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "qa", "QA Team");
        grantMember(server, teamCalendarId, bob, "dav:read-write");
        CalendarURL teamCalendar = findVisibleTeamCalendar(bob, teamCalendarId);

        // When the team calendar is deleted from WebAdmin
        given(webAdminSpecification(server))
            .delete("/domains/{domain}/team-calendars/{teamCalendarId}", DOMAIN.asString(), teamCalendarId.value())
        .then()
            .statusCode(204);

        // Then the metadata is removed
        given(webAdminSpecification(server))
            .get("/domains/{domain}/team-calendars/{teamCalendarId}", DOMAIN.asString(), teamCalendarId.value())
        .then()
            .statusCode(404);

        // And the delegated DAV data is no longer visible nor fetchable
        awaitAtMost.untilAsserted(() -> assertThat(Objects.requireNonNull(calDavClient.findUserCalendarList(bob).block()).calendars().entrySet())
            .noneMatch(entry -> isTeamCalendar(entry, teamCalendarId)));

        assertThatThrownBy(() -> calDavClient.fetchCalendarDetails(server.getProbe(CalendarDataProbe.class).domainId(DOMAIN), teamCalendar, Map.of()).block())
            .isInstanceOf(CalendarNotFoundException.class);
    }

    @Test
    void teamCalendarEventsShouldBeIndexedAndSearchable(TwakeCalendarGuiceServer server) {
        // Given Bob is a read-write delegated member of a team calendar
        TeamCalendarId teamCalendarId = createTeamCalendar(server, "sales", "Sales Team");
        grantMember(server, teamCalendarId, bob, "dav:read-write");
        CalendarURL teamCalendar = findVisibleTeamCalendar(bob, teamCalendarId);
        CalendarURL sourceTeamCalendar = CalendarURL.from(teamCalendarId.asOpenPaaSId());
        String eventUid = "team-search-" + UUID.randomUUID();
        String summary = "Team searchable event " + UUID.randomUUID();

        // When Bob creates an event from his delegated calendar
        putCalendarEvent(bob, teamCalendar, eventUid, eventData(eventUid, summary, bob, alice))
            .statusCode(201);

        // Then Bob can search it through the delegated calendar and the result points to the source team calendar
        awaitAtMost.untilAsserted(() -> given(restApiSpecification(server, bob))
            .body(searchRequest(teamCalendar, summary))
            .post("/calendar/api/events/search")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("_total_hits", equalTo(1))
            .body("_embedded.events._links.self.href", contains(sourceTeamCalendar.asUri().toASCIIString() + "/" + eventUid + ".ics"))
            .body("_embedded.events.data.uid", contains(eventUid)));
    }

    private OpenPaaSUser provisionUser(TwakeCalendarGuiceServer server, String localPartPrefix) {
        SabreDavProvisioningService provisioningService = sabreDavExtension.dockerSabreDavSetup().getOpenPaaSProvisioningService();
        OpenPaaSUser user = provisioningService
            .createDomainIfAbsent(DOMAIN)
            .then(provisioningService.createUser(Username.fromLocalPartWithDomain(localPartPrefix + "_" + UUID.randomUUID(), DOMAIN)))
            .block();

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(DOMAIN);
        calendarDataProbe.addUserToRepository(user.username(), PASSWORD);
        provisionDefaultCalendar(user);
        return user;
    }

    private void provisionDefaultCalendar(OpenPaaSUser user) {
        given(davRequestSpecification(user))
            .contentType("application/xml")
        .when()
            .request("PROPFIND", CalendarURL.from(user.id()).asUri().toASCIIString())
        .then()
            .statusCode(207);
    }

    private String propfindDisplayName(RequestSpecification requestSpecification, String path) {
        return given(requestSpecification)
            .contentType("application/xml")
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", path)
        .then()
            .statusCode(207)
            .extract()
            .body()
            .asString();
    }

    private String extractDisplayName(String responseBody) {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            Document document = documentBuilderFactory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));

            NodeList displayNames = document.getElementsByTagNameNS("DAV:", "displayname");
            if (displayNames.getLength() == 0) {
                throw new IllegalStateException("DAV displayname is missing from PROPFIND response");
            }
            return displayNames.item(0).getTextContent();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract DAV displayname from PROPFIND response", e);
        }
    }

    private RequestSpecification technicalTokenDavRequestSpecification(TwakeCalendarGuiceServer server) {
        DavConfiguration davConfiguration = sabreDavExtension.dockerSabreDavSetup().davConfiguration();
        String technicalToken = TECHNICAL_TOKEN_SERVICE_TESTING.generate(server.getProbe(CalendarDataProbe.class).domainId(DOMAIN))
            .block()
            .value();

        return new RequestSpecBuilder()
            .setBaseUri(davConfiguration.baseUrl().toASCIIString())
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .addHeader("TwakeCalendarToken", technicalToken)
            .build();
    }

    private TeamCalendarId createTeamCalendar(TwakeCalendarGuiceServer server, String name, String displayName) {
        String payload = """
            {
              "name": "{name}",
              "displayName": "{displayName}"
            }
            """.replace("{name}", name)
            .replace("{displayName}", displayName);

        return new TeamCalendarId(given(webAdminSpecification(server))
            .body(payload)
        .when()
            .post("/domains/{domain}/team-calendars", DOMAIN.asString())
        .then()
            .statusCode(201)
            .extract()
            .path("id"));
    }

    private void updateTeamCalendarDisplayName(TwakeCalendarGuiceServer server, TeamCalendarId teamCalendarId, String displayName) {
        String payload = """
            {
              "displayName": "{displayName}"
            }
            """.replace("{displayName}", displayName);

        given(webAdminSpecification(server))
            .body(payload)
        .when()
            .patch("/domains/{domain}/team-calendars/{teamCalendarId}", DOMAIN.asString(), teamCalendarId.value())
        .then()
            .statusCode(200);
    }

    private void grantMember(TwakeCalendarGuiceServer server, TeamCalendarId teamCalendarId, OpenPaaSUser member, String davRight) {
        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:{member}",
                    "{davRight}": true
                  }
                ],
                "remove": []
              }
            }
            """.replace("{member}", member.username().asString())
            .replace("{davRight}", davRight);

        given(webAdminSpecification(server))
            .body(payload)
        .when()
            .post("/domains/{domain}/team-calendars/{teamCalendarId}/members/invitee", DOMAIN.asString(), teamCalendarId.value())
        .then()
            .statusCode(204);
    }

    private CalendarURL findVisibleTeamCalendar(OpenPaaSUser user, TeamCalendarId teamCalendarId) {
        return awaitAtMost.until(() -> calDavClient.findUserCalendarList(user)
            .map(response -> response.calendars().entrySet()
                .stream()
                .filter(entry -> isTeamCalendar(entry, teamCalendarId))
                .map(Map.Entry::getKey)
                .findFirst())
            .block(), Optional::isPresent).get();
    }

    private boolean isTeamCalendar(Map.Entry<CalendarURL, JsonNode> entry, TeamCalendarId teamCalendarId) {
        JsonNode source = entry.getValue().path("calendarserver:source");
        return isTeamCalendarURL(entry.getKey(), teamCalendarId)
            || source.path("id").asText().equals(teamCalendarId.value())
            || source.path("calendarHomeId").asText().equals(teamCalendarId.value())
            || isTeamCalendarHref(source.path("_links").path("self").path("href").asText(null), teamCalendarId)
            || isTeamCalendarHref(source.path("href").asText(null), teamCalendarId)
            || isTeamCalendarHref(entry.getValue().path("calendarserver:delegatedsource").asText(null), teamCalendarId);
    }

    private boolean isTeamCalendarURL(CalendarURL calendarURL, TeamCalendarId teamCalendarId) {
        return calendarURL.base().value().equals(teamCalendarId.value())
            || calendarURL.calendarId().value().equals(teamCalendarId.value());
    }

    private boolean isTeamCalendarHref(String href, TeamCalendarId teamCalendarId) {
        if (href == null || href.isBlank()) {
            return false;
        }
        try {
            String path = URI.create(href).getPath();
            if (path.endsWith(".json")) {
                path = path.substring(0, path.length() - ".json".length());
            }
            return isTeamCalendarURL(CalendarURL.parse(path), teamCalendarId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ValidatableResponse putCalendarEvent(OpenPaaSUser user,
                                                 CalendarURL calendarURL,
                                                 String eventUid,
                                                 String calendarData) {
        URI eventUri = URI.create(calendarURL.asUri().toASCIIString() + "/" + eventUid + ".ics");
        return given(davRequestSpecification(user))
            .contentType("text/plain")
            .body(calendarData)
        .when()
            .put(eventUri.toASCIIString())
        .then();
    }

    private String eventData(String eventUid, String summary, OpenPaaSUser organizer, OpenPaaSUser attendee) {
        return """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            CALSCALE:GREGORIAN\r
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN\r
            BEGIN:VEVENT\r
            UID:{eventUid}\r
            DTSTART:20260101T100000Z\r
            DTEND:20260101T110000Z\r
            CLASS:PUBLIC\r
            SUMMARY:{summary}\r
            DESCRIPTION:Team calendar flow test event\r
            ORGANIZER;CN=Team Member:mailto:{organizer}\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=Invitee:mailto:{attendee}\r
            DTSTAMP:20251231T100000Z\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.replace("{eventUid}", eventUid)
            .replace("{summary}", summary)
            .replace("{organizer}", organizer.username().asString())
            .replace("{attendee}", attendee.username().asString());
    }

    private String searchRequest(CalendarURL calendarURL, String query) {
        return """
            {
              "calendars": [
                { "userId": "{userId}", "calendarId": "{calendarId}" }
              ],
              "query": "{query}"
            }
            """.replace("{userId}", calendarURL.base().value())
            .replace("{calendarId}", calendarURL.calendarId().value())
            .replace("{query}", query);
    }

    private RequestSpecification webAdminSpecification(TwakeCalendarGuiceServer server) {
        return new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .build();
    }

    private RequestSpecification restApiSpecification(TwakeCalendarGuiceServer server, OpenPaaSUser user) {
        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(user.username().asString());
        auth.setPassword(PASSWORD);

        return new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setAuth(auth)
            .build();
    }

    private RequestSpecification davRequestSpecification(OpenPaaSUser user) {
        DavConfiguration davConfiguration = sabreDavExtension.dockerSabreDavSetup().davConfiguration();
        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(davConfiguration.adminCredential().getUserName() + "&" + user.username().asString());
        auth.setPassword(davConfiguration.adminCredential().getPassword());

        return new RequestSpecBuilder()
            .setBaseUri(davConfiguration.baseUrl().toASCIIString())
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setAuth(auth)
            .build();
    }
}
