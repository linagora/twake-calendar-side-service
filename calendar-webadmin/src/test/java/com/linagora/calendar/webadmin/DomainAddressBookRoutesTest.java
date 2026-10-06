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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.AddressBookImportService;
import com.linagora.calendar.webadmin.service.DomainAddressBookClearService;
import com.linagora.calendar.webadmin.task.DomainAddressBookClearTaskAdditionalInformationDTO;
import com.linagora.calendar.webadmin.task.DomainAddressBookImportTaskAdditionalInformationDTO;

import io.restassured.RestAssured;

public class DomainAddressBookRoutesTest {

    private static final String DOMAIN_ADDRESS_BOOK = "dab";
    private static final String DOMAIN_MEMBERS_ADDRESS_BOOK = "domain-members";

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private CardDavClient cardDavClient;
    private DavTestHelper davTestHelper;
    private OpenPaaSDomain domain;

    @BeforeEach
    void setUp() throws SSLException {
        domainDAO = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB());
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = sabreDavExtension.davTestHelper();
        domain = domainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainAddressBookRoutes(domainDAO, cardDavClient, new AddressBookImportService(cardDavClient),
                    new DomainAddressBookClearService(cardDavClient), taskManager, new JsonTransformer()),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(DomainAddressBookImportTaskAdditionalInformationDTO.module())
                        .add(DomainAddressBookClearTaskAdditionalInformationDTO.module())
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
    void contactCountShouldReturnZeroWhenDomainAddressBookIsEmpty() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    @Test
    void contactCountShouldReturnDomainAddressBookContactCount() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        IntStream.range(0, 3).forEach(i -> upsertDomainContact(DOMAIN_ADDRESS_BOOK));

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(3));
    }

    @Test
    void contactCountShouldReturnDomainMembersContactCount() {
        IntStream.range(0, 2).forEach(i -> upsertDomainMember());

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(2));
    }

    @Test
    void contactCountShouldNotCountContactsOfOtherAddressBooks() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        upsertDomainContact(DOMAIN_ADDRESS_BOOK);
        upsertDomainMember();

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(1));
    }

    @Test
    void contactCountShouldNotCountContactsOfOtherDomains() {
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other-domain" + UUID.randomUUID() + ".tld")).block();
        davTestHelper.createDomainAddressBook(domain.id()).block();
        davTestHelper.createDomainAddressBook(otherDomain.id()).block();
        upsertDomainContact(DOMAIN_ADDRESS_BOOK);

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", otherDomain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    @Test
    void contactCountShouldReturn404WhenAddressBookDoesNotExist() {
        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void contactCountShouldReturn404WhenDomainDoesNotExist() {
        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", "ghost.tld", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Domain does not exist"));
    }

    @Test
    void contactCountShouldReturn400WhenDomainIsInvalid() {
        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", "invalid@domain", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void exportShouldReturnContactsOfTheDomainAddressBook() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        String firstUid = upsertDomainContact(DOMAIN_ADDRESS_BOOK);
        String secondUid = upsertDomainContact(DOMAIN_ADDRESS_BOOK);

        String vcard = given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .header("Content-Type", "text/vcard; charset=utf-8")
            .extract()
            .asString();

        assertThat(vcard).contains(firstUid + "@example.com", secondUid + "@example.com");
    }

    @Test
    void exportShouldReturnContactsOfTheDomainMembersAddressBook() {
        String uid = upsertDomainMember();

        assertThat(exportAddressBook(DOMAIN_MEMBERS_ADDRESS_BOOK)).contains(uid + "@example.com");
    }

    @Test
    void exportShouldReturnEmptyResultWhenAddressBookHasNoContact() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        assertThat(exportAddressBook(DOMAIN_ADDRESS_BOOK)).isEmpty();
    }

    @Test
    void exportShouldNotReturnContactsOfOtherAddressBooks() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        upsertDomainContact(DOMAIN_ADDRESS_BOOK);
        String memberUid = upsertDomainMember();

        assertThat(exportAddressBook(DOMAIN_ADDRESS_BOOK)).doesNotContain(memberUid);
    }

    @Test
    void exportShouldNotReturnContactsOfOtherDomains() {
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other-domain" + UUID.randomUUID() + ".tld")).block();
        davTestHelper.createDomainAddressBook(domain.id()).block();
        davTestHelper.createDomainAddressBook(otherDomain.id()).block();
        String uid = upsertDomainContact(DOMAIN_ADDRESS_BOOK);

        String vcard = given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", otherDomain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(vcard).doesNotContain(uid);
    }

    @Test
    void exportShouldReturn404WhenAddressBookDoesNotExist() {
        given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void exportShouldReturn404WhenDomainDoesNotExist() {
        given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", "ghost.tld", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Domain does not exist"));
    }

    @Test
    void exportShouldReturn400WhenDomainIsInvalid() {
        given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", "invalid@domain", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPORT", "Export", "eXpOrT"})
    void exportShouldBeCaseInsensitive(String action) {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        String uid = upsertDomainContact(DOMAIN_ADDRESS_BOOK);

        String vcard = given()
            .queryParam("action", action)
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(vcard).contains(uid + "@example.com");
    }

    @Test
    void importShouldAddContactsToTheDomainAddressBook() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        awaitTask(importAddressBook(DOMAIN_ADDRESS_BOOK, vcard("imported-1") + vcard("imported-2")));

        assertThat(exportAddressBook(DOMAIN_ADDRESS_BOOK))
            .contains("imported-1@example.com", "imported-2@example.com");
    }

    @Test
    void importShouldReturnCompletedTaskDetails() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        String taskId = importAddressBook(DOMAIN_ADDRESS_BOOK, vcard("imported-1") + vcard("imported-2"));

        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("domain-addressbook-import"))
            .body("additionalInformation.domain", is(domain.domain().asString()))
            .body("additionalInformation.addressBookId", is(DOMAIN_ADDRESS_BOOK))
            .body("additionalInformation.totalContactCount", is(2))
            .body("additionalInformation.importedContactCount", is(2))
            .body("additionalInformation.failedContactCount", is(0));
    }

    @Test
    void importShouldUpdateAlreadyExistingContacts() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        awaitTask(importAddressBook(DOMAIN_ADDRESS_BOOK, vcard("imported-1", "John Doe")));

        awaitTask(importAddressBook(DOMAIN_ADDRESS_BOOK, vcard("imported-1", "John Updated")));

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(1));

        assertThat(exportAddressBook(DOMAIN_ADDRESS_BOOK))
            .contains("FN:John Updated")
            .doesNotContain("FN:John Doe");
    }

    @Test
    void importShouldSupportTheOutputOfExport() {
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other-domain" + UUID.randomUUID() + ".tld")).block();
        davTestHelper.createDomainAddressBook(domain.id()).block();
        davTestHelper.createDomainAddressBook(otherDomain.id()).block();
        String uid = upsertDomainContact(DOMAIN_ADDRESS_BOOK);

        String taskId = given()
            .queryParam("action", "import")
            .body(exportAddressBook(DOMAIN_ADDRESS_BOOK))
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", otherDomain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
        awaitTask(taskId);

        String vcard = given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", otherDomain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .extract()
            .asString();

        assertThat(vcard).contains(uid + "@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORT", "Import", "iMpOrT"})
    void importShouldBeCaseInsensitive(String action) {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        String taskId = given()
            .queryParam("action", action)
            .body(vcard("imported-1"))
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
        awaitTask(taskId);

        assertThat(exportAddressBook(DOMAIN_ADDRESS_BOOK)).contains("imported-1@example.com");
    }

    @Test
    void importShouldReturn400WhenNoContactToImport() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        given()
            .queryParam("action", "import")
            .body("not a vCard")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", containsString("no contact to import"));
    }

    @Test
    void importShouldReturn400WhenTargetingDomainMembers() {
        upsertDomainMember();

        given()
            .queryParam("action", "import")
            .body(vcard("imported-1"))
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Cannot import into the 'domain-members' address book"));
    }

    @Test
    void importShouldReturn404WhenAddressBookDoesNotExist() {
        given()
            .queryParam("action", "import")
            .body(vcard("imported-1"))
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void importShouldReturn404WhenDomainDoesNotExist() {
        given()
            .queryParam("action", "import")
            .body(vcard("imported-1"))
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", "ghost.tld", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Domain does not exist"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unsupported"})
    void postShouldReturn400WhenActionIsNotSupported(String action) {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        given()
            .queryParam("action", action)
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"));
    }

    @Test
    void clearShouldDeleteAllContactsOfTheDomainAddressBook() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        IntStream.range(0, 3).forEach(i -> upsertDomainContact(DOMAIN_ADDRESS_BOOK));

        awaitTask(clearContacts(DOMAIN_ADDRESS_BOOK));

        assertContactCount(DOMAIN_ADDRESS_BOOK, 0);
    }

    @Test
    void clearShouldReturnCompletedTaskDetails() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        IntStream.range(0, 2).forEach(i -> upsertDomainContact(DOMAIN_ADDRESS_BOOK));

        String taskId = clearContacts(DOMAIN_ADDRESS_BOOK);

        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("domain-addressbook-clear"))
            .body("additionalInformation.domain", is(domain.domain().asString()))
            .body("additionalInformation.addressBookId", is(DOMAIN_ADDRESS_BOOK))
            .body("additionalInformation.sourceDomain", nullValue())
            .body("additionalInformation.deletedContactCount", is(2))
            .body("additionalInformation.failedContactCount", is(0));
    }

    @Test
    void clearShouldSucceedWhenAddressBookHasNoContact() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        awaitTask(clearContacts(DOMAIN_ADDRESS_BOOK));

        assertContactCount(DOMAIN_ADDRESS_BOOK, 0);
    }

    @Test
    void clearShouldDeleteOnlyContactsWithAMailAddressInTheSourceDomain() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        upsertDomainContact(DOMAIN_ADDRESS_BOOK, vcardWithMails("student-1", "student-1@student.school.org"));
        upsertDomainContact(DOMAIN_ADDRESS_BOOK, vcardWithMails("student-2", "student-2@STUDENT.school.org"));
        upsertDomainContact(DOMAIN_ADDRESS_BOOK, vcardWithMails("teacher", "teacher@school.org"));
        upsertDomainContact(DOMAIN_ADDRESS_BOOK, vcardWithMails("other", "other@other-student.school.org"));

        String taskId = given()
            .queryParam("sourceDomain", "student.school.org")
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");

        given()
        .when()
            .get(TasksRoutes.BASE + "/" + taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.sourceDomain", is("student.school.org"))
            .body("additionalInformation.deletedContactCount", is(2));

        assertThat(exportAddressBook(DOMAIN_ADDRESS_BOOK))
            .contains("teacher@school.org", "other@other-student.school.org")
            .doesNotContain("student-1@student.school.org")
            .doesNotContainIgnoringCase("student-2@student.school.org");
    }

    @Test
    void clearShouldDeleteContactsHavingOneOfTheirMailAddressesInTheSourceDomain() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        upsertDomainContact(DOMAIN_ADDRESS_BOOK, vcardWithMails("student", "student@school.org", "student@student.school.org"));

        String taskId = given()
            .queryParam("sourceDomain", "student.school.org")
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
        awaitTask(taskId);

        assertContactCount(DOMAIN_ADDRESS_BOOK, 0);
    }

    @Test
    void clearShouldNotDeleteContactsOfOtherDomains() {
        OpenPaaSDomain otherDomain = domainDAO.add(Domain.of("other-domain" + UUID.randomUUID() + ".tld")).block();
        davTestHelper.createDomainAddressBook(domain.id()).block();
        davTestHelper.createDomainAddressBook(otherDomain.id()).block();
        String uid = UUID.randomUUID().toString();
        davTestHelper.upsertDomainContact(otherDomain.id(), new AddressBookURL(otherDomain.id(), DOMAIN_ADDRESS_BOOK), uid, vcard(uid)).block();

        awaitTask(clearContacts(DOMAIN_ADDRESS_BOOK));

        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", otherDomain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(1));
    }

    @Test
    void clearShouldReturn400WhenSourceDomainIsInvalid() {
        davTestHelper.createDomainAddressBook(domain.id()).block();

        given()
            .queryParam("sourceDomain", "invalid@domain")
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", containsString("sourceDomain"));
    }

    @Test
    void clearShouldReturn400WhenTargetingDomainMembers() {
        upsertDomainMember();

        given()
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", domain.domain().asString(), DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(400)
            .body("type", is("InvalidArgument"))
            .body("message", is("Cannot clear the 'domain-members' address book"));
    }

    @Test
    void clearShouldReturn404WhenAddressBookDoesNotExist() {
        given()
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", domain.domain().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void clearShouldReturn404WhenDomainDoesNotExist() {
        given()
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", "ghost.tld", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Domain does not exist"));
    }

    private String clearContacts(String addressBookId) {
        return given()
        .when()
            .delete("/domains/{domain}/addressbooks/{addressBookId}/contacts", domain.domain().asString(), addressBookId)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
    }

    private void assertContactCount(String addressBookId, int expectedCount) {
        given()
        .when()
            .get("/domains/{domain}/addressbooks/{addressBookId}/contactCount", domain.domain().asString(), addressBookId)
        .then()
            .statusCode(200)
            .body("count", is(expectedCount));
    }

    private String importAddressBook(String addressBookId, String vcards) {
        return given()
            .queryParam("action", "import")
            .body(vcards)
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), addressBookId)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
    }

    private String exportAddressBook(String addressBookId) {
        return given()
            .queryParam("action", "export")
        .when()
            .post("/domains/{domain}/addressbooks/{addressBookId}", domain.domain().asString(), addressBookId)
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

    private String upsertDomainContact(String addressBookId) {
        String uid = UUID.randomUUID().toString();
        davTestHelper.upsertDomainContact(domain.id(), new AddressBookURL(domain.id(), addressBookId), uid, vcard(uid)).block();
        return uid;
    }

    private void upsertDomainContact(String addressBookId, String vcard) {
        davTestHelper.upsertDomainContact(domain.id(), new AddressBookURL(domain.id(), addressBookId), UUID.randomUUID().toString(), vcard).block();
    }

    private String upsertDomainMember() {
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid).getBytes(StandardCharsets.UTF_8)).block();
        return uid;
    }

    private String vcard(String uid) {
        return vcard(uid, uid);
    }

    private String vcard(String uid, String fullName) {
        return """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:%s
            EMAIL;TYPE=Work:%s@example.com
            END:VCARD
            """.formatted(uid, fullName, uid);
    }

    private String vcardWithMails(String uid, String... mailAddresses) {
        String emails = Arrays.stream(mailAddresses)
            .map("EMAIL;TYPE=Work:%s\n"::formatted)
            .collect(Collectors.joining());
        return "BEGIN:VCARD\nVERSION:4.0\nUID:%s\nFN:%s\n%sEND:VCARD\n".formatted(uid, uid, emails);
    }
}
