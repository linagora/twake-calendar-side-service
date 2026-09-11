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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent;
import com.linagora.calendar.saas.contact.CommonContactPublisher;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.CommonContactRepublishService;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTaskAdditionalInformationDTO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.RestAssured;
import net.javacrumbs.jsonunit.core.Option;
import reactor.core.publisher.Mono;

public class ContactRoutesTest {

    private static final String COLLECTED_ADDRESS_BOOK = "collected";
    private static final String DOMAIN_MEMBERS_ADDRESS_BOOK = "domain-members";
    private static final String DOMAIN_ADDRESS_BOOK = "dab";

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private WebAdminServer webAdminServer;
    private CardDavClient cardDavClient;
    private DavTestHelper davTestHelper;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private OpenPaaSUser user;
    private List<CommonContactOutboundEvent> publishedEvents;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        publishedEvents = new CopyOnWriteArrayList<>();
        CommonContactPublisher publisher = mock(CommonContactPublisher.class);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            publishedEvents.add(invocation.getArgument(0));
            return Mono.empty();
        });

        CommonContactRepublishService republishService = new CommonContactRepublishService(userDAO, domainDAO, cardDavClient,
            new CommonContactEventConverter(userDAO, domainDAO), publisher);

        user = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new ContactRoutes(new JsonTransformer(), taskManager,
                    new ContactRoutes.CommonContactRepublishRequestToTask(republishService)),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(CommonContactRepublishTaskAdditionalInformationDTO.module())
                        .build())))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer).build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void republishShouldPublishUserContacts() {
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "4.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishShouldPublishMultipleUserContacts() {
        String uid = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid2, "John Doe 2");

        awaitRepublishTask();

        assertThat(publishedEvents).hasSize(2);

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "4.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));

        assertThatJson(publishedEvent(uid2))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe 2" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "4.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid2, uid2, uid2, uid2));
    }

    @Test
    void republishShouldPublishContactsOfNewlyCreatedAddressBook() {
        String addressBookId = "testbook";
        cardDavClient.createUserAddressBook(user.username(), user.id(), addressBookId, "Test Address Book").block();
        String uid = UUID.randomUUID().toString();
        upsertUserContact(addressBookId, uid, "Jane Doe");

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Jane Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "4.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), addressBookId, uid, uid, uid, uid));
    }

    @Test
    void republishShouldPublishContactOfADelegatedAddressBookOnlyOnce() {
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        String uid = UUID.randomUUID().toString();
        cardDavClient.updateAddressBookShares(user.username(), new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK),
            List.of(new CardDavClient.AddressBookSharee("mailto:" + delegate.username().asString(), 3))).block();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "Shared contact");

        awaitRepublishTask();

        assertThat(publishedEvents)
            .filteredOn(event -> event.uid().value().equals(uid))
            .singleElement()
            .satisfies(event -> assertThat(event.path().toASCIIString())
                .isEqualTo("addressbooks/%s/%s/%s.vcf".formatted(user.id().value(), COLLECTED_ADDRESS_BOOK, uid)));
    }

    @Test
    void republishShouldPublishDomainMembersContacts() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Member" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "4.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_MEMBERS_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishShouldPublishDomainAddressBookContacts() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        davTestHelper.createDomainAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        davTestHelper.upsertDomainContact(domain.id(), new AddressBookURL(domain.id(), DOMAIN_ADDRESS_BOOK), uid,
            vcard(uid, "Domain Contact")).block();

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Contact" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "4.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishTaskShouldReportAdditionalInformation() {
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");

        String taskId = republishTaskId();
        String taskResponse = awaitTask(taskId);

        assertThatJson(taskResponse)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                    "additionalInformation": {
                        "type": "republish-common-contacts",
                        "timestamp": "${json-unit.any-string}",
                        "processedContactCount": 1,
                        "failedContactCount": 0,
                        "failedAddressBookCount": 0,
                        "failedUserCount": 0,
                        "failedDomainCount": 0,
                        "contactsPerSecond": 100
                    },
                    "type": "republish-common-contacts",
                    "taskId": "%s",
                    "status": "completed"
                }""".formatted(taskId));
    }

    @Test
    void republishShouldRejectUnknownAction() {
        given()
            .queryParam("action", "unknown")
            .post(ContactRoutes.BASE_PATH)
        .then()
            .statusCode(400);
    }

    @Test
    void republishShouldRejectInvalidContactsPerSecond() {
        given()
            .queryParam("action", "republish")
            .queryParam("contactsPerSecond", "invalid")
            .post(ContactRoutes.BASE_PATH)
        .then()
            .statusCode(400);
    }

    private void upsertUserContact(String addressBookId, String uid, String fullName) {
        cardDavClient.upsertContact(user.username(), new AddressBookURL(user.id(), addressBookId), uid,
            vcard(uid, fullName).getBytes(StandardCharsets.UTF_8)).block();
    }

    private String publishedEvent(String uid) {
        return publishedEvents.stream()
            .filter(event -> event.uid().value().equals(uid))
            .findFirst()
            .map(Throwing.function(event -> new String(event.serialize(), StandardCharsets.UTF_8)))
            .orElseThrow();
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

    private void awaitRepublishTask() {
        awaitTask(republishTaskId());
    }

    private String republishTaskId() {
        return given()
            .queryParam("action", "republish")
            .post(ContactRoutes.BASE_PATH)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");
    }

    private String awaitTask(String taskId) {
        return given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .extract()
            .body()
            .asString();
    }
}
