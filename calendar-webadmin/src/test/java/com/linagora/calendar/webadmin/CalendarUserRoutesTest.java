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

import java.util.UUID;

import org.apache.james.core.Username;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

class CalendarUserRoutesTest {

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;

    @BeforeEach
    void setUp() {
        userDAO = new MemoryOpenPaaSUserDAO();
        webAdminServer = WebAdminUtils.createWebAdminServer(new CalendarUserRoutes(userDAO, new JsonTransformer())).start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .setBasePath(CalendarUserRoutes.BASE_PATH)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void getUsersShouldReturnUsers() {
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        when()
            .get()
        .then()
            .contentType(ContentType.JSON)
            .statusCode(200)
            .body("[0].email", equalTo("james@linagora.com"))
            .body("[0].firstname", equalTo("James"))
            .body("[0].lastname", equalTo("Bond"))
            .body("[0].id", equalTo(user.id().value()));
    }

    @Test
    void getUsersShouldReturnEmptyListWhenNoUsersRegistered() {
        when()
            .get()
        .then()
            .statusCode(200)
            .body("size()", equalTo(0));
    }

    @Test
    void postUserShouldAddUser() {
        given()
            .body("{\"email\":\"test@linagora.com\",\"firstname\":\"Test\",\"lastname\":\"User\"}")
        .when()
            .post()
        .then()
            .statusCode(201);
    }

    @Test
    void postUserShouldReturn400WhenEmailMissing() {
        given()
            .body("{\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .post()
        .then()
            .statusCode(400)
            .contentType(ContentType.JSON)
            .body("statusCode", is(HttpStatus.BAD_REQUEST_400))
            .body("type", is("InvalidArgument"))
            .body("message", is("Missing email"));
    }

    @Test
    void postUserShouldReturn400WhenFirstnameMissing() {
        given()
            .body("{\"email\":\"james@linagora.com\",\"lastname\":\"Bond\"}")
        .when()
            .post()
        .then()
            .statusCode(400)
            .contentType(ContentType.JSON)
            .body("statusCode", is(HttpStatus.BAD_REQUEST_400))
            .body("type", is("InvalidArgument"))
            .body("message", is("Missing firstname"));
    }

    @Test
    void postUserShouldReturn400WhenLastnameMissing() {
        given()
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"James\"}")
        .when()
            .post()
        .then()
            .statusCode(400)
            .contentType(ContentType.JSON)
            .body("statusCode", is(HttpStatus.BAD_REQUEST_400))
            .body("type", is("InvalidArgument"))
            .body("message", is("Missing lastname"));
    }

    @Test
    void postUserShouldReturn409WhenDuplicateEmails() {
        userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .body("{\"email\":\"james@linagora.com\",\"firstname\":\"Test\",\"lastname\":\"User\"}")
        .when()
            .post()
        .then()
            .statusCode(409)
            .contentType(ContentType.JSON)
            .body("statusCode", is(HttpStatus.CONFLICT_409))
            .body("type", is("InvalidArgument"))
            .body("message", is("james@linagora.com already exists"));
    }

    @Test
    void headUserByEmailShouldReturn200WhenUserExists() {
        userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("email", "james@linagora.com")
        .when()
            .head()
        .then()
            .statusCode(200);
    }

    @Test
    void headUserByIdShouldReturn200WhenUserExists() {
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
        .when()
            .head()
        .then()
            .statusCode(200);
    }

    @Test
    void headUserByEmailShouldReturn404WhenUserDoesNotExist() {
        given()
            .queryParam("email", "unknown@linagora.com")
        .when()
            .head()
        .then()
            .statusCode(404);
    }

    @Test
    void headUserShouldReturn400WhenNoParamProvided() {
        when()
            .head()
        .then()
            .statusCode(400);
    }

    @Test
    void headUserShouldReturn400WhenBothEmailAndIdProvided() {
        given()
            .queryParam("email", "james@linagora.com")
            .queryParam("id", "some-id")
        .when()
            .head()
        .then()
            .statusCode(400);
    }

    @Test
    void patchUserShouldUpdateUser() {
        OpenPaaSUser original = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", original.id().value())
            .body("{\"email\":\"james2@linagora.com\",\"firstname\":\"James2\",\"lastname\":\"Bond2\"}")
        .when()
            .patch()
        .then()
            .statusCode(204);

        when()
            .get()
            .then()
            .statusCode(200)
            .body("[0].email", equalTo("james2@linagora.com"))
            .body("[0].firstname", equalTo("James2"))
            .body("[0].lastname", equalTo("Bond2"))
            .body("[0].id", equalTo(original.id().value()));
    }

    @Test
    void patchUserShouldReturn400WhenIdMissing() {
        given()
            .body("{\"email\":\"jbond@linagora.com\",\"firstname\":\"James\",\"lastname\":\"Bond\"}")
        .when()
            .patch()
        .then()
            .statusCode(400)
            .body("message", equalTo("Missing 'id' query parameter"));
    }

    @Test
    void patchUserShouldReturn400WhenRequiredFieldsMissing() {
        OpenPaaSUser user = userDAO.add(Username.of("james@linagora.com"), "James", "Bond").block();

        given()
            .queryParam("id", user.id().value())
            .body("{\"firstname\":\"James2\",\"lastname\":\"Bond2\"}")
        .when()
            .patch()
        .then()
            .statusCode(400)
            .body("message", equalTo("Missing one or more required fields: email, firstname, lastname"));
    }

    @Test
    void patchUserShouldReturn409WhenUpdatingToExistingEmail() {
        userDAO.add(Username.of("user1@linagora.com"), "User", "One").block();
        OpenPaaSUser user2 = userDAO.add(Username.of("user2@linagora.com"), "User", "Two").block();

        given()
            .queryParam("id", user2.id().value())
            .body("{\"email\":\"user1@linagora.com\",\"firstname\":\"User\",\"lastname\":\"Two\"}")
        .when()
            .patch()
        .then()
            .statusCode(409)
            .body("message", equalTo("user1@linagora.com already exists"));
    }

    @Test
    void patchUserShouldReturn409WhenUserNotFound() {
        String nonExistingId = UUID.randomUUID().toString();

        given()
            .queryParam("id", nonExistingId)
            .body("{\"email\":\"new@linagora.com\",\"firstname\":\"Updated\",\"lastname\":\"User\"}")
        .when()
            .patch()
        .then()
            .statusCode(404)
            .body("message", equalTo("User with id " + nonExistingId + " not found"));
    }
}
