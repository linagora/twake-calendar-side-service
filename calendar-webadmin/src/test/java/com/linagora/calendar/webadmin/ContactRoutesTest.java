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
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

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

import com.fasterxml.jackson.databind.node.TextNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.ContactChangedEvent;
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
    private OpenPaaSUserDAO userDAO;
    private OpenPaaSUser user;
    private List<CommonContactOutboundEvent> publishedMessages;
    private Set<String> failingContactUids;

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        publishedMessages = new CopyOnWriteArrayList<>();
        failingContactUids = ConcurrentHashMap.newKeySet();
        CommonContactPublisher publisher = mock(CommonContactPublisher.class);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            CommonContactOutboundEvent event = invocation.getArgument(0);
            if (event instanceof ContactChangedEvent contactEvent && failingContactUids.contains(contactEvent.uid().value())) {
                return Mono.error(new RuntimeException("Simulated publication failure"));
            }
            publishedMessages.add(event);
            return Mono.empty();
        });

        CommonContactRepublishService republishService = new CommonContactRepublishService(userDAO, domainDAO, cardDavClient,
            new CommonContactEventConverter(userDAO, domainDAO), publisher);

        user = sabreDavExtension.newTestUser();

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new ContactRoutes(new JsonTransformer(), taskManager,
                    new ContactRoutes.CommonContactRepublishRequestToTask(republishService, userDAO, domainDAO)),
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
        AddressBookURL addressBookURL = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        long revision = Long.parseLong(cardDavClient.retrieveSyncToken(user.username(), addressBookURL).block().value());
        String etag = etagOf(cardDavClient.reportUserAddressBookContacts(user.username(), addressBookURL).block(), uid);

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": %d,
                  "etag": %s,
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid,
                revision, TextNode.valueOf(etag).toString(), uid, uid));
    }

    @Test
    void republishShouldPublishMultipleUserContacts() {
        String uid = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid2, "John Doe 2");

        awaitRepublishTask();

        assertThat(publishedContactEvents()).hasSize(2);

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));

        assertThatJson(publishedEvent(uid2))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe 2" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
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
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Jane Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
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

        assertThat(publishedContactEvents())
            .filteredOn(event -> event.uid().value().equals(uid))
            .singleElement()
            .satisfies(event -> assertThat(event.path().toASCIIString())
                .isEqualTo("addressbooks/%s/%s/%s.vcf".formatted(user.id().value(), COLLECTED_ADDRESS_BOOK, uid)));
    }

    @Test
    void republishShouldBracketTheContactsOfAnAddressBookWithTheStartAndTheEndOfItsSync() {
        // Given a user address book holding one contact
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");
        AddressBookURL addressBookURL = new AddressBookURL(user.id(), COLLECTED_ADDRESS_BOOK);
        long revision = Long.parseLong(cardDavClient.retrieveSyncToken(user.username(), addressBookURL).block().value());
        String addressBookPath = "addressbooks/%s/%s".formatted(user.id().value(), COLLECTED_ADDRESS_BOOK);

        // When the contacts are republished
        String taskId = republishTaskId();
        awaitTask(taskId);

        // Then the contact is sent between the start and the complete end of the sync of its address book,
        // all carrying the same sync ID and the revision of the address book
        String syncId = taskId + ":" + addressBookPath;
        assertThatJson(publishedMessagesOf(addressBookPath))
            .isEqualTo("""
                [
                  {
                    "audience": { "user": "%1$s" },
                    "action": "SYNC_START",
                    "path": "%2$s",
                    "syncId": "%3$s",
                    "revision": %4$d
                  },
                  {
                    "audience": { "user": "%1$s" },
                    "action": "ADD",
                    "path": "%2$s/%5$s.vcf",
                    "uid": "%5$s",
                    "revision": %4$d,
                    "etag": "${json-unit.any-string}",
                    "syncId": "%3$s",
                    "payload": "${json-unit.ignore}"
                  },
                  {
                    "audience": { "user": "%1$s" },
                    "action": "SYNC_END",
                    "path": "%2$s",
                    "syncId": "%3$s",
                    "revision": %4$d,
                    "complete": true,
                    "count": 1
                  }
                ]""".formatted(user.username().asString(), addressBookPath, syncId, revision, uid));
    }

    @Test
    void republishShouldCompleteTheSyncOfAnEmptyAddressBook() {
        // Given the default address book of the user, which holds no contact
        String addressBookPath = "addressbooks/%s/contacts".formatted(user.id().value());

        // When the contacts are republished
        String taskId = republishTaskId();
        awaitTask(taskId);

        // Then the sync of the address book starts and completes without any contact
        assertThatJson(publishedMessagesOf(addressBookPath))
            .isEqualTo("""
                [
                  {
                    "audience": { "user": "%1$s" },
                    "action": "SYNC_START",
                    "path": "%2$s",
                    "syncId": "%3$s:%2$s",
                    "revision": "${json-unit.any-number}"
                  },
                  {
                    "audience": { "user": "%1$s" },
                    "action": "SYNC_END",
                    "path": "%2$s",
                    "syncId": "%3$s:%2$s",
                    "revision": "${json-unit.any-number}",
                    "complete": true,
                    "count": 0
                  }
                ]""".formatted(user.username().asString(), addressBookPath, taskId));
    }

    @Test
    void republishShouldEndTheSyncAsIncompleteWhenAContactFailsToBePublished() {
        // Given an address book holding two contacts, one of which fails to be published
        String failingUid = UUID.randomUUID().toString();
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, failingUid, "Failing contact");
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");
        failingContactUids.add(failingUid);
        String addressBookPath = "addressbooks/%s/%s".formatted(user.id().value(), COLLECTED_ADDRESS_BOOK);

        // When the contacts of the user are republished
        String taskResponse = awaitRepublishTask(userPath(user), Map.of(), "failed");

        // Then the other contact is still sent, the end of the sync tells it is incomplete,
        // and the task reports the failed contact
        assertThat(publishedContactEvents())
            .extracting(event -> event.uid().value())
            .containsExactly(uid);
        assertThatJson(publishedMessagesOf(addressBookPath))
            .inPath("[2]")
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "SYNC_END",
                  "path": "%s",
                  "syncId": "${json-unit.any-string}",
                  "revision": "${json-unit.any-number}",
                  "complete": false,
                  "count": 1
                }""".formatted(user.username().asString(), addressBookPath));
        assertThatJson(taskResponse)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .inPath("additionalInformation")
            .isEqualTo("""
                {
                    "processedContactCount": 1,
                    "failedContactCount": 1
                }""");
    }

    @Test
    void republishShouldPublishDomainMembersContacts() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();
        AddressBookURL addressBookURL = new AddressBookURL(domain.id(), DOMAIN_MEMBERS_ADDRESS_BOOK);
        long revision = Long.parseLong(cardDavClient.retrieveDomainAddressBookSyncToken(domain.id(), addressBookURL).block().value());
        String etag = etagOf(cardDavClient.reportDomainAddressBookContacts(domain.id(), addressBookURL).block(), uid);

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": %d,
                  "etag": %s,
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Member" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_MEMBERS_ADDRESS_BOOK, uid, uid,
                revision, TextNode.valueOf(etag).toString(), uid, uid));
    }

    @Test
    void republishShouldPublishDomainAddressBookContacts() {
        OpenPaaSDomain domain = domainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
        davTestHelper.createDomainAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        AddressBookURL addressBookURL = new AddressBookURL(domain.id(), DOMAIN_ADDRESS_BOOK);
        davTestHelper.upsertDomainContact(domain.id(), addressBookURL, uid, vcard(uid, "Domain Contact")).block();
        long revision = Long.parseLong(cardDavClient.retrieveDomainAddressBookSyncToken(domain.id(), addressBookURL).block().value());
        String etag = etagOf(cardDavClient.reportDomainAddressBookContacts(domain.id(), addressBookURL).block(), uid);

        awaitRepublishTask();

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": %d,
                  "etag": %s,
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Contact" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_ADDRESS_BOOK, uid, uid,
                revision, TextNode.valueOf(etag).toString(), uid, uid));
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
    void republishForAUserShouldPublishContactsOfThatUser() {
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");

        awaitRepublishTask(userPath(user), Map.of());

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishForAUserShouldNotPublishContactsOfOtherUsers() {
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        String otherUid = UUID.randomUUID().toString();
        upsertUserContact(otherUser, COLLECTED_ADDRESS_BOOK, otherUid, "Other user contact");

        awaitRepublishTask(userPath(user), Map.of());

        assertThat(publishedContactEvents())
            .noneSatisfy(event -> assertThat(event.uid().value()).isEqualTo(otherUid));
    }

    @Test
    void republishForAUserShouldReportTheUserInAdditionalInformation() {
        String taskResponse = awaitRepublishTask(userPath(user), Map.of());

        assertThatJson(taskResponse)
            .inPath("additionalInformation")
            .isEqualTo("""
                {
                    "type": "republish-common-contacts",
                    "timestamp": "${json-unit.any-string}",
                    "username": "%s",
                    "processedContactCount": 0,
                    "failedContactCount": 0,
                    "failedAddressBookCount": 0,
                    "failedUserCount": 0,
                    "failedDomainCount": 0,
                    "contactsPerSecond": 100
                }""".formatted(user.username().asString()));
    }

    @Test
    void republishForAUserShouldReturnNotFoundWhenUnknownUser() {
        given()
            .queryParam("action", "republish")
            .post("/users/unknown@unknown.tld/contacts")
        .then()
            .statusCode(404);
    }

    @Test
    void republishForADomainShouldPublishContactsOfUsersOfThatDomain() {
        OpenPaaSDomain domain = newDomain();
        OpenPaaSUser domainUser = newUserInDomain(domain);
        String uid = UUID.randomUUID().toString();
        upsertUserContact(domainUser, COLLECTED_ADDRESS_BOOK, uid, "Domain user contact");

        awaitRepublishTask(domainPath(domain), Map.of());

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain user contact" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domainUser.username().asString(), domainUser.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishForADomainShouldPublishContactsOfTheDomainAddressBooks() {
        OpenPaaSDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();

        awaitRepublishTask(domainPath(domain), Map.of());

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Member" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_MEMBERS_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishForADomainShouldNotPublishContactsOfOtherDomains() {
        OpenPaaSDomain domain = newDomain();
        OpenPaaSDomain otherDomain = newDomain();
        OpenPaaSUser otherDomainUser = newUserInDomain(otherDomain);
        String otherUid = UUID.randomUUID().toString();
        upsertUserContact(otherDomainUser, COLLECTED_ADDRESS_BOOK, otherUid, "Other domain contact");

        awaitRepublishTask(domainPath(domain), Map.of());

        assertThat(publishedContactEvents())
            .noneSatisfy(event -> assertThat(event.uid().value()).isEqualTo(otherUid));
    }

    @Test
    void republishForADomainWithDomainScopeShouldPublishContactsOfTheDomainAddressBooks() {
        OpenPaaSDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();

        awaitRepublishTask(domainPath(domain), Map.of("scope", "domain"));

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Member" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_MEMBERS_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishForADomainWithDomainScopeShouldNotPublishContactsOfTheUsersOfThatDomain() {
        OpenPaaSDomain domain = newDomain();
        OpenPaaSUser domainUser = newUserInDomain(domain);
        String uid = UUID.randomUUID().toString();
        upsertUserContact(domainUser, COLLECTED_ADDRESS_BOOK, uid, "Domain user contact");

        awaitRepublishTask(domainPath(domain), Map.of("scope", "domain"));

        assertThat(publishedContactEvents())
            .noneSatisfy(event -> assertThat(event.uid().value()).isEqualTo(uid));
    }

    @Test
    void republishForADomainWithUserScopeShouldPublishContactsOfTheUsersOfThatDomain() {
        OpenPaaSDomain domain = newDomain();
        OpenPaaSUser domainUser = newUserInDomain(domain);
        String uid = UUID.randomUUID().toString();
        upsertUserContact(domainUser, COLLECTED_ADDRESS_BOOK, uid, "Domain user contact");

        awaitRepublishTask(domainPath(domain), Map.of("scope", "user"));

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain user contact" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domainUser.username().asString(), domainUser.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishForADomainWithUserScopeShouldNotPublishContactsOfTheDomainAddressBooks() {
        OpenPaaSDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();

        awaitRepublishTask(domainPath(domain), Map.of("scope", "user"));

        assertThat(publishedContactEvents())
            .noneSatisfy(event -> assertThat(event.uid().value()).isEqualTo(uid));
    }

    @Test
    void republishForADomainWithUserScopeShouldReportTheScopeInAdditionalInformation() {
        OpenPaaSDomain domain = newDomain();

        String taskResponse = awaitRepublishTask(domainPath(domain), Map.of("scope", "user"));

        assertThatJson(taskResponse)
            .inPath("additionalInformation")
            .isEqualTo("""
                {
                    "type": "republish-common-contacts",
                    "timestamp": "${json-unit.any-string}",
                    "domain": "%s",
                    "scope": "user",
                    "processedContactCount": 0,
                    "failedContactCount": 0,
                    "failedAddressBookCount": 0,
                    "failedUserCount": 0,
                    "failedDomainCount": 0,
                    "contactsPerSecond": 100
                }""".formatted(domain.domain().asString()));
    }

    @Test
    void republishForADomainShouldReportTheDomainInAdditionalInformation() {
        OpenPaaSDomain domain = newDomain();

        String taskResponse = awaitRepublishTask(domainPath(domain), Map.of("scope", "domain"));

        assertThatJson(taskResponse)
            .inPath("additionalInformation")
            .isEqualTo("""
                {
                    "type": "republish-common-contacts",
                    "timestamp": "${json-unit.any-string}",
                    "domain": "%s",
                    "scope": "domain",
                    "processedContactCount": 0,
                    "failedContactCount": 0,
                    "failedAddressBookCount": 0,
                    "failedUserCount": 0,
                    "failedDomainCount": 0,
                    "contactsPerSecond": 100
                }""".formatted(domain.domain().asString()));
    }

    @Test
    void republishForADomainShouldReturnNotFoundWhenUnknownDomain() {
        given()
            .queryParam("action", "republish")
            .post("/domains/unknown.tld/contacts")
        .then()
            .statusCode(404);
    }

    @Test
    void republishForADomainShouldRejectUnsupportedScope() {
        OpenPaaSDomain domain = newDomain();

        given()
            .queryParam("action", "republish")
            .queryParam("scope", "unsupported")
            .post(domainPath(domain))
        .then()
            .statusCode(400);
    }

    @Test
    void republishWithUserScopeShouldPublishUserContacts() {
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");

        awaitRepublishTask(ContactRoutes.BASE_PATH, Map.of("scope", "user"));

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "user": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "John Doe" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(user.username().asString(), user.id().value(), COLLECTED_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishWithUserScopeShouldNotPublishDomainAddressBookContacts() {
        OpenPaaSDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();

        awaitRepublishTask(ContactRoutes.BASE_PATH, Map.of("scope", "user"));

        assertThat(publishedContactEvents())
            .noneSatisfy(event -> assertThat(event.uid().value()).isEqualTo(uid));
    }

    @Test
    void republishWithDomainScopeShouldPublishDomainAddressBookContacts() {
        OpenPaaSDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid, "Domain Member").getBytes(StandardCharsets.UTF_8)).block();

        awaitRepublishTask(ContactRoutes.BASE_PATH, Map.of("scope", "domain"));

        assertThatJson(publishedEvent(uid))
            .isEqualTo("""
                {
                  "audience": { "domain": "%s" },
                  "action": "ADD",
                  "path": "addressbooks/%s/%s/%s.vcf",
                  "uid": "%s",
                  "revision": "${json-unit.ignore}",
                  "etag": "${json-unit.ignore}",
                  "syncId": "${json-unit.any-string}",
                  "payload": {
                    "@type": "Card",
                    "version": "2.0",
                    "prodId": "${json-unit.ignore}",
                    "uid": "%s",
                    "name": { "@type": "Name", "full": "Domain Member" },
                    "emails": {
                      "EMAIL-1": { "@type": "EmailAddress", "address": "%s@example.com", "contexts": { "work": true } }
                    },
                    "vCardProps": [["version", {}, "text", "3.0"]]
                  }
                }""".formatted(domain.domain().asString(), domain.id().value(), DOMAIN_MEMBERS_ADDRESS_BOOK, uid, uid, uid, uid));
    }

    @Test
    void republishWithDomainScopeShouldNotPublishUserContacts() {
        String uid = UUID.randomUUID().toString();
        upsertUserContact(COLLECTED_ADDRESS_BOOK, uid, "John Doe");

        awaitRepublishTask(ContactRoutes.BASE_PATH, Map.of("scope", "domain"));

        assertThat(publishedContactEvents())
            .noneSatisfy(event -> assertThat(event.uid().value()).isEqualTo(uid));
    }

    @Test
    void republishWithScopeShouldReportItInAdditionalInformation() {
        String taskResponse = awaitRepublishTask(ContactRoutes.BASE_PATH, Map.of("scope", "user"));

        assertThatJson(taskResponse)
            .inPath("additionalInformation.scope")
            .isStringEqualTo("user");
    }

    @Test
    void republishShouldRejectUnsupportedScope() {
        given()
            .queryParam("action", "republish")
            .queryParam("scope", "unsupported")
            .post(ContactRoutes.BASE_PATH)
        .then()
            .statusCode(400);
    }

    @Test
    void republishForAUserShouldRejectScopeParameter() {
        given()
            .queryParam("action", "republish")
            .queryParam("scope", "user")
            .post(userPath(user))
        .then()
            .statusCode(400);
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

    @Test
    void republishShouldRejectNonPositiveContactsPerSecondWithReadableMessage() {
        // Given a republish request with a negative contactsPerSecond
        // When the request is posted
        // Then it is rejected with the actual value substituted in the message
        given()
            .queryParam("action", "republish")
            .queryParam("contactsPerSecond", "-5")
            .post(ContactRoutes.BASE_PATH)
        .then()
            .statusCode(400)
            .body("details", is("Query parameter 'contactsPerSecond' must be strictly positive, got: -5"));
    }

    private void upsertUserContact(String addressBookId, String uid, String fullName) {
        upsertUserContact(user, addressBookId, uid, fullName);
    }

    private void upsertUserContact(OpenPaaSUser contactOwner, String addressBookId, String uid, String fullName) {
        cardDavClient.upsertContact(contactOwner.username(), new AddressBookURL(contactOwner.id(), addressBookId), uid,
            vcard(uid, fullName).getBytes(StandardCharsets.UTF_8)).block();
    }

    private String userPath(OpenPaaSUser openPaaSUser) {
        return "/users/" + openPaaSUser.username().asString() + "/contacts";
    }

    private String domainPath(OpenPaaSDomain openPaaSDomain) {
        return "/domains/" + openPaaSDomain.domain().asString() + "/contacts";
    }

    private OpenPaaSDomain newDomain() {
        return domainDAO.add(Domain.of("new-domain" + UUID.randomUUID() + ".tld")).block();
    }

    private OpenPaaSUser newUserInDomain(OpenPaaSDomain domain) {
        return sabreDavExtension.dockerSabreDavSetup().getOpenPaaSProvisioningService()
            .createUser(Username.fromLocalPartWithDomain("user_" + UUID.randomUUID(), domain.domain()))
            .block();
    }

    private String awaitRepublishTask(String path, Map<String, String> queryParams) {
        return awaitRepublishTask(path, queryParams, "completed");
    }

    private String awaitRepublishTask(String path, Map<String, String> queryParams, String expectedStatus) {
        String taskId = given()
            .queryParam("action", "republish")
            .queryParams(queryParams)
            .post(path)
        .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .get("taskId");
        return awaitTask(taskId, expectedStatus);
    }

    private String publishedEvent(String uid) {
        List<ContactChangedEvent> events = publishedContactEvents().stream()
            .filter(event -> event.uid().value().equals(uid))
            .toList();

        assertThat(events).as("published events of contact %s", uid).hasSize(1);

        return Throwing.supplier(() -> new String(events.getFirst().serialize(), StandardCharsets.UTF_8)).get();
    }

    private String etagOf(AddressBookReportXmlResponse response, String uid) {
        return response.extractContactObjects().stream()
            .filter(contact -> contact.href().getPath().endsWith("/" + uid + ".vcf"))
            .findFirst()
            .orElseThrow()
            .etag();
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
        return awaitTask(taskId, "completed");
    }

    private String awaitTask(String taskId, String expectedStatus) {
        return given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is(expectedStatus))
            .extract()
            .body()
            .asString();
    }

    private List<ContactChangedEvent> publishedContactEvents() {
        return publishedMessages.stream()
            .filter(ContactChangedEvent.class::isInstance)
            .map(ContactChangedEvent.class::cast)
            .toList();
    }

    /**
     * The messages published for an address book and its contacts, in their order of publication.
     */
    private String publishedMessagesOf(String addressBookPath) {
        return publishedMessages.stream()
            .filter(message -> message.path().toASCIIString().equals(addressBookPath)
                || message.path().toASCIIString().startsWith(addressBookPath + "/"))
            .map(message -> Throwing.supplier(() -> new String(message.serialize(), StandardCharsets.UTF_8)).get())
            .collect(Collectors.joining(",", "[", "]"));
    }
}
