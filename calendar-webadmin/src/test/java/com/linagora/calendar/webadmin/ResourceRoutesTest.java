/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                             *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                             *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                 *
 *                                             *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                             *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                              *
 ********************************************************************/

package com.linagora.calendar.webadmin;

import static com.linagora.calendar.dav.SabreDavProvisioningService.DOMAIN;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static java.time.ZoneOffset.UTC;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.time.Clock;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class ResourceRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBResourceDAO resourceDAO;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.system(UTC));
        CalDavClient calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));

        ResourceAdministratorService resourceAdministratorService = new ResourceAdministratorService(calDavClient, userDAO);
        webAdminServer = WebAdminUtils.createWebAdminServer(
            new ResourceRoutes(resourceDAO, domainDAO, userDAO,
                new JsonTransformer(), resourceAdministratorService, taskManager, calDavClient)).start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .setBasePath(ResourceRoutes.BASE_PATH)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void getResourcesShouldReturnEmptyByDefault() {
        String string = when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(string).isEqualTo("[]");
    }

    @Test
    void getResourcesFilteredByDomainShouldReturnEmptyByDefault() {
        domainDAO.add(Domain.of("linagora.com")).block();

        String string = given()
            .queryParam("domain", "linagora.com")
        .when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(string).isEqualTo("[]");
    }

    @Test
    void getResourcesFilteredByDomainShouldReturnInvalidRequestWhenDomainDoNotExist() {
        String notExistDomain = UUID.randomUUID() + ".com";
        given()
            .queryParam("domain", notExistDomain)
        .when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(400);
    }

    @Test
    void getResourcesFilteredByDomainShouldReturnInvalidRequestWhenBadDomain() {
        given()
            .queryParam("domain", "linagor@.com")
        .when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(400);
    }

    @Test
    void getResourcesFilteredByDomainShouldReturnInvalidRequestWhenEmptyDomain() {
        given()
            .queryParam("domain", "")
        .when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(400);
    }

    @Test
    void getResourcesShouldReturnCreatedResources() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        OpenPaaSDomain domain2 = domainDAO.add(Domain.of("twake.app")).block();
        OpenPaaSUser user3 = userDAO.add(Username.of("user3@twake.app")).block();
        OpenPaaSUser user4 = userDAO.add(Username.of("user4@twake.app")).block();
        ResourceAdministrator admin3 = new ResourceAdministrator(user3.id(), "user");
        ResourceAdministrator admin4 = new ResourceAdministrator(user4.id(), "user");
        ResourceId resourceId2 = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin3, admin4),
            user3.id(), "Descripting", domain2.id(), "laptop", "Resource name")).block();

        String string = when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(string)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                           [
                           {
                               "name": "Resource name",
                               "deleted": false,
                               "description": "Descripting",
                               "id": "RESOURCE_ID_1",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "creator":"user1@linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           },
                           {
                               "name": "Resource name",
                               "deleted": false,
                               "description": "Descripting",
                               "id": "RESOURCE_ID_2",
                               "creator":"user3@twake.app",
                               "icon": "laptop",
                               "domain": "twake.app",
                               "administrators": [
                                   {
                                       "email": "user3@twake.app"
                                   },
                                   {
                                       "email": "user4@twake.app"
                                   }
                               ]
                           }
                                            ]"""
            .replace("RESOURCE_ID_1", resourceId.value())
            .replace("RESOURCE_ID_2", resourceId2.value()));
    }

    @Test
    void getResourcesFilteredByDomainShouldReturnCreatedResourceOfThisDomain() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        OpenPaaSDomain domain2 = domainDAO.add(Domain.of("twake.app")).block();
        OpenPaaSUser user3 = userDAO.add(Username.of("user3@twake.app")).block();
        OpenPaaSUser user4 = userDAO.add(Username.of("user4@twake.app")).block();
        ResourceAdministrator admin3 = new ResourceAdministrator(user3.id(), "user");
        ResourceAdministrator admin4 = new ResourceAdministrator(user4.id(), "user");
        ResourceId resourceId2 = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin3, admin4),
            user3.id(), "Descripting", domain2.id(), "laptop", "Resource name")).block();

        String string = given()
            .queryParam("domain", "linagora.com")
        .when()
            .get()
         .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();


        assertThatJson(string)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                           [
                           {
                               "name": "Resource name",
                               "deleted": false,
                               "description": "Descripting",
                               "creator":"user1@linagora.com",
                               "id": "RESOURCE_ID_1",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                                            ]"""
            .replace("RESOURCE_ID_1", resourceId.value())
            .replace("RESOURCE_ID_2", resourceId2.value()));
    }

    @Test
    void getResourceShouldReturnCorrespondingValue() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        String string = when()
            .get(resourceId.value())
         .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();


        assertThatJson(string)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                           {
                               "name": "Resource name",
                               "deleted": false,
                               "description": "Descripting",
                               "creator":"user1@linagora.com",
                               "id": "RESOURCE_ID_1",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                           """
            .replace("RESOURCE_ID_1", resourceId.value()));
    }

    @Test
    void patchShouldUpdateTheResource() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        given()
            .body("""
                {
                               "name": "Resource name 2",
                               "description": "Descripting 2",
                               "icon": "battery",
                               "administrators": [
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                """)
            .patch(resourceId.value())
            .then()
            .statusCode(204);

        String string = given()
            .queryParam("domain", "linagora.com")
        .when()
            .get(resourceId.value())
        .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(string)
            .isEqualTo("""
                           {
                               "name": "Resource name 2",
                               "deleted": false,
                               "description": "Descripting 2",
                               "creator":"user1@linagora.com",
                               "id": "RESOURCE_ID_1",
                               "icon": "battery",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                           """
            .replace("RESOURCE_ID_1", resourceId.value()));
    }

    @Test
    void patchShouldSupportPartialUpdates() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        given()
            .body("""
                {"name": "Resource name 2"}
                """)
            .patch(resourceId.value())
            .then()
            .statusCode(204);

        String string = given()
            .queryParam("domain", "linagora.com")
        .when()
            .get(resourceId.value())
        .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(string)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                           {
                               "name": "Resource name 2",
                               "deleted": false,
                               "description": "Descripting",
                               "creator":"user1@linagora.com",
                               "id": "RESOURCE_ID_1",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                           """
            .replace("RESOURCE_ID_1", resourceId.value()));
    }

    @Test
    void patchShouldReturn400WhenAdminNotFound() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        given()
            .body("""
                "administrators": [
                  {"email": "notfound@linagora.com"},
                  {"email": "user2@linagora.com"}
                ]
                """)
            .patch(resourceId.value())
            .then()
            .statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "notfound",
        "6901de931710f414cf7953a9"
    })
    void patchShouldReturn404WhenResourceNotFound(String notFoundId) {
        given()
            .body("""
                {"name":"user2@linagora.com"}
                """)
            .patch(notFoundId)
            .then()
            .statusCode(404);
    }

    @Test
    void postShouldCreateTheResource() {
        OpenPaaSUser user1 = sabreDavExtension.newTestUser();
        OpenPaaSUser user2 = sabreDavExtension.newTestUser();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "creator":"{USER1}",
                               "icon": "laptop",
                               "domain": "{DOMAIN}",
                               "administrators": [
                                   {
                                       "email": "{USER1}"
                                   },
                                   {
                                       "email": "{USER2}"
                                   }
                               ]
                           }
                """.replace("{DOMAIN}", DOMAIN)
                .replace("{USER1}", user1.username().asString())
                .replace("{USER2}", user2.username().asString()))
            .post()
        .then()
            .statusCode(201);

        String string = given()
            .queryParam("domain", DOMAIN)
        .when()
            .get()
         .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();


        assertThatJson(string)
            .whenIgnoringPaths("[0].id")
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                           [{
                               "name": "Resource name",
                               "deleted": false,
                               "description": "Descripting",
                               "creator":"{USER1}",
                               "id": "RESOURCE_ID_1",
                               "icon": "laptop",
                               "domain": "{DOMAIN}",
                               "administrators": [
                                   {
                                       "email": "{USER1}"
                                   },
                                   {
                                       "email": "{USER2}"
                                   }
                               ]
                           }]
                           """
                .replace("{DOMAIN}", DOMAIN)
                .replace("{USER1}", user1.username().asString())
                .replace("{USER2}", user2.username().asString()));
    }

    @Test
    void postShouldRejectDeletedField() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "deleted": false,
                               "creator":"user1@linagora.com",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                """)
            .post()
        .then()
            .statusCode(400);
    }

    @Test
    void postShouldRejectIdField() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "id": "abc",
                               "creator":"user1@linagora.com",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                """)
            .post()
        .then()
            .statusCode(400);
    }

    @Test
    void postShouldRejectUnknownDomain() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "id": "abc",
                               "creator":"user1@linagora.com",
                               "icon": "laptop",
                               "domain": "notfound.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                """)
            .post()
        .then()
            .statusCode(400);
    }

    @Test
    void postShouldRejectUnknownCreator() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "id": "abc",
                               "creator":"notfound@linagora.com",
                               "icon": "laptop",
                               "domain": "notfound.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                """)
            .post()
        .then()
            .statusCode(400);
    }

    @Test
    void postShouldRejectUnknownAdmin() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "id": "abc",
                               "creator":"notfound@linagora.com",
                               "icon": "laptop",
                               "domain": "notfound.com",
                               "administrators": [
                                   {
                                       "email": "notfound@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                """)
            .post()
        .then()
            .statusCode(400);
    }

    @Test
    void postShouldAcceptNoAdmins() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();

        given()
            .body("""
                {
                               "name": "Resource name",
                               "description": "Descripting",
                               "id": "abc",
                               "creator":"notfound@linagora.com",
                               "icon": "laptop",
                               "domain": "notfound.com",
                               "administrators": []
                           }
                """)
            .post()
        .then()
            .statusCode(400);
    }

    @Test
    void deleteResourceShouldUpdateDeletedField() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("user1@linagora.com")).block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com")).block();
        ResourceAdministrator admin1 = new ResourceAdministrator(user1.id(), "user");
        ResourceAdministrator admin2 = new ResourceAdministrator(user2.id(), "user");
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(ImmutableList.of(admin1, admin2),
            user1.id(), "Descripting", domain.id(), "laptop", "Resource name")).block();

        when()
            .delete(resourceId.value())
         .then()
            .contentType(ContentType.JSON)
            .statusCode(204);

        String string = when()
            .get(resourceId.value())
         .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .extract()
            .body()
            .asString();


        assertThatJson(string)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                           {
                               "name": "Resource name",
                               "deleted": true,
                               "description": "Descripting",
                               "creator":"user1@linagora.com",
                               "id": "RESOURCE_ID_1",
                               "icon": "laptop",
                               "domain": "linagora.com",
                               "administrators": [
                                   {
                                       "email": "user1@linagora.com"
                                   },
                                   {
                                       "email": "user2@linagora.com"
                                   }
                               ]
                           }
                           """
            .replace("RESOURCE_ID_1", resourceId.value()));
    }

    @Test
    void getResourceShouldReturnNotFoundWhenNotExist() {
        when()
            .get("notfound")
        .then()
            .contentType(ContentType.JSON)
            .statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "notfound",
        "6901de931710f414cf7953a9"
    })
    void deleteResourceShouldReturnNotFoundWhenNotExist(String notFoundId) {
        when()
            .delete(notFoundId)
        .then()
            .statusCode(404)
            .contentType(ContentType.JSON);
    }

    @Test
    void createResourceShouldGrantDelegationRightsToSingleAdmin() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin = sabreDavExtension.newTestUser();
        // When
        String location = given()
            .body("""
                {
                    "name": "Meeting Room A",
                    "description": "Room for team meetings",
                    "creator": "%s",
                    "icon": "door",
                    "domain": "%s",
                    "administrators": [
                        { "email": "%s" }
                    ]
                }
                """.formatted(creator.username().asString(), DOMAIN, admin.username().asString()))
            .post()
            .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);

        // Then verify delegation on DAV
        OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        ArrayNode invites = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(openPaaSDomain.id(), new ResourceId(resourceId))
            .block();

        assertThat(invites)
            .as("DAV invite list should contain admin delegation entry")
            .isNotEmpty()
            .anySatisfy(invite ->
                assertThat(invite.get("href").asText()).contains(admin.username().asString()));
    }

    @Test
    void createResourceShouldGrantDelegationRightsToMultipleAdmins() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // When
        String location = given()
            .body("""
                {
                    "name": "Meeting Room B",
                    "description": "Room for board meetings",
                    "creator": "%s",
                    "icon": "meeting_room",
                    "domain": "%s",
                    "administrators": [
                        { "email": "%s" },
                        { "email": "%s" }
                    ]
                }
                """.formatted(creator.username().asString(), DOMAIN,
                admin1.username().asString(), admin2.username().asString()))
            .post()
            .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);

        // Then verify delegation on DAV
        OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        ArrayNode invites = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(openPaaSDomain.id(), new ResourceId(resourceId))
            .block();

        assertThat(invites)
            .as("DAV invite list should contain both admin delegation entries")
            .isNotEmpty();

        assertThat(invites.findValuesAsText("href"))
            .anyMatch(href -> href.contains(admin1.username().asString()))
            .anyMatch(href -> href.contains(admin2.username().asString()));
    }

    @Test
    void deleteResourceShouldRevokeDelegationRightsOnDav() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // Create the resource
        String location = given()
            .body("""
                {
                    "name": "Meeting Room C",
                    "description": "Room for presentations",
                    "creator": "%s",
                    "icon": "meeting_room",
                    "domain": "%s",
                    "administrators": [
                        { "email": "%s" },
                        { "email": "%s" }
                    ]
                }
                """.formatted(creator.username().asString(), DOMAIN,
                admin1.username().asString(), admin2.username().asString()))
            .post()
            .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);

        OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        // Sanity check: ensure rights are present before delete
        ArrayNode invitesBefore = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(openPaaSDomain.id(), new ResourceId(resourceId))
            .block();

        assertThat(invitesBefore)
            .as("Invite list before deletion should contain admin users")
            .isNotEmpty()
            .anySatisfy(invite ->
                assertThat(invite.get("href").asText())
                    .satisfiesAnyOf(href -> assertThat(href).contains(admin1.username().asString()),
                        href -> assertThat(href).contains(admin2.username().asString())));

        // When — delete the resource
        when()
            .delete(resourceId)
            .then()
            .statusCode(204);

        // Then — DAV rights should be revoked
        ArrayNode invitesAfter = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(openPaaSDomain.id(), new ResourceId(resourceId))
            .block();

        assertThat(invitesAfter)
            .as("Invite list after deletion should be empty or contain no admin delegations")
            .allSatisfy(invite ->
                assertThat(invite.get("href").asText())
                    .doesNotContain(admin1.username().asString())
                    .doesNotContain(admin2.username().asString()));
    }

    @Test
    void updateResourceShouldAddNewAdminOnDav() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // Create resource with 1 admin
        String location = given()
            .body("""
                {
                    "name": "Meeting Room D",
                    "description": "Room for updates",
                    "creator": "%s",
                    "icon": "door",
                    "domain": "%s",
                    "administrators": [{ "email": "%s" }]
                }
                """.formatted(creator.username().asString(), DOMAIN, admin1.username().asString()))
            .post()
            .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);
        OpenPaaSDomain domain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        // Sanity check
        ArrayNode before = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString())
            .doesNotContain("mailto:" + admin2.username().asString());

        // When — add admin2
        given()
            .body("""
                {
                    "administrators": [
                        { "email": "%s" },
                        { "email": "%s" }
                    ]
                }
                """.formatted(admin1.username().asString(), admin2.username().asString()))
            .patch(resourceId)
            .then()
            .statusCode(204);

        // Then — DAV invites should include both admins
        ArrayNode after = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(after.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(),
                "mailto:" + admin2.username().asString());
    }

    @Test
    void updateResourceShouldRevokeRemovedAdminsOnDav() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // Create resource with 2 admins
        String location = given()
            .body("""
                {
                    "name": "Meeting Room E",
                    "description": "Room for admin revocation",
                    "creator": "%s",
                    "icon": "door",
                    "domain": "%s",
                    "administrators": [
                        { "email": "%s" },
                        { "email": "%s" }
                    ]
                }
                """.formatted(creator.username().asString(), DOMAIN,
                admin1.username().asString(), admin2.username().asString()))
            .post()
        .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);
        OpenPaaSDomain domain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        // Sanity check — before patch
        ArrayNode before = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(),
                "mailto:" + admin2.username().asString());

        // When — remove admin2
        given()
            .body("""
                {
                    "administrators": [
                        { "email": "%s" }
                    ]
                }
                """.formatted(admin1.username().asString()))
            .patch(resourceId)
        .then()
            .statusCode(204);

        // Then — DAV should no longer contain admin2
        ArrayNode after = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(after.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString())
            .doesNotContain("mailto:" + admin2.username().asString());
    }

    @Test
    void updateResourceShouldIgnoreWhenAdministratorsFieldIsAbsent() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin = sabreDavExtension.newTestUser();

        String location = given()
            .body("""
                {
                    "name": "Meeting Room F",
                    "description": "Original description",
                    "creator": "%s",
                    "icon": "door",
                    "domain": "%s",
                    "administrators": [{ "email": "%s" }]
                }
                """.formatted(creator.username().asString(), DOMAIN, admin.username().asString()))
            .post()
        .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);
        OpenPaaSDomain domain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        // Sanity check — ensure admin exists before patch
        ArrayNode before = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin.username().asString());

        // When — update description only
        given()
            .body("""
                {
                    "description": "Updated description only"
                }
                """)
            .patch(resourceId)
            .then()
            .statusCode(204);

        // Then — DAV should remain unchanged
        ArrayNode after = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(after.findValuesAsText("href"))
            .contains("mailto:" + admin.username().asString());
    }

    @Test
    void updateResourceShouldRevokeAllAdminsWhenAdministratorsListEmpty() {
        // Given
        OpenPaaSUser creator = sabreDavExtension.newTestUser();
        OpenPaaSUser admin1 = sabreDavExtension.newTestUser();
        OpenPaaSUser admin2 = sabreDavExtension.newTestUser();

        // Create resource with 2 admins
        String location = given()
            .body("""
                {
                    "name": "Meeting Room G",
                    "description": "Room for clearing all admins",
                    "creator": "%s",
                    "icon": "door",
                    "domain": "%s",
                    "administrators": [
                        { "email": "%s" },
                        { "email": "%s" }
                    ]
                }
                """.formatted(creator.username().asString(), DOMAIN,
                admin1.username().asString(), admin2.username().asString()))
            .post()
        .then()
            .statusCode(201)
            .extract()
            .header("Location");

        String resourceId = location.substring(location.lastIndexOf('/') + 1);
        OpenPaaSDomain domain = domainDAO.retrieve(creator.username().getDomainPart().get()).block();

        // Sanity check — before patch
        ArrayNode before = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(before.findValuesAsText("href"))
            .contains("mailto:" + admin1.username().asString(),
                "mailto:" + admin2.username().asString());

        // When — update with empty admin list
        given()
            .body("""
                {
                    "administrators": []
                }
                """)
            .patch(resourceId)
        .then()
            .statusCode(204);

        // Then — DAV should have no admin delegations
        ArrayNode after = sabreDavExtension.davTestHelper()
            .getCalendarDelegateInvites(domain.id(), new ResourceId(resourceId))
            .block();

        assertThat(after.findValuesAsText("href"))
            .as("All admin delegations should be revoked when administrators=[]")
            .noneMatch(href -> href.contains("mailto:" + admin1.username().asString()))
            .noneMatch(href -> href.contains("mailto:" + admin2.username().asString()));
    }

}
