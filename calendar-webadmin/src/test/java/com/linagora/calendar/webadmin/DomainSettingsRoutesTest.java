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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.DomainSettingsDAO;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;
import com.linagora.calendar.storage.mongodb.MongoDBDomainSettingsDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

class DomainSettingsRoutesTest {

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(MongoDBOpenPaaSDomainDAO.COLLECTION, MongoDBDomainSettingsDAO.COLLECTION));

    private WebAdminServer webAdminServer;
    private OpenPaaSDomainDAO domainDAO;
    private DomainSettingsDAO domainSettingsDAO;

    @BeforeEach
    void setUp() {
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        domainSettingsDAO = new MongoDBDomainSettingsDAO(mongo.getDb());
        DomainSettingsResolver resolver = new DomainSettingsResolver(domainSettingsDAO, Set.of(), Set.of(), new MapConfiguration(Map.of()));

        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainSettingsRoutes(domainSettingsDAO, resolver, domainDAO, new JsonTransformer()))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void getShouldReturnNullsWithSystemDefaultsResolvedWhenNoSettingsSaved() {
        domainDAO.add(Domain.of("linagora.com")).block();

        String body = when()
            .get("/domains/linagora.com/settings")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract().asString();

        assertThatJson(body).isEqualTo("""
            {
              "userSearchMode": null,
              "resourceSearchEnabled": null,
              "defaultCalendarPublicVisibility": null,
              "resolved": {
                "userSearchMode": "enabled",
                "resourceSearchEnabled": true,
                "defaultCalendarPublicVisibility": "private"
              }
            }""");
    }

    @Test
    void getShouldReturnStoredSettingsWithResolvedFallbacksForUnsetFields() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": null
                }
                """)
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(204);

        String body = when()
            .get("/domains/linagora.com/settings")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract().asString();

        assertThatJson(body).isEqualTo("""
            {
              "userSearchMode": "limited",
              "resourceSearchEnabled": false,
              "defaultCalendarPublicVisibility": null,
              "resolved": {
                "userSearchMode": "limited",
                "resourceSearchEnabled": false,
                "defaultCalendarPublicVisibility": "private"
              }
            }""");
    }

    @Test
    void putShouldUpdateSettings() {
        domainDAO.add(Domain.of("linagora.com")).block();

        String body1 = """
            {
              "userSearchMode": "limited",
              "resourceSearchEnabled": false,
              "defaultCalendarPublicVisibility": "private"
            }
            """;
        String body2 = """
            {
              "userSearchMode": "disabled",
              "resourceSearchEnabled": true,
              "defaultCalendarPublicVisibility": null
            }
            """;

        given().contentType(ContentType.JSON).body(body1).put("/domains/linagora.com/settings").then().statusCode(204);
        given().contentType(ContentType.JSON).body(body2).put("/domains/linagora.com/settings").then().statusCode(204);

        String response = when()
            .get("/domains/linagora.com/settings")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThatJson(response).isEqualTo("""
            {
              "userSearchMode": "disabled",
              "resourceSearchEnabled": true,
              "defaultCalendarPublicVisibility": null,
              "resolved": {
                "userSearchMode": "disabled",
                "resourceSearchEnabled": true,
                "defaultCalendarPublicVisibility": "private"
              }
            }""");
    }

    @Test
    void putShouldBeIndependentPerDomain() {
        domainDAO.add(Domain.of("linagora.com")).block();
        domainDAO.add(Domain.of("other.com")).block();

        String body1 = """
            {
              "userSearchMode": "limited",
              "resourceSearchEnabled": false,
              "defaultCalendarPublicVisibility": "read"
            }
            """;
        given()
            .contentType(ContentType.JSON)
            .body(body1)
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(204);

        String body2 = """
            {
              "userSearchMode": "disabled",
              "resourceSearchEnabled": true,
              "defaultCalendarPublicVisibility": null
            }
            """;
        given()
            .contentType(ContentType.JSON)
            .body(body2)
            .put("/domains/other.com/settings")
        .then()
            .statusCode(204);

        assertThatJson(when().get("/domains/linagora.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": "read",
                  "resolved": {
                    "userSearchMode": "limited",
                    "resourceSearchEnabled": false,
                    "defaultCalendarPublicVisibility": "read"
                  }
                }""");

        assertThatJson(when().get("/domains/other.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": true,
                  "defaultCalendarPublicVisibility": null,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "private"
                  }
                }""");
    }

    @Test
    void getShouldReturn404WhenDomainNotFound() {
        when()
            .get("/domains/unknown.com/settings")
        .then()
            .statusCode(404);
    }

    @Test
    void putShouldReturn404WhenDomainNotFound() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "enabled", "resourceSearchEnabled": true, "defaultCalendarPublicVisibility": null}""")
            .put("/domains/unknown.com/settings")
        .then()
            .statusCode(404);
    }

    @Test
    void getShouldReturn400WhenInvalidDomain() {
        when()
            .get("/domains/invalid@@domain/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void putShouldReturn400WhenMissingUserSearchMode() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"resourceSearchEnabled": false, "defaultCalendarPublicVisibility": null}""")
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void putShouldReturn400WhenMissingResourceSearchEnabled() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited", "defaultCalendarPublicVisibility": null}""")
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void putShouldReturn400WhenMissingDefaultCalendarPublicVisibility() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited", "resourceSearchEnabled": false}""")
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void putShouldReturn400WhenRedundantField() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": null,
                  "unknownField": "value"
                }
                """)
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void patchShouldUpdateFields() {
        domainDAO.add(Domain.of("linagora.com")).block();
        given().contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited", "resourceSearchEnabled": false, "defaultCalendarPublicVisibility": "read"}""")
            .put("/domains/linagora.com/settings").then().statusCode(204);

        given().contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "disabled", "resourceSearchEnabled": true, "defaultCalendarPublicVisibility": null}""")
            .patch("/domains/linagora.com/settings").then().statusCode(204);

        assertThatJson(when().get("/domains/linagora.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": true,
                  "defaultCalendarPublicVisibility": null,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "private"
                  }
                }""");
    }

    @Test
    void patchShouldUpdateOnlyTheProvidedField() {
        domainDAO.add(Domain.of("linagora.com")).block();
        given().contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited", "resourceSearchEnabled": false, "defaultCalendarPublicVisibility": "read"}""")
            .put("/domains/linagora.com/settings").then().statusCode(204);

        given().contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "disabled"}""")
            .patch("/domains/linagora.com/settings").then().statusCode(204);

        assertThatJson(when().get("/domains/linagora.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": "read",
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": false,
                    "defaultCalendarPublicVisibility": "read"
                  }
                }""");
    }

    @Test
    void patchShouldClearFieldWhenNullProvided() {
        domainDAO.add(Domain.of("linagora.com")).block();
        given().contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited", "resourceSearchEnabled": false, "defaultCalendarPublicVisibility": "read"}""")
            .put("/domains/linagora.com/settings").then().statusCode(204);

        given().contentType(ContentType.JSON)
            .body("""
                {"resourceSearchEnabled": null}""")
            .patch("/domains/linagora.com/settings").then().statusCode(204);

        assertThatJson(when().get("/domains/linagora.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": null,
                  "defaultCalendarPublicVisibility": "read",
                  "resolved": {
                    "userSearchMode": "limited",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "read"
                  }
                }""");
    }

    @Test
    void patchShouldReturn404WhenDomainNotFound() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited"}""")
            .patch("/domains/unknown.com/settings")
        .then()
            .statusCode(404);
    }

    @Test
    void patchShouldReturn400WhenBodyIsEmpty() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("")
            .patch("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void patchShouldReturn400WhenBodyIsInvalidJson() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("not-valid-json")
            .patch("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void patchShouldReturn400WhenUnknownFieldIsProvided() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"unknownField": "value"}""")
            .patch("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void patchShouldReturn400WhenUserSearchModeIsInvalid() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "invalid_value"}""")
            .patch("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void patchShouldReturn400WhenDefaultCalendarPublicVisibilityIsInvalid() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"defaultCalendarPublicVisibility": "invalid_value"}""")
            .patch("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void patchShouldWorkEvenWhenNoSettingsSaved() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given().contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "disabled"}""")
            .patch("/domains/linagora.com/settings").then().statusCode(204);

        assertThatJson(when().get("/domains/linagora.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": null,
                  "defaultCalendarPublicVisibility": null,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "private"
                  }
                }""");
    }
}
