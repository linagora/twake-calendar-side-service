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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
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

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.saas.SaaSUserProvisioner;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.MemoryDomainSettingsDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.UserContactResolver;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.DavDomainMembersClearService;
import com.linagora.calendar.webadmin.service.DomainMembersUserSearchModeService;
import com.linagora.calendar.webadmin.task.DomainMembersUserSearchModeTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

class DomainSettingsDomainMembersTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private static final String LIMITED_BY_CONFIGURATION = "limited-by-conf-";

    private WebAdminServer webAdminServer;
    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBOpenPaaSUserDAO userDAO;
    private SaaSUserProvisioner provisioner;
    private OpenPaaSDomain domain;
    private OpenPaaSDomain configuredLimitedDomain;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        domain = createDomain("domain-");
        configuredLimitedDomain = createDomain(LIMITED_BY_CONFIGURATION);

        MemoryDomainSettingsDAO domainSettingsDAO = new MemoryDomainSettingsDAO();
        DomainSettingsResolver resolver = new DomainSettingsResolver(domainSettingsDAO,
            Set.of(), Set.of(configuredLimitedDomain.domain()), new MapConfiguration(Map.of()));
        provisioner = new SaaSUserProvisioner(userDAO, domainDAO, cardDavClient, resolver, new UserContactResolver.Noop());
        DomainMembersUserSearchModeService service = new DomainMembersUserSearchModeService(resolver, userDAO, provisioner,
            new DavDomainMembersClearService(cardDavClient));

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainSettingsRoutes(domainSettingsDAO, resolver, domainDAO, new JsonTransformer(), taskManager, service),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>of(
                        DomainMembersUserSearchModeTaskAdditionalInformationDTO.module()))))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void leavingEnabledShouldClearDomainMembers() {
        addMember(domain, "alice");
        addMember(domain, "bob");
        assertThat(domainMembersVcards(domain)).contains("alice@", "bob@");

        String taskId = patchUserSearchMode(domain, "\"limited\"");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("align-domain-members-with-user-search-mode"))
            .body("additionalInformation.domain", is(domain.domain().asString()))
            .body("additionalInformation.userSearchMode", is("limited"))
            .body("additionalInformation.deletedCount", is(2))
            .body("additionalInformation.populatedCount", is(0));

        assertThat(domainMembersVcards(domain)).doesNotContain("alice@", "bob@");
    }

    @Test
    void enteringEnabledShouldPopulateDomainMembersWithRegisteredUsers() {
        awaitTask(patchUserSearchMode(domain, "\"disabled\""));
        registerUser(domain, "alice");
        registerUser(domain, "bob");
        assertThat(domainMembersVcards(domain)).doesNotContain("alice@", "bob@");

        String taskId = patchUserSearchMode(domain, "\"enabled\"");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.userSearchMode", is("enabled"))
            .body("additionalInformation.populatedCount", is(2))
            .body("additionalInformation.deletedCount", is(0));

        assertThat(domainMembersVcards(domain)).contains("alice@" + domain.domain().asString(), "bob@" + domain.domain().asString());
    }

    @Test
    void populatingShouldKeepTheCardAMemberAlreadyHas() throws Exception {
        awaitTask(patchUserSearchMode(domain, "\"limited\""));
        Username alice = registerUser(domain, "alice");
        // Written while limited, as the administrator LDAP sync would do
        AddressBookContact card = AddressBookContact.builder()
            .mail(alice.asMailAddress())
            .build();
        cardDavClient.upsertContactDomainMembers(domain.id(), card.vcardUid(), card.toVcardBytes()).block();

        awaitTask(patchUserSearchMode(domain, "\"enabled\""));

        assertThat(domainMembersVcards(domain).split("BEGIN:VCARD", -1)).hasSize(2);
    }

    @Test
    void putShouldAlsoAlignDomainMembers() {
        addMember(domain, "alice");

        String taskId = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "userSearchMode": "disabled",
                  "resourceSearchEnabled": null,
                  "defaultCalendarPublicVisibility": null,
                  "calendarPublicVisibilitySettingEnabled": null
                }
                """)
            .put("/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(201)
            .header("Location", startsWith("/tasks/"))
            .extract()
            .jsonPath()
            .getString("taskId");

        awaitTask(taskId);
        assertThat(domainMembersVcards(domain)).doesNotContain("alice@");
    }

    @Test
    void changeBetweenLimitedAndDisabledShouldStartNoTask() {
        awaitTask(patchUserSearchMode(domain, "\"limited\""));

        patchUserSearchModeStartingNoTask(domain, "\"disabled\"");
    }

    @Test
    void changeOfAnotherSettingShouldStartNoTask() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"resourceSearchEnabled\": false}")
            .patch("/domains/" + domain.domain().asString() + "/settings")
        .then()
            .statusCode(204);
    }

    @Test
    void effectiveModeShouldIncludeTheConfiguredFallback() {
        registerUser(configuredLimitedDomain, "alice");

        // No setting stored: the configuration makes the domain limited, so enabling it fills the book
        awaitTask(patchUserSearchMode(configuredLimitedDomain, "\"enabled\""));
        assertThat(domainMembersVcards(configuredLimitedDomain)).contains("alice@");

        // Removing the setting falls back to the configured limited mode, so the book is cleared
        awaitTask(patchUserSearchMode(configuredLimitedDomain, "null"));
        assertThat(domainMembersVcards(configuredLimitedDomain)).doesNotContain("alice@");

        // Limited by configuration, explicitly limited: no change of the effective mode
        patchUserSearchModeStartingNoTask(configuredLimitedDomain, "\"limited\"");
    }

    @Test
    void theLastChangeShouldWinWhateverTheOrderOfTheTasks() {
        registerUser(domain, "alice");
        String clearTask = patchUserSearchMode(domain, "\"limited\"");
        String populateTask = patchUserSearchMode(domain, "\"enabled\"");

        awaitTask(clearTask);
        awaitTask(populateTask);

        assertThat(domainMembersVcards(domain)).contains("alice@");
    }

    private OpenPaaSDomain createDomain(String prefix) {
        OpenPaaSDomain created = domainDAO.add(Domain.of(prefix + UUID.randomUUID() + ".tld")).block();
        cardDavClient.createDomainMembersAddressBook(created.id()).block();
        return created;
    }

    private Username registerUser(OpenPaaSDomain targetDomain, String localPart) {
        Username username = Username.of(localPart + "@" + targetDomain.domain().asString());
        userDAO.add(username).block();
        return username;
    }

    private void addMember(OpenPaaSDomain targetDomain, String localPart) {
        Username username = Username.of(localPart + "@" + targetDomain.domain().asString());
        if (userDAO.retrieve(username).blockOptional().isEmpty()) {
            userDAO.add(username).block();
        }
        provisioner.addToDomainAddressBook(username).block();
    }

    private String patchUserSearchMode(OpenPaaSDomain targetDomain, String jsonValue) {
        return given()
            .contentType(ContentType.JSON)
            .body("{\"userSearchMode\": " + jsonValue + "}")
            .patch("/domains/" + targetDomain.domain().asString() + "/settings")
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");
    }

    private void patchUserSearchModeStartingNoTask(OpenPaaSDomain targetDomain, String jsonValue) {
        given()
            .contentType(ContentType.JSON)
            .body("{\"userSearchMode\": " + jsonValue + "}")
            .patch("/domains/" + targetDomain.domain().asString() + "/settings")
        .then()
            .statusCode(204);
    }

    private void awaitTask(String taskId) {
        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"));
    }

    private String domainMembersVcards(OpenPaaSDomain targetDomain) {
        return cardDavClient.listContactDomainMembers(targetDomain.id())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }
}
