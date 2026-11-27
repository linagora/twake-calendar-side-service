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
import static io.restassured.RestAssured.when;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.CoreMatchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.DomainSettingsDAO;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.UserSearchMode;
import com.linagora.calendar.storage.mongodb.MongoDBDomainSettingsDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import reactor.core.publisher.Mono;

class DomainSettingsRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private OpenPaaSDomainDAO domainDAO;
    private DomainSettingsDAO domainSettingsDAO;

    private CardDavClient client;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        domainSettingsDAO = new MongoDBDomainSettingsDAO(mongoDB);
        client = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        DomainSettingsResolver resolver = new DomainSettingsResolver(domainSettingsDAO, Set.of(), Set.of(), new MapConfiguration(Map.of()));

        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainSettingsRoutes(domainSettingsDAO, resolver, domainDAO, new JsonTransformer(), client))
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
              "calendarPublicVisibilitySettingEnabled": null,
              "resolved": {
                "userSearchMode": "enabled",
                "resourceSearchEnabled": true,
                "defaultCalendarPublicVisibility": "private",
                "calendarPublicVisibilitySettingEnabled": true
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
                  "defaultCalendarPublicVisibility": null,
                  "calendarPublicVisibilitySettingEnabled": null
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
              "calendarPublicVisibilitySettingEnabled": null,
              "resolved": {
                "userSearchMode": "limited",
                "resourceSearchEnabled": false,
                "defaultCalendarPublicVisibility": "private",
                "calendarPublicVisibilitySettingEnabled": true
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
              "defaultCalendarPublicVisibility": "private",
              "calendarPublicVisibilitySettingEnabled": true
            }
            """;
        String body2 = """
            {
              "userSearchMode": "disabled",
              "resourceSearchEnabled": true,
              "defaultCalendarPublicVisibility": null,
              "calendarPublicVisibilitySettingEnabled": false
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
              "calendarPublicVisibilitySettingEnabled": false,
              "resolved": {
                "userSearchMode": "disabled",
                "resourceSearchEnabled": true,
                "defaultCalendarPublicVisibility": "private",
                "calendarPublicVisibilitySettingEnabled": false
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
              "defaultCalendarPublicVisibility": "read",
              "calendarPublicVisibilitySettingEnabled": false
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
              "defaultCalendarPublicVisibility": null,
              "calendarPublicVisibilitySettingEnabled": null
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
                  "calendarPublicVisibilitySettingEnabled": false,
                  "resolved": {
                    "userSearchMode": "limited",
                    "resourceSearchEnabled": false,
                    "defaultCalendarPublicVisibility": "read",
                    "calendarPublicVisibilitySettingEnabled": false
                  }
                }""");

        assertThatJson(when().get("/domains/other.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": true,
                  "defaultCalendarPublicVisibility": null,
                  "calendarPublicVisibilitySettingEnabled": null,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "private",
                    "calendarPublicVisibilitySettingEnabled": true
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
                {"userSearchMode": "enabled", "resourceSearchEnabled": true, "defaultCalendarPublicVisibility": null, "calendarPublicVisibilitySettingEnabled": null}""")
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
    void putShouldReturn400WhenBodyIsJsonNull() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("null")
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(400)
            .body("type", equalTo("InvalidArgument"))
            .body("message", equalTo("JSON payload of the request is not valid"));
    }

    @Test
    void patchShouldReturn400WhenBodyIsJsonNull() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("null")
            .patch("/domains/linagora.com/settings")
        .then()
            .statusCode(400)
            .body("type", equalTo("InvalidArgument"))
            .body("message", equalTo("Request body must be a JSON object"));
    }

    @Test
    void putShouldReturn400WhenMissingUserSearchMode() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"resourceSearchEnabled": false, "defaultCalendarPublicVisibility": null, "calendarPublicVisibilitySettingEnabled": null}""")
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
                {"userSearchMode": "limited", "defaultCalendarPublicVisibility": null, "calendarPublicVisibilitySettingEnabled": null}""")
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
                {"userSearchMode": "limited", "resourceSearchEnabled": false, "calendarPublicVisibilitySettingEnabled": null}""")
            .put("/domains/linagora.com/settings")
        .then()
            .statusCode(400);
    }

    @Test
    void putShouldReturn400WhenMissingCalendarPublicVisibilitySettingEnabled() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": "limited", "resourceSearchEnabled": false, "defaultCalendarPublicVisibility": null}""")
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
                  "calendarPublicVisibilitySettingEnabled": null,
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
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": "read",
                  "calendarPublicVisibilitySettingEnabled": true
                }
                """)
            .put("/domains/linagora.com/settings").then().statusCode(204);

        given().contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": true,
                  "defaultCalendarPublicVisibility": null,
                  "calendarPublicVisibilitySettingEnabled": false
                }
                """)
            .patch("/domains/linagora.com/settings").then().statusCode(204);

        assertThatJson(when().get("/domains/linagora.com/settings").then().statusCode(200).extract().asString())
            .isEqualTo("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": true,
                  "defaultCalendarPublicVisibility": null,
                  "calendarPublicVisibilitySettingEnabled": false,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "private",
                    "calendarPublicVisibilitySettingEnabled": false
                  }
                }""");
    }

    @Test
    void patchShouldUpdateOnlyTheProvidedField() {
        domainDAO.add(Domain.of("linagora.com")).block();
        given().contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": "read",
                  "calendarPublicVisibilitySettingEnabled": true
                }
                """)
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
                  "calendarPublicVisibilitySettingEnabled": true,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": false,
                    "defaultCalendarPublicVisibility": "read",
                    "calendarPublicVisibilitySettingEnabled": true
                  }
                }""");
    }

    @Test
    void patchShouldClearFieldWhenNullProvided() {
        domainDAO.add(Domain.of("linagora.com")).block();
        given().contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "limited",
                  "resourceSearchEnabled": false,
                  "defaultCalendarPublicVisibility": "read",
                  "calendarPublicVisibilitySettingEnabled": true
                }
                """)
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
                  "calendarPublicVisibilitySettingEnabled": true,
                  "resolved": {
                    "userSearchMode": "limited",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "read",
                    "calendarPublicVisibilitySettingEnabled": true
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
    void patchShouldReturn400WhenCalendarPublicVisibilitySettingEnabledIsInvalid() {
        domainDAO.add(Domain.of("linagora.com")).block();

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"calendarPublicVisibilitySettingEnabled": "invalid_value"}""")
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
                  "calendarPublicVisibilitySettingEnabled": null,
                  "resolved": {
                    "userSearchMode": "disabled",
                    "resourceSearchEnabled": true,
                    "defaultCalendarPublicVisibility": "private",
                    "calendarPublicVisibilitySettingEnabled": true
                  }
                }""");
    }

    @ParameterizedTest
    @ValueSource(strings = {"limited", "disabled"})
    void restrictingShouldPreserveCardsAndTechnicalSynchronization(String mode) {
        // Given a domain member who can read an existing card
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        String cardUid = UUID.randomUUID().toString();
        byte[] card = createDomainMemberCard(domain, cardUid);
        assertThat(client.exportContact(member.username(), book).block()).isNotEmpty();

        // When user search is restricted
        patchUserSearchMode(domain, mode);

        // Then members lose read access, but technical-token reads and writes still work
        assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
            .hasMessageContaining("403");
        assertThat(client.retrieveContactDomainMembers(domain.id(), cardUid).block()).isEqualTo(card);
        client.upsertContactDomainMembers(domain.id(), cardUid, card).block();
        assertThat(client.retrieveContactDomainMembers(domain.id(), cardUid).block()).isEqualTo(card);
    }

    @Test
    void enablingShouldRestoreReadWithoutRepopulatingOrChangingCards() {
        // Given an existing card hidden from domain members
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        String cardUid = UUID.randomUUID().toString();
        byte[] card = createDomainMemberCard(domain, cardUid);
        patchUserSearchMode(domain, "limited");

        // When user search is enabled again
        patchUserSearchMode(domain, "enabled");

        // Then the member can read the original card without a resync
        assertThat(new String(client.exportContact(member.username(), book).block(), StandardCharsets.UTF_8))
            .contains(cardUid);
        assertThat(client.retrieveContactDomainMembers(domain.id(), cardUid).block()).isEqualTo(card);
    }

    @Test
    void enablingShouldAllowMembersWhoJoinLater() {
        // Given a domain address book whose member access was restricted
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        String cardUid = UUID.randomUUID().toString();
        createDomainMemberCard(domain, cardUid);
        patchUserSearchMode(domain, "limited");

        // When access is restored before a new member joins
        patchUserSearchMode(domain, "enabled");
        OpenPaaSUser newcomer = sabreDavExtension.newTestUser();

        // Then the ACL also grants the new member access
        assertThat(new String(client.exportContact(newcomer.username(), book).block(), StandardCharsets.UTF_8))
            .contains(cardUid);
    }

    @Test
    void removingStoredModeShouldApplyConfigurationFallback() {
        // Given an enabled domain with a limited configuration fallback
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        client.createDomainMembersAddressBook(domain.id()).block();
        restartServerWithFallback(domain.domain(), UserSearchMode.LIMITED);
        patchUserSearchMode(domain, "enabled");

        // When the stored mode is cleared
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"userSearchMode": null}
                """)
        .when()
            .patch("/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204);

        // Then the fallback revokes member read access
        assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
            .hasMessageContaining("403");
    }

    @Test
    void putShouldRestrictAccessAndStillReturnNoContent() {
        // Given an existing domain member card
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        String cardUid = UUID.randomUUID().toString();
        byte[] card = createDomainMemberCard(domain, cardUid);

        // When a PUT disables user search
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": null,
                  "defaultCalendarPublicVisibility": null,
                  "calendarPublicVisibilitySettingEnabled": null
                }
                """)
        .when()
            .put("/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));

        // Then access is revoked without deleting the card
        assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
            .hasMessageContaining("403");
        assertThat(client.retrieveContactDomainMembers(domain.id(), cardUid).block()).isEqualTo(card);
    }

    @ParameterizedTest
    @CsvSource({"PUT, enabled", "PUT, limited", "PATCH, enabled", "PATCH, limited"})
    void updatingModeShouldCreateMissingBook(String method, String mode) {
        // Given a domain member without a domain-members address book
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        assertThat(client.listDomainAddressBookUrls(domain.id(), CardDavClient.MirrorAddressBooks.EXCLUDE).collectList().block())
            .doesNotContain(book);
        String payload = """
            {
              "userSearchMode": "%s",
              "resourceSearchEnabled": null,
              "defaultCalendarPublicVisibility": null,
              "calendarPublicVisibilitySettingEnabled": null
            }
            """.formatted(mode);

        // When the mode is updated and the same request is retried
        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .request(method, "/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));
        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .request(method, "/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));

        // Then the book exists, the mode is saved and its ACL matches that mode
        assertThat(domainSettingsDAO.retrieve(domain.domain()).block().userSearchMode())
            .contains(UserSearchMode.deserialize(mode));
        assertThat(client.listDomainAddressBookUrls(domain.id(), CardDavClient.MirrorAddressBooks.EXCLUDE).collectList().block())
            .contains(book);
        if (mode.equals("enabled")) {
            client.exportContact(member.username(), book).block();
        } else {
            assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
                .hasMessageContaining("403");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH"})
    void disablingShouldNotCreateMissingBook(String method) {
        // Given a domain without a domain-members address book
        OpenPaaSDomain domain = domainDAO.add(Domain.of("no-book-" + UUID.randomUUID() + ".example")).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        String payload = """
            {
              "userSearchMode": "disabled",
              "resourceSearchEnabled": null,
              "defaultCalendarPublicVisibility": null,
              "calendarPublicVisibilitySettingEnabled": null
            }
            """;

        // When disabling user search is requested twice
        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .request(method, "/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));
        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .request(method, "/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));

        // Then the setting is saved and the book remains absent
        assertThat(domainSettingsDAO.retrieve(domain.domain()).block().userSearchMode())
            .contains(UserSearchMode.DISABLED);
        assertThat(client.listDomainAddressBookUrls(domain.id(), CardDavClient.MirrorAddressBooks.EXCLUDE).collectList().block())
            .doesNotContain(book);
    }

    @ParameterizedTest
    @CsvSource({
        "PUT, ENABLED", "PUT, LIMITED", "PUT, DISABLED",
        "PATCH, ENABLED", "PATCH, LIMITED", "PATCH, DISABLED"
    })
    void clearingModeShouldHandleMissingBookUsingFallback(String method, UserSearchMode fallback) {
        // Given a domain without a book and a configured fallback mode
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        restartServerWithFallback(domain.domain(), fallback);
        assertThat(client.listDomainAddressBookUrls(domain.id(), CardDavClient.MirrorAddressBooks.EXCLUDE).collectList().block())
            .doesNotContain(book);
        String payload = """
            {
              "userSearchMode": null,
              "resourceSearchEnabled": null,
              "defaultCalendarPublicVisibility": null,
              "calendarPublicVisibilitySettingEnabled": null
            }
            """;

        // When clearing the mode is requested twice
        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .request(method, "/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));
        given()
            .contentType(ContentType.JSON)
            .body(payload)
        .when()
            .request(method, "/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204)
            .body(equalTo(""));

        // Then no explicit mode is stored and book access follows the fallback
        assertThat(domainSettingsDAO.retrieve(domain.domain()).block().userSearchMode()).isEmpty();
        if (fallback == UserSearchMode.DISABLED) {
            assertThat(client.listDomainAddressBookUrls(domain.id(), CardDavClient.MirrorAddressBooks.EXCLUDE).collectList().block())
                .doesNotContain(book);
        } else {
            assertThat(client.listDomainAddressBookUrls(domain.id(), CardDavClient.MirrorAddressBooks.EXCLUDE).collectList().block())
                .contains(book);
            if (fallback == UserSearchMode.ENABLED) {
                client.exportContact(member.username(), book).block();
            } else {
                assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
                    .hasMessageContaining("403");
            }
        }
    }

    @Test
    void patchingOtherSettingsShouldNotRequireAnAddressBook() {
        // Given a domain without a domain-members address book
        OpenPaaSDomain domain = domainDAO.add(Domain.of("no-book-" + UUID.randomUUID() + ".example")).block();

        // When only another setting is patched
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"resourceSearchEnabled": false}
                """)
        .when()
            .patch("/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204);
    }

    @Test
    void retryShouldCompleteAnUpdateAfterMongoRejectsTheSetting() {
        // Given an enabled domain whose MongoDB validator refuses the limited mode
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        String cardUid = UUID.randomUUID().toString();
        byte[] card = createDomainMemberCard(domain, cardUid);
        patchUserSearchMode(domain, "enabled");
        Mono.from(sabreDavExtension.dockerSabreDavSetup().getMongoDB().runCommand(
            new Document("collMod", MongoDBDomainSettingsDAO.COLLECTION)
                .append("validator", new Document("userSearchMode", new Document("$ne", "limited"))))).block();
        try {
            // When the ACL update succeeds but saving the setting fails
            given()
                .contentType(ContentType.JSON)
                .body("""
                    {"userSearchMode": "limited"}
                    """)
            .when()
                .patch("/domains/" + domain.domain().asString() + "/settings")
            .then()
                .statusCode(500);

            // Then the ACL stays restricted and the previously saved mode is retained
            assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
                .hasMessageContaining("403");
            assertThat(domainSettingsDAO.retrieve(domain.domain()).block().userSearchMode()).contains(UserSearchMode.ENABLED);
        } finally {
            Mono.from(sabreDavExtension.dockerSabreDavSetup().getMongoDB().runCommand(
                new Document("collMod", MongoDBDomainSettingsDAO.COLLECTION).append("validator", new Document()))).block();
        }

        // When the same update is retried after MongoDB recovers
        patchUserSearchMode(domain, "limited");
        patchUserSearchMode(domain, "limited");

        // Then the mode is saved without changing the card
        assertThat(domainSettingsDAO.retrieve(domain.domain()).block().userSearchMode()).contains(UserSearchMode.LIMITED);
        assertThat(client.retrieveContactDomainMembers(domain.id(), cardUid).block()).isEqualTo(card);
        assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
            .hasMessageContaining("403");
    }

    @Test
    void reapplyingTheSameModeShouldRepairAclDrift() {
        // Given a saved limited mode whose ACL has drifted to readable
        OpenPaaSUser member = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = domainDAO.retrieve(member.username().getDomainPart().orElseThrow()).block();
        AddressBookURL book = new AddressBookURL(domain.id(), CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID);
        client.createDomainMembersAddressBook(domain.id()).block();
        patchUserSearchMode(domain, "limited");
        client.updateDomainMembersAcl(domain.id(), true).block();

        // When the same mode is applied again
        patchUserSearchMode(domain, "limited");

        // Then member read access is revoked again
        assertThatThrownBy(() -> client.exportContact(member.username(), book).block())
            .hasMessageContaining("403");
    }

    private byte[] createDomainMemberCard(OpenPaaSDomain domain, String cardUid) {
        byte[] card = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:Directory Member
            END:VCARD
            """.formatted(cardUid).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        client.upsertContactDomainMembers(domain.id(), cardUid, card).block();
        return card;
    }

    private void patchUserSearchMode(OpenPaaSDomain domain, String mode) {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("userSearchMode", mode))
        .when()
            .patch("/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204);
    }

    private void restartServerWithFallback(Domain domain, UserSearchMode fallback) {
        webAdminServer.destroy();
        DomainSettingsResolver resolver = new DomainSettingsResolver(domainSettingsDAO,
            fallback == UserSearchMode.DISABLED ? Set.of(domain) : Set.of(),
            fallback == UserSearchMode.LIMITED ? Set.of(domain) : Set.of(),
            new MapConfiguration(Map.of()));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainSettingsRoutes(domainSettingsDAO, resolver, domainDAO, new JsonTransformer(), client))
            .start();
        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }
}
