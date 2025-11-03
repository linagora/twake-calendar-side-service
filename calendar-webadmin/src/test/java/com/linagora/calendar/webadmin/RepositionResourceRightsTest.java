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
import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.spy;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.linagora.calendar.webadmin.task.RepositionResourceRightsTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import reactor.core.publisher.Mono;

public class RepositionResourceRightsTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBResourceDAO resourceDAO;
    private CalDavClient calDavClient;
    private DavTestHelper davTestHelper;

    private OpenPaaSUser creator;
    private OpenPaaSId domainId;
   
    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.system(UTC));
        CalDavClient realCalDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavClient = spy(realCalDavClient);
        davTestHelper = sabreDavExtension.davTestHelper();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        ResourceAdministratorService resourceAdministratorService = new ResourceAdministratorService(calDavClient, userDAO);
        ResourceRoutes resourceRoutes = new ResourceRoutes(resourceDAO, domainDAO, userDAO, new JsonTransformer(), resourceAdministratorService, taskManager, calDavClient);

        webAdminServer = WebAdminUtils.createWebAdminServer(resourceRoutes,
                new TasksRoutes(taskManager, new JsonTransformer(), new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                    .add(RepositionResourceRightsTaskAdditionalInformationDTO.module())
                    .build())))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .setBasePath(ResourceRoutes.BASE_PATH)
            .build();

        creator = sabreDavExtension.newTestUser();
        domainId = domainDAO.retrieve(creator.username().getDomainPart().get())
            .map(OpenPaaSDomain::id).block();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
        Mockito.reset(calDavClient);
    }

    @Test
    void shouldShowAllInformationInResponse() {
        String taskId = given()
            .queryParam("task", "repositionWriteRights")
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
            .body("type", is("reposition-resource-rights"))
            .body("additionalInformation.processedResourceCount", is(0))
            .body("additionalInformation.failedResourceCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("reposition-resource-rights"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()))
            .body("completedDate", is(notNullValue()));
    }

    @Test
    void shouldReturnErrorWhenTaskNameIsInvalid() {
        given()
            .queryParam("task", "invalidTaskName")
        .when()
            .post()
        .then()
            .statusCode(400)
            .body("details", is("Unknown task: invalidTaskName"));
    }

    @Test
    void shouldRestoreWriteRightsForAllAdmins() {
        // Given
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // Create resource with 2 admins
        ResourceId resourceId = createNewResource(creator, List.of(admin1, admin2));

        // Sanity check — initial invites include both admins
        ArrayNode before = davTestHelper.getCalendarDelegateInvites(domainId, resourceId).block();

        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(), "mailto:" + admin2.username().asString());

        // WHEN — Manually revoke rights on DAV to simulate drift
        calDavClient.revokeWriteRights(domainId, resourceId, List.of(admin1.username(), admin2.username())).block();

        // Assert they are indeed removed
        ArrayNode afterRevoke = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();

        assertThat(afterRevoke.findValuesAsText("href"))
            .doesNotContain("mailto:" + admin1.username().asString(),
                "mailto:" + admin2.username().asString());

        // WHEN — Run the repositionWriteRights task
        runRepositionTaskAndAwait()
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(1))
            .body("additionalInformation.failedResourceCount", is(0));

        // THEN — rights should be restored on DAV
        ArrayNode afterTask = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();

        assertThat(afterTask.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(),
                "mailto:" + admin2.username().asString());
    }

    @Test
    void shouldRestoreWriteRightsForMultipleResources() {
        // Given
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        ResourceId resourceId1 = createNewResource(creator, List.of(admin1));
        ResourceId resourceId2 = createNewResource(creator, List.of(admin2));

        // Sanity check — both resources have their admins
        ArrayNode before1 = davTestHelper.getCalendarDelegateInvites(domainId, resourceId1).block();
        ArrayNode before2 = davTestHelper.getCalendarDelegateInvites(domainId, resourceId2).block();

        assertThat(before1.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString());
        assertThat(before2.findValuesAsText("href"))
            .contains("mailto:" + admin2.username().asString());

        // WHEN — revoke rights on DAV
        calDavClient.revokeWriteRights(domainId, resourceId1, List.of(admin1.username())).block();
        calDavClient.revokeWriteRights(domainId, resourceId2, List.of(admin2.username())).block();

        // Assert they are removed
        ArrayNode afterRevoke1 = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId1).block();
        ArrayNode afterRevoke2 = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId2).block();

        assertThat(afterRevoke1.findValuesAsText("href"))
            .doesNotContain("mailto:" + admin1.username().asString());
        assertThat(afterRevoke2.findValuesAsText("href"))
            .doesNotContain("mailto:" + admin2.username().asString());

        // WHEN — run task
        runRepositionTaskAndAwait()
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(2))
            .body("additionalInformation.failedResourceCount", is(0));

        // THEN — both resources restored
        ArrayNode afterTask1 = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId1).block();
        ArrayNode afterTask2 = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId2).block();

        assertThat(afterTask1.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString());
        assertThat(afterTask2.findValuesAsText("href"))
            .contains("mailto:" + admin2.username().asString());
    }

    @Test
    void shouldHandlePartialFailureDuringRepositionRights() {
        // Given
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // Create 2 resources
        ResourceId resourceId1 = createNewResource(creator, List.of(admin1));
        ResourceId resourceId2 = createNewResource(creator, List.of(admin2));

        Mockito.doReturn(Mono.error(() -> new DavClientException("Simulated failure for resourceId1")))
            .when(calDavClient)
            .grantReadWriteRights(Mockito.any(),
                Mockito.argThat(resourceId1::equals),
                Mockito.any());

        // When — trigger reposition task
        String taskId = given()
            .queryParam("task", "repositionWriteRights")
            .post()
            .jsonPath()
            .get("taskId");

        // Then — await completion
        given()
            .basePath(TasksRoutes.BASE)
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("failed"))
            .body("additionalInformation.processedResourceCount", is(2))
            .body("additionalInformation.failedResourceCount", is(1));

        Mockito.verify(calDavClient, Mockito.atLeast(2))
            .grantReadWriteRights(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void shouldBeIdempotentWhenRunningRepositionRightsTaskMultipleTimes() {
        // Given
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        ResourceId resourceId = createNewResource(creator, List.of(admin1, admin2));

        // Sanity check — initial rights
        ArrayNode before = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();
        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(), "mailto:" + admin2.username().asString());

        // WHEN — Run task first time
        runRepositionTaskAndAwait()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(1))
            .body("additionalInformation.failedResourceCount", is(0));

        // Run task second time — should still succeed without error
        runRepositionTaskAndAwait()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(1))
            .body("additionalInformation.failedResourceCount", is(0));

        // THEN — Rights should remain unchanged
        ArrayNode after = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();

        assertThat(after.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(), "mailto:" + admin2.username().asString());
    }

    @Test
    void shouldReapplyRightsForResourcesAcrossMultipleDomains() {
        // Given
        // Create two distinct domains
        OpenPaaSDomain domain1 = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSDomain domain2 = domainDAO.add(Domain.of("twake.app")).block();

        // Create users for each domain
        OpenPaaSUser creator1 = userDAO.add(Username.of("creator1@linagora.com")).block();
        OpenPaaSUser creator2 = userDAO.add(Username.of("creator2@twake.app" )).block();
        OpenPaaSUser admin1 = userDAO.add(Username.of("admin1@linagora.com")).block();
        OpenPaaSUser admin2 = userDAO.add(Username.of("admin2@twake.app" )).block();

        // Create resources in each domain
        ResourceId resourceId1 = createNewResource(creator1, List.of(admin1));
        ResourceId resourceId2 = createNewResource(creator2, List.of(admin2));

        // Sanity check — ensure both admins exist initially
        ArrayNode before1 = davTestHelper.getCalendarDelegateInvites(domain1.id(), resourceId1).block();
        ArrayNode before2 = davTestHelper.getCalendarDelegateInvites(domain2.id(), resourceId2).block();
        assertThat(before1.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString());
        assertThat(before2.findValuesAsText("href"))
            .contains("mailto:" + admin2.username().asString());

        // WHEN — revoke rights manually
        calDavClient.revokeWriteRights(domain1.id(), resourceId1, List.of(admin1.username())).block();
        calDavClient.revokeWriteRights(domain2.id(), resourceId2, List.of(admin2.username())).block();

        // Confirm both removed
        ArrayNode afterRevoke1 = davTestHelper.getCalendarDelegateInvites(domain1.id(), resourceId1).block();
        ArrayNode afterRevoke2 = davTestHelper.getCalendarDelegateInvites(domain2.id(), resourceId2).block();
        assertThat(afterRevoke1.findValuesAsText("href"))
            .doesNotContain("mailto:" + admin1.username().asString());
        assertThat(afterRevoke2.findValuesAsText("href"))
            .doesNotContain("mailto:" + admin2.username().asString());

        // WHEN — run reposition task
        runRepositionTaskAndAwait()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(2))
            .body("additionalInformation.failedResourceCount", is(0));

        // Both rights restored
        ArrayNode afterTask1 = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain1.id(), resourceId1).block();
        ArrayNode afterTask2 = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain2.id(), resourceId2).block();
        assertThat(afterTask1.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString());
        assertThat(afterTask2.findValuesAsText("href"))
            .contains("mailto:" + admin2.username().asString());
    }

    @Test
    void shouldCompleteSuccessfullyWhenNoResourcesPresent() {
        String taskId = given()
            .queryParam("task", "repositionWriteRights")
        .when()
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(0))
            .body("additionalInformation.failedResourceCount", is(0));
    }

    @Test
    void shouldSkipDeletedResources() {
        OpenPaaSUser admin = sabreDavExtension.newTestUser();
        ResourceId resourceId = createNewResource(creator, List.of(admin));

        // Mark resource as deleted
        resourceDAO.softDelete(resourceId).block();

        String taskId = given()
            .queryParam("task", "repositionWriteRights")
        .when()
            .post()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(0))
            .body("additionalInformation.failedResourceCount", is(0));
    }

    @Test
    void shouldNotRemoveExtraAdminsOnDavDuringReposition() {
        // Given
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser ghostAdmin = sabreDavExtension.newTestUser();
        OpenPaaSUser creator = sabreDavExtension.newTestUser();

        // Create resource with 1 admin (admin1)
        ResourceId resourceId = createNewResource(creator, List.of(admin1));

        // Sanity check — DAV initially contains only admin1
        ArrayNode before = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();

        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString());

        // Simulate an extra DAV-only admin (ghost)
        calDavClient.grantReadWriteRights(domainId, resourceId, List.of(ghostAdmin.username()))
            .block();

        // Confirm both admins are now on DAV
        ArrayNode withGhost = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();

        assertThat(withGhost.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString())
            .contains("mailto:" + ghostAdmin.username().asString());

        // WHEN — run reposition task
        runRepositionTaskAndAwait()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(1))
            .body("additionalInformation.failedResourceCount", is(0));

        // THEN — both admins (backend + extra) should still be present
        ArrayNode after = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domainId, resourceId)
            .block();

        assertThat(after.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString())
            .contains("mailto:" + ghostAdmin.username().asString());
    }

    @Test
    void shouldGrantRightsOnlyForResourcesHavingAdministrators() {
        // Given
        OpenPaaSUser admin = sabreDavExtension.newTestUser();

        // Resource A: has admin
        ResourceId resourceWithAdmin = createNewResource(creator, List.of(admin));

        // Resource B: has no admin
        ResourceId resourceWithoutAdmin = createNewResource(creator, List.of());

        Mockito.reset(calDavClient);

        // WHEN
        runRepositionTaskAndAwait()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.processedResourceCount", is(2))
            .body("additionalInformation.failedResourceCount", is(0));

        // THEN
        Mockito.verify(calDavClient, Mockito.atLeastOnce())
            .grantReadWriteRights(Mockito.any(),
                Mockito.eq(resourceWithAdmin),
                Mockito.any());

        Mockito.verify(calDavClient)
            .grantReadWriteRights(Mockito.any(),
                Mockito.eq(resourceWithoutAdmin),
                Mockito.argThat(Collection::isEmpty));
    }

    private ResourceId createNewResource(OpenPaaSUser creator, List<OpenPaaSUser> admins) {
        String adminsJson = String.join(", ",
            admins.stream()
                .map(admin -> "{ \"email\": \"" + admin.username().asString() + "\" }")
                .toList());
        String body = """
            {
                "name": "%s",
                "description": "%s",
                "creator": "%s",
                "icon": "door",
                "domain": "%s",
                "administrators": [
                    %s
                ]
            }
            """.formatted(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            creator.username().asString(),
            creator.username().getDomainPart().get().asString(),
            adminsJson.toString());

        String location = given()
            .body(body)
            .post()
        .then()
            .statusCode(201)
            .extract()
            .header("Location");
        String resourceId = location.substring(location.lastIndexOf('/') + 1);
        return new ResourceId(resourceId);
    }

    private ValidatableResponse runRepositionTaskAndAwait() {
        String taskId = given()
            .queryParam("task", "repositionWriteRights")
            .post()
            .jsonPath()
            .get("taskId");
        return given()
            .basePath(TasksRoutes.BASE)
            .get(taskId + "/await")
            .then();
    }

}
