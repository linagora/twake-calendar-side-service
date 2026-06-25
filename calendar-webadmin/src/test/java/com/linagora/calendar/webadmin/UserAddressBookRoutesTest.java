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
import org.apache.james.webadmin.WebAdminUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

public class UserAddressBookRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private CardDavClient cardDavClient;

    private OpenPaaSUser user;
    private OpenPaaSUser otherUser;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        user = sabreDavExtension.newTestUser();
        otherUser = sabreDavExtension.newTestUser();

        webAdminServer = WebAdminUtils.createWebAdminServer(new UserAddressBookRoutes(userDAO, cardDavClient))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void listAddressBooksShouldReturnDefaultAddressBooks() {
        List<String> hrefs = listAddressBookHrefs(user);

        assertThat(hrefs)
            .contains("/addressbooks/%s/contacts.json".formatted(user.id().value()));
    }

    @Test
    void listAddressBooksShouldReturn404WhenUserDoesNotExist() {
        given()
        .when()
            .get("/users/ghost@linagora.com/addressbooks")
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    @Test
    void createAddressBookShouldCreateAddressBookInDav() {
        String addressBookId = UUID.randomUUID().toString();

        given()
            .body("""
                {"id":"%s","dav:name":"My Contacts","carddav:description":"Personal contacts"}
                """.formatted(addressBookId))
        .when()
            .post("/users/{username}/addressbooks", user.username().asString())
        .then()
            .statusCode(201)
            .body("id", is(addressBookId));

        assertThat(listAddressBookHrefs(user))
            .contains("/addressbooks/%s/%s.json".formatted(user.id().value(), addressBookId));
    }

    @Test
    void createAddressBookShouldGenerateIdWhenAbsent() {
        String addressBookId = given()
            .body("""
                {"dav:name":"Generated id book"}
                """)
        .when()
            .post("/users/{username}/addressbooks", user.username().asString())
        .then()
            .statusCode(201)
            .body("id", not(emptyString()))
            .extract()
            .jsonPath()
            .getString("id");

        assertThat(listAddressBookHrefs(user))
            .contains("/addressbooks/%s/%s.json".formatted(user.id().value(), addressBookId));
    }

    @Test
    void createAddressBookShouldFailWhenNameIsMissing() {
        given()
            .body("{}")
        .when()
            .post("/users/{username}/addressbooks", user.username().asString())
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void createAddressBookShouldReturn404WhenUserDoesNotExist() {
        given()
            .body("""
                {"dav:name":"My Contacts"}
                """)
        .when()
            .post("/users/ghost@linagora.com/addressbooks")
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void deleteAddressBookShouldDeleteAddressBook() {
        String addressBookId = createAddressBook(user, "To be deleted");

        given()
        .when()
            .delete("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);

        assertThat(listAddressBookHrefs(user))
            .doesNotContain("/addressbooks/%s/%s.json".formatted(user.id().value(), addressBookId));
    }

    @Test
    void deleteAddressBookShouldReturn404WhenAddressBookDoesNotExist() {
        given()
        .when()
            .delete("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void deleteAddressBookShouldReturn400WhenDeletingSystemAddressBook() {
        given()
        .when()
            .delete("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), "contacts")
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Cannot delete system address book"));
    }

    @Test
    void publicRightShouldPublishAddressBook() {
        String addressBookId = createAddressBook(user, "Soon to be public");

        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/publicRight", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);
    }

    @Test
    void publicRightShouldUnpublishAddressBook() {
        String addressBookId = createAddressBook(user, "Public then private");
        cardDavClient.updateAddressBookPublicRight(user.username(), new AddressBookURL(user.id(), addressBookId), true).block();

        given()
            .body("""
                {"public_right":""}
                """)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/publicRight", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);
    }

    @Test
    void publicRightShouldRejectUnsupportedValue() {
        String addressBookId = createAddressBook(user, "Address book");

        given()
            .body("""
                {"public_right":"{DAV:}write"}
                """)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/publicRight", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void publicRightShouldReturn404WhenAddressBookDoesNotExist() {
        given()
            .body("""
                {"public_right":"{DAV:}read"}
                """)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/publicRight", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void inviteeShouldGrantDelegation() {
        String addressBookId = createAddressBook(user, "Shared address book");

        given()
            .body("""
                {"dav:sharee":[{"dav:href":"mailto:%s","dav:share-access":3}]}
                """.formatted(otherUser.username().asString()))
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/invitee", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);
    }

    @Test
    void inviteeShouldRevokeDelegation() {
        String addressBookId = createAddressBook(user, "Shared address book");
        cardDavClient.updateAddressBookShares(user.username(), new AddressBookURL(user.id(), addressBookId),
            List.of(new CardDavClient.AddressBookSharee("mailto:" + otherUser.username().asString(), 3))).block();

        given()
            .body("""
                {"dav:sharee":[{"dav:href":"mailto:%s","dav:share-access":5}]}
                """.formatted(otherUser.username().asString()))
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/invitee", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);
    }

    @Test
    void inviteeShouldReturn404WhenAddressBookDoesNotExist() {
        given()
            .body("""
                {"dav:sharee":[]}
                """)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/invitee", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"));
    }

    @Test
    void inviteeShouldFailWhenShareeFieldIsMissing() {
        String addressBookId = createAddressBook(user, "Address book");

        given()
            .body("{}")
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/invitee", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void inviteeShouldFailWhenShareAccessIsInvalid() {
        String addressBookId = createAddressBook(user, "Address book");

        given()
            .body("""
                {"dav:sharee":[{"dav:href":"mailto:%s","dav:share-access":99}]}
                """.formatted(otherUser.username().asString()))
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/invitee", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    private String createAddressBook(OpenPaaSUser owner, String name) {
        return given()
            .body("""
                {"dav:name":"%s"}
                """.formatted(name))
        .when()
            .post("/users/{username}/addressbooks", owner.username().asString())
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    }

    private List<String> listAddressBookHrefs(OpenPaaSUser targetUser) {
        String body = given()
        .when()
            .get("/users/{username}/addressbooks", targetUser.username().asString())
        .then()
            .statusCode(200)
            .extract()
            .asString();

        List<String> hrefs = JsonPath.from(body).getList("_embedded.'dav:addressbook'._links.self.href");
        if (hrefs == null) {
            return List.of();
        }
        return hrefs;
    }
}
