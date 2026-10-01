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

    private static final String DOMAIN_MEMBERS_ADDRESS_BOOK = "domain-members";
    private static final String DOMAIN_ADDRESS_BOOK = "dab";
    private static final String CONTACT_COUNT_PATH = "/domains/{domain}/addressbooks/{addressBookId}/contactCount";

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

        webAdminServer = WebAdminUtils.createWebAdminServer(new DomainAddressBookRoutes(domainDAO, cardDavClient))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void contactCountShouldReturnZeroWhenDomainMembersAddressBookIsEmpty() {
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();

        given()
        .when()
            .get(CONTACT_COUNT_PATH, domain.domain().asString(), DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(0));
    }

    @Test
    void contactCountShouldReturnDomainMembersContactCount() {
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();
        IntStream.range(0, 3).forEach(i -> {
            String vcardUid = UUID.randomUUID().toString();
            cardDavClient.upsertContactDomainMembers(domain.id(), vcardUid,
                vcard(vcardUid, "John Doe " + i).getBytes(StandardCharsets.UTF_8)).block();
        });

        given()
        .when()
            .get(CONTACT_COUNT_PATH, domain.domain().asString(), DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(3));
    }

    @Test
    void contactCountShouldReturnDomainAddressBookContactCount() {
        davTestHelper.createDomainAddressBook(domain.id()).block();
        AddressBookURL addressBookURL = new AddressBookURL(domain.id(), DOMAIN_ADDRESS_BOOK);
        IntStream.range(0, 2).forEach(i -> {
            String vcardUid = UUID.randomUUID().toString();
            davTestHelper.upsertDomainContact(domain.id(), addressBookURL, vcardUid, vcard(vcardUid, "John Doe " + i)).block();
        });

        given()
        .when()
            .get(CONTACT_COUNT_PATH, domain.domain().asString(), DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(200)
            .body("count", is(2));
    }

    @Test
    void contactCountShouldReturn404WhenAddressBookDoesNotExist() {
        cardDavClient.createDomainMembersAddressBook(domain.id()).block();

        given()
        .when()
            .get(CONTACT_COUNT_PATH, domain.domain().asString(), UUID.randomUUID().toString())
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Address book does not exist"));
    }

    @Test
    void contactCountShouldReturn404WhenDomainDoesNotExist() {
        given()
        .when()
            .get(CONTACT_COUNT_PATH, "ghost.tld", DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(404)
            .body("type", is("notFound"))
            .body("message", is("Domain does not exist"));
    }

    private String vcard(String vcardUid, String fullName) {
        return """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:%s
            END:VCARD
            """.formatted(vcardUid, fullName);
    }
}
