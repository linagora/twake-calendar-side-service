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

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static java.time.ZoneOffset.UTC;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.time.Clock;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

import com.linagora.calendar.storage.MemoryOpenPaaSDomainDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.MemoryResourceDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class ResourceRoutesTest {

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private MemoryOpenPaaSDomainDAO domainDAO;
    private MemoryResourceDAO resourceDAO;

    @BeforeEach
    void setUp() {
        userDAO = new MemoryOpenPaaSUserDAO();
        domainDAO = new MemoryOpenPaaSDomainDAO();
        resourceDAO = new MemoryResourceDAO(Clock.system(UTC));
        webAdminServer = WebAdminUtils.createWebAdminServer(new ResourceRoutes(resourceDAO, domainDAO, userDAO, new JsonTransformer())).start();

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
        given()
            .queryParam("domain", "linagora.com")
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
        given()
            .queryParam("domain", "linagora.com")
        .when()
            .get("notfound")
         .then()
            .contentType(ContentType.JSON)
            .statusCode(404);
    }

    @Test
    void deleteResourceShouldReturnNotFoundWhenNotExist() {
        given()
            .queryParam("domain", "linagora.com")
        .when()
            .delete("notfound")
         .then()
            .contentType(ContentType.JSON)
            .statusCode(404);
    }

}
