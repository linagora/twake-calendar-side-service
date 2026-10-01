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
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.IntStream;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;

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

        webAdminServer = WebAdminUtils.createWebAdminServer(
                new DomainAddressBookRoutes(domainDAO, cardDavClient, new JsonTransformer()))
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

    private void upsertDomainContact(String addressBookId) {
        String uid = UUID.randomUUID().toString();
        davTestHelper.upsertDomainContact(domain.id(), new AddressBookURL(domain.id(), addressBookId), uid, vcard(uid)).block();
    }

    private void upsertDomainMember() {
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContactDomainMembers(domain.id(), uid, vcard(uid).getBytes(StandardCharsets.UTF_8)).block();
    }

    private String vcard(String uid) {
        return """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:%s
            EMAIL;TYPE=Work:%s@example.com
            END:VCARD
            """.formatted(uid, uid, uid);
    }
}
