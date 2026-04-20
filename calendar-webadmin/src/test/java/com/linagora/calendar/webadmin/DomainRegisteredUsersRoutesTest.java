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
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.MemoryOpenPaaSDomainDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

class DomainRegisteredUsersRoutesTest {

    private WebAdminServer webAdminServer;
    private MemoryOpenPaaSDomainDAO domainDAO;
    private MemoryOpenPaaSUserDAO userDAO;

    @BeforeEach
    void setUp() {
        domainDAO = new MemoryOpenPaaSDomainDAO();
        userDAO = new MemoryOpenPaaSUserDAO();

        webAdminServer = WebAdminUtils.createWebAdminServer(
            new DomainRegisteredUsersRoutes(userDAO, domainDAO, new JsonTransformer())).start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void getUsersShouldReturnEmptyWhenNone() {
        domainDAO.add(Domain.of("linagora.com")).block();

        when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("size()", equalTo(0));
    }

    @Test
    void getUsersShouldReturnOnlyUsersOfThatDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        domainDAO.add(Domain.of("twake.app")).block();
        OpenPaaSUser user1 = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();
        userDAO.add(Username.of("other@twake.app"), "Other", "User").block();

        when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].email", equalTo("james@linagora.com"))
            .body("[0].id", equalTo(user1.id().value()));
    }

    @Test
    void getUsersShouldReturn404WhenDomainNotFound() {
        when()
            .get("/domains/notfound.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void getUsersShouldReturn400WhenDomainMalformed() {
        when()
            .get("/domains/bad@domain/registeredUsers")
        .then()
            .statusCode(400);
    }

    @Test
    void getUserByEmailShouldReturnUser() {
        domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("email", "james@linagora.com")
        .when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("email", equalTo("james@linagora.com"))
            .body("id", equalTo(user.id().value()));
    }

    @Test
    void getUserByEmailShouldReturn404WhenUserBelongsToOtherDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        domainDAO.add(Domain.of("twake.app")).block();
        userDAO.add(Username.of("james@twake.app"), "James", "Bond").block();

        given()
            .queryParam("email", "james@twake.app")
        .when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void getUserByEmailShouldReturn404WhenNotFound() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .queryParam("email", "unknown@linagora.com")
        .when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void postShouldAddUser() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .post("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(201);

        when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].email", equalTo("james@linagora.com"));
    }

    @Test
    void postShouldReturn400WhenEmailDomainMismatch() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .body("{\"email\":\"james@other.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .post("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Email domain must match URL domain: linagora.com"));
    }

    @Test
    void postShouldReturn400WhenEmailMissing() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .body("{\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .post("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("JSON payload of the request is not valid"));
    }

    @Test
    void postShouldReturn404WhenDomainNotFound() {
        given()
            .body("{\"email\":\"james@notfound.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .post("/domains/notfound.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void postShouldReturn400WhenFirstnameMissing() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .body("{\"email\":\"james@linagora.com\",\"lastname\":\"Bond\"}")
        .when()
            .post("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Invalid arguments supplied in the user request"));
    }

    @Test
    void postShouldReturn400WhenLastnameMissing() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\"}")
        .when()
            .post("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Invalid arguments supplied in the user request"));
    }

    @Test
    void getUsersShouldSucceedWhenUserHasEmptyFirstname() {
        domainDAO.add(Domain.of("linagora.com")).block();
        userDAO.add(Username.of("james@linagora.com"), "", "Bond").block();

        when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("[0].firstname", equalTo(""))
            .body("[0].lastname", equalTo("Bond"));
    }

    @Test
    void getUsersShouldSucceedWhenUserHasEmptyLastname() {
        domainDAO.add(Domain.of("linagora.com")).block();
        userDAO.add(Username.of("james@linagora.com"), "James", "").block();

        when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("[0].firstname", equalTo("James"))
            .body("[0].lastname", equalTo(""));
    }

    @Test
    void patchShouldReturn400WhenFirstnameMissing() {
        domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
            .body("{\"email\":\"james@linagora.com\",\"lastname\":\"Bond\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Invalid arguments supplied in the user request"));
    }

    @Test
    void patchShouldReturn400WhenLastnameMissing() {
        domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Invalid arguments supplied in the user request"));
    }

    @Test
    void postShouldReturn409WhenUserAlreadyExists() {
        domainDAO.add(Domain.of("linagora.com")).block();
        userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .post("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(409);
    }

    @Test
    void headByEmailShouldReturn200WhenUserExists() {
        domainDAO.add(Domain.of("linagora.com")).block();
        userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("email", "james@linagora.com")
        .when()
            .head("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200);
    }

    @Test
    void headByEmailShouldReturn404WhenUserBelongsToOtherDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        domainDAO.add(Domain.of("twake.app")).block();
        userDAO.add(Username.of("james@twake.app"), "James", "Bond").block();

        given()
            .queryParam("email", "james@twake.app")
        .when()
            .head("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void headByIdShouldReturn200WhenUserExistsInDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
        .when()
            .head("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200);
    }

    @Test
    void headByIdShouldReturn404WhenUserBelongsToOtherDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        domainDAO.add(Domain.of("twake.app")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@twake.app"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
        .when()
            .head("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void patchShouldUpdateUser() {
        domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
            .body("{\"email\":\"james2@linagora.com\",\"firstname\":\"James2\",\"lastname\":\"Bond2\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(204);

        when()
            .get("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(200)
            .body("[0].email", equalTo("james2@linagora.com"))
            .body("[0].firstname", equalTo("James2"));
    }

    @Test
    void patchShouldReturn404WhenUserBelongsToOtherDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        domainDAO.add(Domain.of("twake.app")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@twake.app"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(404);
    }

    @Test
    void patchShouldReturn400WhenIdMissing() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Missing 'id' query parameter"));
    }

    @Test
    void patchShouldReturn400WhenEmailDomainMismatch() {
        domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
            .body("{\"email\":\"james@other.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(400)
            .body("message", equalTo("Email domain must match URL domain: linagora.com"));
    }

    @Test
    void patchShouldReturn409WhenUpdatingToExistingEmail() {
        domainDAO.add(Domain.of("linagora.com")).block();
        userDAO.add(Username.of("user1@linagora.com"), "User", "One").block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com"), "User", "Two").block();

        given()
            .queryParam("id", user2.id().value())
            .body("{\"email\":\"user1@linagora.com\",\"firstname\":\"User\",\"lastname\":\"Two\"}")
        .when()
            .patch("/domains/linagora.com/registeredUsers")
        .then()
            .statusCode(409);
    }
}
