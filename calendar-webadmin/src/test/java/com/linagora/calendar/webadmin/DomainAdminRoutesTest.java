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

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.MemoryOpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSDomainDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

class DomainAdminRoutesTest {

    private WebAdminServer webAdminServer;
    private MemoryOpenPaaSDomainDAO domainDAO;
    private MemoryOpenPaaSUserDAO userDAO;
    private MemoryOpenPaaSDomainAdminDAO domainAdminDAO;

    @BeforeEach
    void setUp() {
        domainDAO = new MemoryOpenPaaSDomainDAO();
        userDAO = new MemoryOpenPaaSUserDAO();
        domainAdminDAO = new MemoryOpenPaaSDomainAdminDAO();

        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainAdminRoutes(domainDAO, userDAO, domainAdminDAO, new JsonTransformer()))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void listAdminsShouldReturnEmptyWhenNoAdmins() {
        domainDAO.add(Domain.of("linagora.com")).block();

        String body = when()
            .get("/domains/linagora.com/admins")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .asString();

        assertThatJson(body).isEqualTo("[]");
    }

    @Test
    void putShouldAddAdmin() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("user1@linagora.com")).block();

        given()
            .put("/domains/" + domain.domain().name() + "/admins/" + user.username().asString())
        .then()
            .statusCode(204);

        String body = when()
            .get("/domains/" + domain.domain().name() + "/admins")
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(body).isEqualTo("[\"" + user.username().asString() + "\"]");
    }

    @Test
    void deleteShouldRevokeAdmin() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("user1@linagora.com")).block();

        // add first
        given().put("/domains/" + domain.domain().name() + "/admins/" + user.username().asString()).then().statusCode(204);

        // revoke
        given().delete("/domains/" + domain.domain().name() + "/admins/" + user.username().asString())
            .then()
            .statusCode(204);

        String body = when()
            .get("/domains/" + domain.domain().name() + "/admins")
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(body).isEqualTo("[]");
    }

    @Test
    void getShouldReturn404WhenDomainNotExist() {
        when()
            .get("/domains/notfound.com/admins")
        .then()
            .statusCode(404);
    }

    @Test
    void putShouldReturn404WhenUserNotExist() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .put("/domains/linagora.com/admins/notfound@linagora.com")
        .then()
            .statusCode(404);
    }

    @Test
    void putShouldReturn400WhenInvalidUser() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .put("/domains/linagora.com/admins/@@linagora.com")
        .then()
            .statusCode(400);
    }

    @Test
    void deleteShouldReturn404WhenUserNotExist() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .delete("/domains/linagora.com/admins/notfound@linagora.com")
        .then()
            .statusCode(404);
    }

    @Test
    void listAdminsShouldBeSortedAlphabetically() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser userA = userDAO.add(Username.of("aaa@linagora.com")).block();
        OpenPaaSUser userZ = userDAO.add(Username.of("zzz@linagora.com")).block();
        OpenPaaSUser userM = userDAO.add(Username.of("mmm@linagora.com")).block();

        // add in random order
        given().put("/domains/" + domain.domain().name() + "/admins/" + userM.username().asString()).then().statusCode(204);
        given().put("/domains/" + domain.domain().name() + "/admins/" + userZ.username().asString()).then().statusCode(204);
        given().put("/domains/" + domain.domain().name() + "/admins/" + userA.username().asString()).then().statusCode(204);

        String body = when()
            .get("/domains/" + domain.domain().name() + "/admins")
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(body).isEqualTo("[\""
            + userA.username().asString() + "\", \""
            + userM.username().asString() + "\", \""
            + userZ.username().asString() + "\"]");
    }

    @Test
    void getShouldReturn400WhenDomainInvalidFormat() {
        when()
            .get("/domains/linagor@.com/admins")
        .then()
            .statusCode(400);
    }

    @Test
    void putShouldBeIdempotent() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("user1@linagora.com")).block();

        given().put("/domains/" + domain.domain().name() + "/admins/" + user.username().asString()).then().statusCode(204);
        given().put("/domains/" + domain.domain().name() + "/admins/" + user.username().asString()).then().statusCode(204);

        String body = when()
            .get("/domains/" + domain.domain().name() + "/admins")
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(body).isEqualTo("[\"" + user.username().asString() + "\"]");
    }

    @Test
    void deleteShouldBeIdempotentWhenUserNotAdmin() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();
        OpenPaaSUser user = userDAO.add(Username.of("user1@linagora.com")).block();

        given()
            .delete("/domains/" + domain.domain().name() + "/admins/" + user.username().asString())
        .then()
            .statusCode(204);

        String body = when()
            .get("/domains/" + domain.domain().name() + "/admins")
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThatJson(body).isEqualTo("[]");
    }

    @Test
    void deleteShouldReturn400WhenInvalidUser() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .delete("/domains/linagora.com/admins/@@linagora.com")
        .then()
            .statusCode(400);
    }
}
