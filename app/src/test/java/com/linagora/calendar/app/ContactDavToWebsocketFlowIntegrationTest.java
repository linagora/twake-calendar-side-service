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

package com.linagora.calendar.app;

import static com.linagora.calendar.app.TestFixture.awaitMessage;
import static com.linagora.calendar.app.TestFixture.connectWebSocket;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.HttpStatus;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.redis.DockerRedisExtension;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import okhttp3.WebSocket;

class ContactDavToWebsocketFlowIntegrationTest {

    private static final String PASSWORD = "secret";
    private static final String SABRE_SYNC_TOKEN_PREFIX = "http://sabre.io/ns/sync/";

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.perClass();

    @Order(2)
    @RegisterExtension
    static DockerRedisExtension dockerExtension = new DockerRedisExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB)
            .enableRedis(),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        new AbstractModule() {
            @Provides
            @Singleton
            public RedisConfiguration redisConfiguration() {
                return StandaloneRedisConfiguration.from(dockerExtension.redisURI().toString());
            }
        });

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private CardDavClient cardDavClient;
    private DavTestHelper davTestHelper;
    private OpenPaaSUser bob;
    private int restApiPort;
    private WebSocket webSocket;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        bob = sabreDavExtension.newTestUser(Optional.of("bob"));

        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(bob.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(bob.username(), PASSWORD);

        restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterEach
    void tearDown() {
        if (webSocket != null) {
            webSocket.close(1000, "test finished");
        }
    }

    @Test
    void bobShouldReceiveWebsocketPushWhenContactIsCreated() {
        // GIVEN: Bob opens a WebSocket and registers his collected address book
        AddressBookURL addressBookURL = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBookURL.asUri().toString();
        String vcardUid = UUID.randomUUID().toString();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        registerAddressBook(messages, addressBookUri);

        // WHEN: Bob creates a contact
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "John Doe")).block();

        // THEN: Bob receives the current address book sync token over WebSocket
        SyncToken createdToken = cardDavClient.retrieveSyncToken(bob.username(), addressBookURL).block();
        assertAddressBookSyncToken(messages, addressBookUri, createdToken);
    }

    @Test
    void bobShouldReceiveWebsocketPushWhenContactIsUpdated() {
        // GIVEN: Bob registers his address book and has received the notification for an existing contact
        AddressBookURL addressBookURL = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBookURL.asUri().toString();
        String vcardUid = UUID.randomUUID().toString();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        registerAddressBook(messages, addressBookUri);
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "John Doe")).block();
        SyncToken initialToken = cardDavClient.retrieveSyncToken(bob.username(), addressBookURL).block();
        assertAddressBookSyncToken(messages, addressBookUri, initialToken);

        // WHEN: Bob updates the contact
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "Jane Doe")).block();

        // THEN: Bob receives a new address book sync token over WebSocket
        SyncToken updatedToken = cardDavClient.retrieveSyncToken(bob.username(), addressBookURL).block();
        assertThat(updatedToken).isNotEqualTo(initialToken);
        assertAddressBookSyncToken(messages, addressBookUri, updatedToken);
    }

    @Test
    void bobShouldReceiveWebsocketPushWhenContactIsDeleted() {
        // GIVEN: Bob registers his address book and has received the notification for an existing contact
        AddressBookURL addressBookURL = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBookURL.asUri().toString();
        String vcardUid = UUID.randomUUID().toString();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        registerAddressBook(messages, addressBookUri);
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "John Doe")).block();
        SyncToken initialToken = cardDavClient.retrieveSyncToken(bob.username(), addressBookURL).block();
        assertAddressBookSyncToken(messages, addressBookUri, initialToken);

        // WHEN: Bob deletes the contact
        cardDavClient.deleteContact(bob.username(), addressBookURL, new ContactUid(vcardUid)).block();

        // THEN: Bob receives another new address book sync token over WebSocket
        SyncToken deletedToken = cardDavClient.retrieveSyncToken(bob.username(), addressBookURL).block();
        assertThat(deletedToken).isNotEqualTo(initialToken);
        assertAddressBookSyncToken(messages, addressBookUri, deletedToken);
    }

    @Test
    void contactCreateReportShouldMatchWebsocketSyncToken() {
        // GIVEN: Bob registers his address book and remembers its current sync token
        AddressBookURL addressBookURL = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBookURL.asUri().toString();
        String vcardUid = UUID.randomUUID().toString();
        SyncToken initialToken = cardDavClient.retrieveSyncToken(bob.username(), addressBookURL).block();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        registerAddressBook(messages, addressBookUri);

        // WHEN: Bob creates a contact and receives a new sync token over WebSocket
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "John Doe")).block();
        SyncToken createdToken = awaitAddressBookSyncToken(messages, addressBookUri);
        String reportResponse = davTestHelper.fetchContactsBySyncToken(bob, addressBookURL, SABRE_SYNC_TOKEN_PREFIX + initialToken.value()).block();

        // THEN: Reporting from the previous token returns the created contact and the WebSocket token
        assertThat(reportResponse)
            .contains(addressBookUri + "/" + vcardUid + ".vcf", SABRE_SYNC_TOKEN_PREFIX + createdToken.value());
    }

    @Test
    void contactUpdateReportShouldMatchWebsocketSyncToken() {
        // GIVEN: Bob registers his address book and has received the token for an existing contact
        AddressBookURL addressBookURL = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBookURL.asUri().toString();
        String vcardUid = UUID.randomUUID().toString();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        registerAddressBook(messages, addressBookUri);
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "John Doe")).block();
        SyncToken initialToken = awaitAddressBookSyncToken(messages, addressBookUri);

        // WHEN: Bob updates the contact and reports changes from the previous token
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "Jane Doe")).block();
        SyncToken updatedToken = awaitAddressBookSyncToken(messages, addressBookUri);
        String reportResponse = davTestHelper.fetchContactsBySyncToken(
            bob, addressBookURL, SABRE_SYNC_TOKEN_PREFIX + initialToken.value()).block();

        // THEN: The report returns the updated contact and the WebSocket token
        assertThat(reportResponse)
            .contains(addressBookUri + "/" + vcardUid + ".vcf", SABRE_SYNC_TOKEN_PREFIX + updatedToken.value());
    }

    @Test
    void contactDeleteReportShouldMatchWebsocketSyncToken() {
        // GIVEN: Bob registers his address book and has received the token for an existing contact
        AddressBookURL addressBookURL = new AddressBookURL(bob.id(), "collected");
        String addressBookUri = addressBookURL.asUri().toString();
        String vcardUid = UUID.randomUUID().toString();
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        registerAddressBook(messages, addressBookUri);
        cardDavClient.createContact(bob.username(), addressBookURL, new ContactUid(vcardUid), buildVCard(vcardUid, "John Doe")).block();
        SyncToken initialToken = awaitAddressBookSyncToken(messages, addressBookUri);

        // WHEN: Bob deletes the contact and reports changes from the previous token
        cardDavClient.deleteContact(bob.username(), addressBookURL, new ContactUid(vcardUid)).block();
        SyncToken deletedToken = awaitAddressBookSyncToken(messages, addressBookUri);
        String reportResponse = davTestHelper.fetchContactsBySyncToken(
            bob, addressBookURL, SABRE_SYNC_TOKEN_PREFIX + initialToken.value()).block();

        // THEN: The report returns the deleted contact and the WebSocket token
        assertThat(reportResponse)
            .contains(addressBookUri + "/" + vcardUid + ".vcf", SABRE_SYNC_TOKEN_PREFIX + deletedToken.value());
    }

    private void registerAddressBook(BlockingQueue<String> messages, String addressBookUri) {
        webSocket = connectWebSocket(restApiPort, generateTicket(bob), messages);
        webSocket.send("""
            {
                "register": ["{addressBookUri}"]
            }
            """.replace("{addressBookUri}", addressBookUri));
        awaitMessage(messages, message -> message.contains("\"registered\"") && message.contains(addressBookUri));
    }

    private String generateTicket(OpenPaaSUser user) {
        String ticketResponse = given()
            .auth().preemptive().basic(user.username().asString(), PASSWORD)
        .when()
            .post("http://localhost:" + restApiPort + "/ws/ticket")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .asString();
        return JsonPath.from(ticketResponse).getString("value");
    }

    private void assertAddressBookSyncToken(BlockingQueue<String> messages, String addressBookUri, SyncToken syncToken) {
        String pushMessage = awaitMessage(messages, message -> message.contains(addressBookUri)
            && message.contains(syncToken.value()));

        assertThatJson(pushMessage)
            .isEqualTo("""
                {
                  "{addressBookUri}": {
                    "syncToken": "{syncToken}"
                  }
                }
                """
                .replace("{addressBookUri}", addressBookUri)
                .replace("{syncToken}", syncToken.value()));
    }

    private SyncToken awaitAddressBookSyncToken(BlockingQueue<String> messages, String addressBookUri) {
        String pushMessage = awaitMessage(messages, message -> message.contains(addressBookUri)
            && message.contains("\"syncToken\""));

        assertThatJson(pushMessage)
            .isEqualTo("""
                {
                  "{addressBookUri}": {
                    "syncToken": "${json-unit.ignore}"
                  }
                }
                """.replace("{addressBookUri}", addressBookUri));

        Map<String, Object> root = JsonPath.from(pushMessage).get();
        Map<String, Object> addressBookChange = (Map<String, Object>) root.get(addressBookUri);
        return new SyncToken((String) addressBookChange.get("syncToken"));
    }

    private byte[] buildVCard(String vcardUid, String fullName) {
        return """
            BEGIN:VCARD
            VERSION:4.0
            UID:{vcardUid}
            FN:{fullName}
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """
            .replace("{vcardUid}", vcardUid)
            .replace("{fullName}", fullName)
            .getBytes(StandardCharsets.UTF_8);
    }
}
