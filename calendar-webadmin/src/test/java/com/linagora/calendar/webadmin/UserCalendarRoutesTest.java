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
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.webadmin.WebAdminServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

public class UserCalendarRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;

    private OpenPaaSUser user;
    private OpenPaaSUser otherUser;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = sabreDavExtension.davTestHelper();

        user = sabreDavExtension.newTestUser();
        otherUser = sabreDavExtension.newTestUser();

        webAdminServer = WebAdminUtils.createWebAdminServer(new UserCalendarRoutes(userDAO, calDavClient))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void listCalendarsShouldReturnDefaultCalendar() {
        List<String> hrefs = listCalendarHrefs(user);

        assertThat(hrefs)
            .contains("/calendars/%s/%s.json".formatted(user.id().value(), user.id().value()));
    }

    @Test
    void listCalendarsShouldReturn404WhenUserDoesNotExist() {
        given()
        .when()
            .get("/users/ghost@linagora.com/calendars")
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    @Test
    void createCalendarShouldCreateCalendarInDav() {
        String calendarId = UUID.randomUUID().toString();

        given()
            .body("""
                {"id":"%s","dav:name":"My calendar","apple:color":"#F5CFD0","caldav:description":"some description"}
                """.formatted(calendarId))
        .when()
            .post("/users/{username}/calendars", user.username().asString())
        .then()
            .statusCode(201)
            .body("id", is(calendarId));

        assertThat(listCalendarHrefs(user))
            .contains("/calendars/%s/%s.json".formatted(user.id().value(), calendarId));

        JsonPath metadata = calendarMetadata(user, calendarId);
        assertThat(metadata.getString("'dav:name'")).isEqualTo("My calendar");
        assertThat(metadata.getString("'apple:color'")).isEqualTo("#F5CFD0");
        assertThat(metadata.getString("'caldav:description'")).isEqualTo("some description");
    }

    @Test
    void createCalendarShouldGenerateIdWhenAbsent() {
        String calendarId = given()
            .body("""
                {"dav:name":"Generated id calendar"}
                """)
        .when()
            .post("/users/{username}/calendars", user.username().asString())
        .then()
            .statusCode(201)
            .body("id", not(emptyString()))
            .extract()
            .jsonPath()
            .getString("id");

        assertThat(listCalendarHrefs(user))
            .contains("/calendars/%s/%s.json".formatted(user.id().value(), calendarId));
    }

    @Test
    void createCalendarShouldFailWhenNameIsMissing() {
        given()
            .body("""
                {"apple:color":"#F5CFD0"}
                """)
        .when()
            .post("/users/{username}/calendars", user.username().asString())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void createCalendarShouldFailOnUnknownField() {
        given()
            .body("""
                {"dav:name":"My calendar","unknown":"value"}
                """)
        .when()
            .post("/users/{username}/calendars", user.username().asString())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void createCalendarShouldReturn404WhenUserDoesNotExist() {
        given()
            .body("""
                {"dav:name":"My calendar"}
                """)
        .when()
            .post("/users/ghost@linagora.com/calendars")
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void deleteCalendarShouldDeleteCalendar() {
        String calendarId = createCalendar(user, "To be deleted");

        given()
        .when()
            .delete("/users/{username}/calendars/{calendarId}", user.username().asString(), calendarId)
        .then()
            .statusCode(204);

        assertThat(listCalendarHrefs(user))
            .doesNotContain("/calendars/%s/%s.json".formatted(user.id().value(), calendarId));
    }

    @Test
    void deleteCalendarShouldReturn404WhenCalendarDoesNotExist() {
        given()
        .when()
            .delete("/users/{username}/calendars/{calendarId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Calendar does not exist"));
    }

    @Test
    void deleteCalendarShouldDeleteDelegatedCalendarCopy() {
        String calendarId = createCalendar(user, "Delegated calendar");
        davTestHelper.grantDelegation(user, new CalendarURL(user.id(), new OpenPaaSId(calendarId)), otherUser, "dav:read");

        List<String> delegatedHrefs = listCalendarHrefs(otherUser);
        String delegatedCopyId = extractCalendarId(findOtherCalendarHref(delegatedHrefs, otherUser));

        given()
        .when()
            .delete("/users/{username}/calendars/{calendarId}", otherUser.username().asString(), delegatedCopyId)
        .then()
            .statusCode(204);

        assertThat(listCalendarHrefs(otherUser))
            .containsExactly("/calendars/%s/%s.json".formatted(otherUser.id().value(), otherUser.id().value()));
    }

    @Test
    void deleteCalendarShouldDeletePublicSubscription() {
        String sourceCalendarId = createCalendar(user, "Public calendar");
        davTestHelper.updateCalendarAcl(user,
            new CalendarURL(user.id(), new OpenPaaSId(sourceCalendarId)).asUri(),
            "{DAV:}read");

        String subscriptionId = UUID.randomUUID().toString();
        davTestHelper.subscribeToSharedCalendar(otherUser, SubscribedCalendarRequest.builder()
            .id(subscriptionId)
            .sourceUserId(user.id().value())
            .sourceCalendarId(sourceCalendarId)
            .name("My subscription")
            .color("#00FF00")
            .readOnly(true)
            .build());

        assertThat(listCalendarHrefs(otherUser))
            .contains("/calendars/%s/%s.json".formatted(otherUser.id().value(), subscriptionId));

        given()
        .when()
            .delete("/users/{username}/calendars/{calendarId}", otherUser.username().asString(), subscriptionId)
        .then()
            .statusCode(204);

        assertThat(listCalendarHrefs(otherUser))
            .doesNotContain("/calendars/%s/%s.json".formatted(otherUser.id().value(), subscriptionId));
    }

    @Test
    void publicRightShouldGrantPublicReadRight() {
        String calendarId = createCalendar(user, "Soon to be public");

        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/publicRight", user.username().asString(), calendarId)
        .then()
            .statusCode(204);

        assertThat(authenticatedPrincipalPrivileges(user, calendarId))
            .contains("{DAV:}read");
    }

    @Test
    void publicRightShouldRemovePublicRights() {
        String calendarId = createCalendar(user, "Public then private");
        davTestHelper.updateCalendarAcl(user,
            new CalendarURL(user.id(), new OpenPaaSId(calendarId)).asUri(),
            "{DAV:}read");
        assertThat(authenticatedPrincipalPrivileges(user, calendarId))
            .contains("{DAV:}read");

        given()
            .body("""
                {"public_right":""}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/publicRight", user.username().asString(), calendarId)
        .then()
            .statusCode(204);

        assertThat(authenticatedPrincipalPrivileges(user, calendarId))
            .doesNotContain("{DAV:}read");
    }

    @Test
    void publicRightShouldRejectUnsupportedValue() {
        String calendarId = createCalendar(user, "Calendar");

        given()
            .body("""
                {"public_right":"{DAV:}all"}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/publicRight", user.username().asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void publicRightShouldReturn404WhenCalendarDoesNotExist() {
        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/publicRight", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void inviteeShouldGrantDelegation() {
        String calendarId = createCalendar(user, "Shared calendar");

        given()
            .body("""
                {"share":{"set":[{"dav:href":"mailto:%s","dav:read-write":true}],"remove":[]}}
                """.formatted(otherUser.username().asString()))
        .when()
            .post("/users/{username}/calendars/{calendarId}/invitee", user.username().asString(), calendarId)
        .then()
            .statusCode(204);

        assertThat(delegatedCalendarHrefs(otherUser))
            .hasSize(1);
    }

    @Test
    void inviteeShouldRevokeDelegation() {
        String calendarId = createCalendar(user, "Shared calendar");
        davTestHelper.grantDelegation(user, new CalendarURL(user.id(), new OpenPaaSId(calendarId)), otherUser, "dav:read-write");
        assertThat(delegatedCalendarHrefs(otherUser)).hasSize(1);

        given()
            .body("""
                {"share":{"set":[],"remove":[{"dav:href":"mailto:%s"}]}}
                """.formatted(otherUser.username().asString()))
        .when()
            .post("/users/{username}/calendars/{calendarId}/invitee", user.username().asString(), calendarId)
        .then()
            .statusCode(204);

        assertThat(delegatedCalendarHrefs(otherUser)).isEmpty();
    }

    @Test
    void inviteeShouldFailWhenShareFieldIsMissing() {
        String calendarId = createCalendar(user, "Calendar");

        given()
            .body("""
                {"set":[]}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/invitee", user.username().asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void inviteeShouldFailWhenHrefIsNotAMailtoUri() {
        String calendarId = createCalendar(user, "Calendar");

        given()
            .body("""
                {"share":{"set":[{"dav:href":"http://example.com","dav:read":true}],"remove":[]}}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/invitee", user.username().asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void inviteeShouldFailWhenNoRightIsProvided() {
        String calendarId = createCalendar(user, "Calendar");

        given()
            .body("""
                {"share":{"set":[{"dav:href":"mailto:%s"}],"remove":[]}}
                """.formatted(otherUser.username().asString()))
        .when()
            .post("/users/{username}/calendars/{calendarId}/invitee", user.username().asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void inviteeShouldReturn404WhenCalendarDoesNotExist() {
        given()
            .body("""
                {"share":{"set":[],"remove":[]}}
                """)
        .when()
            .post("/users/{username}/calendars/{calendarId}/invitee", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void patchShouldUpdateCalendarDetails() {
        String calendarId = createCalendar(user, "Original name");

        given()
            .body("""
                {"dav:name":"B test 2","caldav:description":"sample desc","apple:color":"#F5CFD0"}
                """)
        .when()
            .patch("/users/{username}/calendars/{calendarId}", user.username().asString(), calendarId)
        .then()
            .statusCode(204);

        JsonPath metadata = calendarMetadata(user, calendarId);
        assertThat(metadata.getString("'dav:name'")).isEqualTo("B test 2");
        assertThat(metadata.getString("'caldav:description'")).isEqualTo("sample desc");
        assertThat(metadata.getString("'apple:color'")).isEqualTo("#F5CFD0");
    }

    @Test
    void patchShouldRejectUnknownField() {
        String calendarId = createCalendar(user, "Calendar");

        given()
            .body("""
                {"dav:name":"New name","id":"should-not-be-here"}
                """)
        .when()
            .patch("/users/{username}/calendars/{calendarId}", user.username().asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void patchShouldFailWhenNoFieldIsProvided() {
        String calendarId = createCalendar(user, "Calendar");

        given()
            .body("{}")
        .when()
            .patch("/users/{username}/calendars/{calendarId}", user.username().asString(), calendarId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void patchShouldReturn404WhenCalendarDoesNotExist() {
        given()
            .body("""
                {"dav:name":"New name"}
                """)
        .when()
            .patch("/users/{username}/calendars/{calendarId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    private String createCalendar(OpenPaaSUser owner, String name) {
        return given()
            .body("""
                {"dav:name":"%s","apple:color":"#0000FF","caldav:description":""}
                """.formatted(name))
        .when()
            .post("/users/{username}/calendars", owner.username().asString())
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    }

    private List<String> listCalendarHrefs(OpenPaaSUser targetUser) {
        String body = given()
        .when()
            .get("/users/{username}/calendars", targetUser.username().asString())
        .then()
            .statusCode(200)
            .extract()
            .asString();

        List<String> hrefs = JsonPath.from(body).getList("_embedded.'dav:calendar'._links.self.href");
        if (hrefs == null) {
            return List.of();
        }
        return hrefs;
    }

    private List<String> delegatedCalendarHrefs(OpenPaaSUser targetUser) {
        String defaultHref = "/calendars/%s/%s.json".formatted(targetUser.id().value(), targetUser.id().value());
        return listCalendarHrefs(targetUser).stream()
            .filter(href -> !href.equals(defaultHref))
            .toList();
    }

    private String findOtherCalendarHref(List<String> hrefs, OpenPaaSUser targetUser) {
        String defaultHref = "/calendars/%s/%s.json".formatted(targetUser.id().value(), targetUser.id().value());
        return hrefs.stream()
            .filter(href -> !href.equals(defaultHref))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No delegated calendar found in " + hrefs));
    }

    private String extractCalendarId(String href) {
        String withoutExtension = href.substring(0, href.length() - ".json".length());
        return withoutExtension.substring(withoutExtension.lastIndexOf('/') + 1);
    }

    private JsonPath calendarMetadata(OpenPaaSUser owner, String calendarId) {
        return JsonPath.from(davTestHelper.getCalendarMetadata(owner, new OpenPaaSId(calendarId)).block());
    }

    private List<String> authenticatedPrincipalPrivileges(OpenPaaSUser owner, String calendarId) {
        return calendarMetadata(owner, calendarId)
            .getList("acl.findAll { it.principal == '{DAV:}authenticated' }.privilege");
    }
}
