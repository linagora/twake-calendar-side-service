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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.net.ssl.SSLException;

import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.AddressBookImportService;
import com.linagora.calendar.webadmin.task.AddressBookImportTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

public class UserAddressBookRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private CardDavClient cardDavClient;
    private DavTestHelper davTestHelper;

    private OpenPaaSUser user;
    private OpenPaaSUser otherUser;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = sabreDavExtension.davTestHelper();

        user = sabreDavExtension.newTestUser();
        otherUser = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new UserAddressBookRoutes(userDAO, cardDavClient, new AddressBookImportService(cardDavClient), taskManager),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(AddressBookImportTaskAdditionalInformationDTO.module())
                        .build())))
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

    @ParameterizedTest
    @MethodSource("deleteAddressBookErrorCases")
    void deleteAddressBookShouldReturnErrorForInvalidCases(String addressBookId, int statusCode, String type, String message) {
        given()
        .when()
            .delete("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(statusCode)
            .body("type", is(type))
            .body("message", is(message));
    }

    static Stream<Arguments> deleteAddressBookErrorCases() {
        return Stream.of(
            Arguments.of("00000000-0000-0000-0000-000000000000", 404, "notFound", "Address book does not exist"),
            Arguments.of("contacts", 400, "InvalidArgument", "Cannot delete system address book"));
    }

    @Test
    void updateAddressBookShouldUpdateNameAndDescription() {
        String addressBookId = createAddressBook(user, "Old name");

        given()
            .body("""
                {"dav:name":"New name","carddav:description":"New description"}
                """)
        .when()
            .patch("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);

        assertThat(retrieveAddressBook(user, addressBookId))
            .containsEntry("dav:name", "New name")
            .containsEntry("carddav:description", "New description");
    }

    @Test
    void updateAddressBookShouldLeaveOmittedFieldsUnchanged() {
        String addressBookId = createAddressBook(user, "Old name");

        given()
            .body("""
                {"carddav:description":"New description"}
                """)
        .when()
            .patch("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(204);

        assertThat(retrieveAddressBook(user, addressBookId))
            .containsEntry("dav:name", "Old name")
            .containsEntry("carddav:description", "New description");
    }

    @Test
    void updateAddressBookShouldUpdateSubscriptionWithoutUpdatingSource() {
        // Given
        String sourceAddressBookId = createAddressBook(user, "Source name");
        String subscriptionId = UUID.randomUUID().toString();
        AddressBookURL sourceAddressBookURL = new AddressBookURL(user.id(), sourceAddressBookId);
        cardDavClient.updateAddressBookPublicRight(user.username(), sourceAddressBookURL, true).block();
        davTestHelper.createAddressBookSubscription(otherUser, subscriptionId, "Subscription name", sourceAddressBookURL).block();

        assertThat(retrieveAddressBook(otherUser, subscriptionId))
            .containsEntry("dav:name", "Subscription name");

        // When
        given()
            .body("""
                {"dav:name":"Updated subscription","carddav:description":"Updated subscription description"}
                """)
        .when()
            .patch("/users/{username}/addressbooks/{addressBookId}", otherUser.username().asString(), subscriptionId)
        .then()
            .statusCode(204);

        // Then
        assertThat(retrieveAddressBook(otherUser, subscriptionId))
            .containsEntry("dav:name", "Updated subscription")
            .containsEntry("carddav:description", "Updated subscription description");
        assertThat(retrieveAddressBook(user, sourceAddressBookId))
            .containsEntry("dav:name", "Source name");
    }

    @Test
    void updateAddressBookShouldUpdateDelegationWithoutUpdatingSource() {
        // Given
        String sourceAddressBookId = createAddressBook(user, "Source name");
        cardDavClient.updateAddressBookShares(user.username(), new AddressBookURL(user.id(), sourceAddressBookId),
            List.of(new CardDavClient.AddressBookSharee("mailto:" + otherUser.username().asString(), 3))).block();
        String delegatedAddressBookId = cardDavClient.listUserAddressBookIds(otherUser.username(), otherUser.id())
            .filter(addressBook -> addressBook.type() == CardDavClient.AddressBookType.USER)
            .single()
            .map(CardDavClient.AddressBook::value)
            .block();

        // When
        given()
            .body("""
                {"dav:name":"Updated delegation","carddav:description":"Updated delegation description"}
                """)
        .when()
            .patch("/users/{username}/addressbooks/{addressBookId}", otherUser.username().asString(), delegatedAddressBookId)
        .then()
            .statusCode(204);

        // Then
        assertThat(retrieveAddressBook(otherUser, delegatedAddressBookId))
            .containsEntry("dav:name", "Updated delegation")
            .containsEntry("carddav:description", "Updated delegation description");
        assertThat(retrieveAddressBook(user, sourceAddressBookId))
            .containsEntry("dav:name", "Source name");
    }

    @ParameterizedTest
    @MethodSource("updateAddressBookErrorCases")
    void updateAddressBookShouldReturnErrorForInvalidCases(String addressBookId, String body, int statusCode, String type, String message) {
        given()
            .body(body)
        .when()
            .patch("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(statusCode)
            .body("type", is(type))
            .body("message", containsString(message));
    }

    static Stream<Arguments> updateAddressBookErrorCases() {
        String validBody = "{\"dav:name\":\"New name\"}";
        return Stream.of(
            Arguments.of("00000000-0000-0000-0000-000000000000", validBody, 404, "notFound", "Address book does not exist"),
            Arguments.of("contacts", validBody, 400, "InvalidArgument", "Cannot update system address book"),
            Arguments.of("contacts", "{}", 400, "InvalidArgument", "At least one of 'dav:name', 'carddav:description' must be provided"),
            Arguments.of("contacts", "{\"unknown\":\"value\"}", 400, "InvalidArgument", "Invalid request body"),
            Arguments.of("contacts", "not json", 400, "InvalidArgument", "Invalid request body"));
    }

    @Test
    void updateAddressBookShouldReturn404WhenUserDoesNotExist() {
        given()
            .body("""
                {"dav:name":"New name"}
                """)
        .when()
            .patch("/users/ghost@linagora.com/addressbooks/contacts")
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    @Test
    void exportShouldReturnContactsOfTheAddressBook() {
        String addressBookId = createAddressBook(user, "To be exported");
        upsertContact(user, addressBookId, "John Doe", "john.doe@linagora.com");
        upsertContact(user, addressBookId, "Jane Doe", "jane.doe@linagora.com");

        String vcard = given()
        .when()
            .queryParam("action", "export")
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .header("Content-Type", "text/vcard; charset=utf-8")
            .extract()
            .asString();

        assertThat(vcard).contains("EMAIL:john.doe@linagora.com", "EMAIL:jane.doe@linagora.com");
    }

    @Test
    void exportShouldReturnEmptyResultWhenAddressBookHasNoContact() {
        String addressBookId = createAddressBook(user, "Empty address book");

        String vcard = given()
        .when()
            .queryParam("action", "export")
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(vcard).isEmpty();
    }

    @Test
    void exportShouldNotReturnContactsOfOtherAddressBooks() {
        String addressBookId = createAddressBook(user, "Exported address book");
        String otherAddressBookId = createAddressBook(user, "Other address book");
        upsertContact(user, addressBookId, "John Doe", "john.doe@linagora.com");
        upsertContact(user, otherAddressBookId, "Jane Doe", "jane.doe@linagora.com");

        String vcard = given()
        .when()
            .queryParam("action", "export")
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(vcard).doesNotContain("jane.doe@linagora.com");
    }

    @Test
    void exportShouldReturn404WhenAddressBookDoesNotExist() {
        given()
        .when()
            .queryParam("action", "export")
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void exportShouldReturn404WhenUserDoesNotExist() {
        given()
        .when()
            .queryParam("action", "export")
            .post("/users/ghost@linagora.com/addressbooks/contacts")
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    @Test
    void importShouldAddContactsToTheAddressBook() {
        String addressBookId = createAddressBook(user, "Address book to fill");

        awaitTask(importAddressBook(user, addressBookId,
            vcard("imported-1", "John Doe", "john.doe@linagora.com")
                + vcard("imported-2", "Jane Doe", "jane.doe@linagora.com")));

        assertThat(exportAddressBook(user, addressBookId))
            .contains("EMAIL:john.doe@linagora.com", "EMAIL:jane.doe@linagora.com");
    }

    @Test
    void importShouldReturnCompletedTaskDetails() {
        String addressBookId = createAddressBook(user, "Address book to fill");

        String taskId = importAddressBook(user, addressBookId,
            vcard("imported-1", "John Doe", "john.doe@linagora.com")
                + vcard("imported-2", "Jane Doe", "jane.doe@linagora.com"));

        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("addressbook-import"))
            .body("additionalInformation.username", is(user.username().asString()))
            .body("additionalInformation.addressBookId", is(addressBookId))
            .body("additionalInformation.totalContactCount", is(2))
            .body("additionalInformation.importedContactCount", is(2))
            .body("additionalInformation.failedContactCount", is(0));
    }

    @Test
    void importShouldUpdateAlreadyExistingContacts() {
        String addressBookId = createAddressBook(user, "Address book to update");
        awaitTask(importAddressBook(user, addressBookId, vcard("imported-1", "John Doe", "john.doe@linagora.com")));

        awaitTask(importAddressBook(user, addressBookId, vcard("imported-1", "John Doe", "john.doe@twake.app")));

        given()
        .when()
            .get("/users/{username}/addressbooks/{addressBookId}/contactCount", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .body("count", is(1));

        assertThat(exportAddressBook(user, addressBookId))
            .contains("EMAIL:john.doe@twake.app")
            .doesNotContain("EMAIL:john.doe@linagora.com");
    }

    @Test
    void importShouldSupportTheOutputOfExport() {
        String sourceAddressBookId = createAddressBook(user, "Source address book");
        upsertContact(user, sourceAddressBookId, "John Doe", "john.doe@linagora.com");
        String targetAddressBookId = createAddressBook(user, "Target address book");

        awaitTask(importAddressBook(user, targetAddressBookId, exportAddressBook(user, sourceAddressBookId)));

        assertThat(exportAddressBook(user, targetAddressBookId))
            .contains("EMAIL:john.doe@linagora.com");
    }

    @Test
    void importShouldReturn400WhenNoContactToImport() {
        String addressBookId = createAddressBook(user, "Address book to fill");

        given()
            .queryParam("action", "import")
            .body("not a vCard")
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", containsString("no contact to import"));
    }

    @Test
    void importShouldReturn404WhenAddressBookDoesNotExist() {
        given()
            .queryParam("action", "import")
            .body(vcard("imported-1", "John Doe", "john.doe@linagora.com"))
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void importShouldReturn404WhenUserDoesNotExist() {
        given()
            .queryParam("action", "import")
            .body(vcard("imported-1", "John Doe", "john.doe@linagora.com"))
        .when()
            .post("/users/ghost@linagora.com/addressbooks/contacts")
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPORT", "Export", "eXpOrT"})
    void exportShouldBeCaseInsensitive(String action) {
        String addressBookId = createAddressBook(user, "To be exported");
        upsertContact(user, addressBookId, "John Doe", "john.doe@linagora.com");

        String vcard = given()
        .when()
            .queryParam("action", action)
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(vcard).contains("EMAIL:john.doe@linagora.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORT", "Import", "iMpOrT"})
    void importShouldBeCaseInsensitive(String action) {
        String addressBookId = createAddressBook(user, "Address book to fill");

        String taskId = given()
            .queryParam("action", action)
            .body(vcard("imported-1", "John Doe", "john.doe@linagora.com"))
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
        awaitTask(taskId);

        assertThat(exportAddressBook(user, addressBookId)).contains("EMAIL:john.doe@linagora.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unsupported"})
    void postShouldReturn400WhenActionIsNotSupported(String action) {
        String addressBookId = createAddressBook(user, "Address book");

        given()
        .when()
            .queryParam("action", action)
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void postShouldReturn400WhenActionIsMissing() {
        String addressBookId = createAddressBook(user, "Address book");

        given()
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
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

    @ParameterizedTest
    @MethodSource("publicRightErrorCases")
    void publicRightShouldReturnErrorForInvalidCases(String body, int statusCode, String type) {
        given()
            .body(body)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/publicRight", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(statusCode)
            .body("type", is(type));
    }

    static Stream<Arguments> publicRightErrorCases() {
        return Stream.of(
            Arguments.of("{\"public_right\":\"{DAV:}write\"}", 400, "InvalidArgument"),
            Arguments.of("{\"public_right\":\"{DAV:}read\"}", 404, "notFound"));
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

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"dav:sharee\":[{\"dav:href\":\"mailto:anyone@linagora.com\",\"dav:share-access\":99}]}"
    })
    void inviteeShouldFailWhenPayloadIsInvalid(String body) {
        String addressBookId = createAddressBook(user, "Address book");

        given()
            .body(body)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}/invitee", user.username().asString(), addressBookId)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void contactCountShouldReturnZeroWhenAddressBookIsEmpty() {
        String addressBookId = createAddressBook(user, "Empty address book");

        given()
        .when()
            .get("/users/{username}/addressbooks/{addressBookId}/contactCount", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    @Test
    void contactCountShouldReturnContactCount() {
        String addressBookId = createAddressBook(user, "Filled address book");
        IntStream.range(0, 3).forEach(i -> createContact(addressBookId, "John Doe " + i));

        given()
        .when()
            .get("/users/{username}/addressbooks/{addressBookId}/contactCount", user.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .body("count", is(3));
    }

    @Test
    void contactCountShouldReturn404WhenAddressBookDoesNotExist() {
        given()
        .when()
            .get("/users/{username}/addressbooks/{addressBookId}/contactCount", user.username().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void contactCountShouldReturn404WhenUserDoesNotExist() {
        given()
        .when()
            .get("/users/ghost@linagora.com/addressbooks/contacts/contactCount")
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("User does not exist"));
    }

    private String importAddressBook(OpenPaaSUser targetUser, String addressBookId, String vcards) {
        return given()
            .queryParam("action", "import")
            .body(vcards)
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}", targetUser.username().asString(), addressBookId)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
    }

    private String exportAddressBook(OpenPaaSUser targetUser, String addressBookId) {
        return given()
            .queryParam("action", "export")
        .when()
            .post("/users/{username}/addressbooks/{addressBookId}", targetUser.username().asString(), addressBookId)
        .then()
            .statusCode(200)
            .extract()
            .asString();
    }

    private void awaitTask(String taskId) {
        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"));
    }

    private String vcard(String uid, String fullName, String email) {
        return """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:%s
            EMAIL:%s
            END:VCARD
            """.formatted(uid, fullName, email);
    }

    private void createContact(String addressBookId, String fullName) {
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:%s
            END:VCARD
            """.formatted(vcardUid, fullName);
        cardDavClient.upsertContact(user.username(), new AddressBookURL(user.id(), addressBookId), vcardUid,
            vcard.getBytes(StandardCharsets.UTF_8)).block();
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

    private void upsertContact(OpenPaaSUser owner, String addressBookId, String fullName, String email) {
        String contactUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:%s
            EMAIL:%s
            END:VCARD
            """.formatted(contactUid, fullName, email);

        cardDavClient.upsertContact(owner.username(), new AddressBookURL(owner.id(), addressBookId), contactUid,
            vcard.getBytes(StandardCharsets.UTF_8)).block();
    }

    private Map<String, Object> retrieveAddressBook(OpenPaaSUser targetUser, String addressBookId) {
        String body = given()
        .when()
            .get("/users/{username}/addressbooks", targetUser.username().asString())
        .then()
            .statusCode(200)
            .extract()
            .asString();

        return JsonPath.from(body)
            .getMap("_embedded.'dav:addressbook'.find { it._links.self.href == '/addressbooks/%s/%s.json' }"
                .formatted(targetUser.id().value(), addressBookId));
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
