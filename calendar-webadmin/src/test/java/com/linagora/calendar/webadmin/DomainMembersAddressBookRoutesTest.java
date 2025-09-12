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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
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

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ldap.LdapDomainMemberProvider;
import com.linagora.calendar.storage.ldap.LdapUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.DomainMembersAddressBookRoutes.LdapToDavDomainMembersSyncTaskRegistration;
import com.linagora.calendar.webadmin.service.LdapToDavDomainMembersSyncService;
import com.linagora.calendar.webadmin.task.DomainMembersSyncTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import net.javacrumbs.jsonunit.core.Option;
import reactor.core.publisher.Flux;

public class DomainMembersAddressBookRoutesTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private WebAdminServer webAdminServer;
    private LdapDomainMemberProvider ldapDomainMemberProvider;
    private OpenPaaSDomain openPaaSDomain;
    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSDomainDAO domainDAO;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);

        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        ldapDomainMemberProvider = mock(LdapDomainMemberProvider.class);
        LdapToDavDomainMembersSyncService syncService = new LdapToDavDomainMembersSyncService(
            ldapDomainMemberProvider, cardDavClient);

        LdapToDavDomainMembersSyncTaskRegistration ldapToDavDomainMembersSyncTaskRegistration = new LdapToDavDomainMembersSyncTaskRegistration(
            syncService, domainDAO);

        webAdminServer = WebAdminUtils.createWebAdminServer(new DomainMembersAddressBookRoutes(
                    new JsonTransformer(), taskManager,
                    ImmutableSet.of(ldapToDavDomainMembersSyncTaskRegistration)),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(DomainMembersSyncTaskAdditionalInformationDTO.module())
                        .build())))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
        Domain domain = Domain.of("new-domain" + UUID.randomUUID() + ".tld");
        openPaaSDomain = domainDAO.add(domain).block();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void allDomainsSyncTaskShouldBeRegistered() {
        LdapUser ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        mockLdapDomainMembersForDomain(openPaaSDomain.domain(), ldap);

        String taskId = given()
            .queryParam("task", "sync")
            .post(DomainMembersAddressBookRoutes.BASE_PATH)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        String taskResponse = given()
            .basePath(TasksRoutes.BASE)
            .when()
            .get(taskId + "/await")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(taskResponse)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                    "additionalInformation": {
                        "type": "sync-domain-members-contacts-ldap-to-dav",
                        "domain": null,
                        "timestamp": "${json-unit.any-string}",
                        "addedCount": 1,
                        "addFailureContacts": [],
                        "updatedCount": 0,
                        "updateFailureContacts": [],
                        "deletedCount": 0,
                        "deleteFailureContacts": []
                    },
                    "type": "sync-domain-members-contacts-ldap-to-dav",
                    "taskId": "%s",
                    "status": "completed"
                }""".formatted(taskId));
    }

    @Test
    void singleDomainSyncTaskShouldBeRegistered() {
        LdapUser ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        mockLdapDomainMembersForDomain(openPaaSDomain.domain(), ldap);

        String taskId = given()
            .queryParam("task", "sync")
            .post(DomainMembersAddressBookRoutes.BASE_PATH + "/" + openPaaSDomain.domain().asString())
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");

        String taskResponse = given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(taskResponse)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                    "additionalInformation": {
                        "type": "sync-domain-members-contacts-ldap-to-dav",
                        "domain": "{domain}",
                        "timestamp": "${json-unit.any-string}",
                        "addedCount": 1,
                        "addFailureContacts": [],
                        "updatedCount": 0,
                        "updateFailureContacts": [],
                        "deletedCount": 0,
                        "deleteFailureContacts": []
                    },
                    "type": "sync-domain-members-contacts-ldap-to-dav",
                    "taskId": "{taskId}",
                    "status": "completed"
                }""".replace("{taskId}", taskId)
                .replace("{domain}", openPaaSDomain.domain().asString()));
    }

    @Test
    void singleDomainSyncTaskShouldReturnNotFoundWhenDomainDoesNotExist() {
        String nonExistentDomain = "non-existent-" + UUID.randomUUID() + ".tld";

        String response = given()
            .queryParam("task", "sync")
            .post(DomainMembersAddressBookRoutes.BASE_PATH + "/" + nonExistentDomain)
        .then()
            .statusCode(404)
            .extract()
            .body().asString();

        assertThatJson(response)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                    "statusCode": 404,
                    "type": "notFound",
                    "message": "domain not found: %s"
                }""".formatted(nonExistentDomain));
    }

    @Test
    void shouldSyncAllDomainMembersToDavServerAfterTaskCompletion() {
        // Given
        LdapUser ldap1 = ldapMember("uid001", "alice@example.com", "Alice", "Nguyen", "Alice Nguyen", "111");
        LdapUser ldap2 = ldapMember("uid002", "bob@example.org", "Bob", "Tran", "Bob Tran", "222");

        Domain domain1 = Domain.of("domain1" + UUID.randomUUID() + ".tld");
        Domain domain2 = Domain.of("domain2" + UUID.randomUUID() + ".tld");
        OpenPaaSDomain openPaaSDomain1 = domainDAO.add(domain1).block();
        OpenPaaSDomain openPaaSDomain2 = domainDAO.add(domain2).block();

        when(ldapDomainMemberProvider.domainMembers(any()))
            .thenAnswer(inv -> {
                Domain input = inv.getArgument(0);
                if (input.equals(domain1)) {
                    return Flux.just(ldap1);
                } else if (input.equals(domain2)) {
                    return Flux.just(ldap2);
                } else {
                    return Flux.empty();
                }
            });

        String taskId = given()
            .queryParam("task", "sync")
            .post(DomainMembersAddressBookRoutes.BASE_PATH)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.addedCount", is(2));

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain1.id()))
            .contains("alice@example.com")
            .doesNotContain("bob@example.org");

        assertThat(listContactDomainMembersAsVcard(openPaaSDomain2.id()))
            .contains("bob@example.org")
            .doesNotContain("alice@example.com");
    }

    @Test
    void shouldSyncSingleDomainMembersToDavServerAfterTaskCompletion() {
        LdapUser ldap = ldapMember("uid123", "charlie@example.net", "Charlie", "Pham", "Charlie Pham", "333");
        Domain domain = Domain.of("domain1" + UUID.randomUUID() + ".tld");
        OpenPaaSDomain openPaaSDomain1 = domainDAO.add(domain).block();
        mockLdapDomainMembersForDomain(domain, ldap);

        String taskId = given()
            .queryParam("task", "sync")
            .post(DomainMembersAddressBookRoutes.BASE_PATH + "/" + domain.asString())
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.addedCount", is(1));

        String vcard = listContactDomainMembersAsVcard(openPaaSDomain1.id());
        assertThat(vcard)
            .contains("EMAIL:charlie@example.net");
    }

    @Test
    void routeShouldReturnCorrectAddedUpdatedDeletedCounts() {
        // Given domain and initial contact
        Domain domain = Domain.of("domain-test-" + UUID.randomUUID() + ".tld");
        OpenPaaSDomain openPaaSDomain = domainDAO.add(domain).block();

        // Initial LDAP contact -> to be added
        LdapUser ldapInitial1 = ldapMember("uid001-1", "first11@example.com", "Nguyen", "First", "First Nguyen", "111");
        LdapUser ldapInitial2 = ldapMember("uid001-2", "first12@example.com", "Nguyen", "First", "First Nguyen", "111");

        // Add initial contact
        mockLdapDomainMembersForDomain(domain, ldapInitial1, ldapInitial2);

        // First sync - should add contact
        Supplier<String> submitTask = () -> given()
            .queryParam("task", "sync")
            .post("/addressbook/domain-members/" + domain.asString())
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("taskId");

        String initialTaskId = submitTask.get();

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(initialTaskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.addedCount", is(2));

        // Modify the contact for update + add one new + remove one -> simulate updated + added + deleted
        LdapUser ldapUpdated = ldapMember("uid001-1", "first11@example.com", "Nguyen", "First", "First Updated", "999"); // Updated
        LdapUser ldapAdded = ldapMember("uid002", "second@example.org", "Le", "Second", "Second Le", "222"); // New

        mockLdapDomainMembersForDomain(domain, ldapUpdated, ldapAdded);

        // Second sync - should detect 1 updated, 1 added, 1 deleted
        String taskId = submitTask.get();

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.addedCount", is(1))
            .body("additionalInformation.updatedCount", is(1))
            .body("additionalInformation.deletedCount", is(1));
    }

    private void mockLdapDomainMembersForDomain(Domain domain, LdapUser... members) {
        when(ldapDomainMemberProvider.domainMembers(any()))
            .thenAnswer(inv -> {
                Domain input = inv.getArgument(0);
                return domain.equals(input) ? Flux.fromArray(members) : Flux.empty();
            });
    }

    private LdapUser ldapMember(String uid, String mail, String sn, String givenName, String displayName, String tel) {
        return LdapUser.builder()
            .uid(uid)
            .cn(displayName)
            .sn(sn)
            .givenName(givenName)
            .mail(Throwing.supplier(() -> new MailAddress(mail)).get())
            .telephoneNumber(tel)
            .displayName(displayName)
            .build();
    }

    private String listContactDomainMembersAsVcard(OpenPaaSId domainId) {
        return cardDavClient.listContactDomainMembers(domainId)
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

}
