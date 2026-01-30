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

package com.linagora.calendar.saas;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

class SaaSUserSubscriptionHandlerTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private SaaSUserSubscriptionHandler testee;
    private CardDavClient cardDavClient;
    private MongoDBOpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private OpenPaaSDomain testDomain;

    @BeforeEach
    void setupEach() throws Exception {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        testee = new SaaSUserSubscriptionHandler(userDAO, domainDAO, cardDavClient);
        testDomain = createNewDomainWithAddressBook();
    }

    private OpenPaaSDomain createNewDomainWithAddressBook() {
        OpenPaaSDomain newDomain = domainDAO.add(Domain.of("test-domain-" + UUID.randomUUID() + ".tld")).block();
        cardDavClient.createDomainMembersAddressBook(newDomain.id()).block();
        return newDomain;
    }

    @Test
    void shouldRegisterUserAndAddToAddressBookWhenCalendarFeatureEnabled() {
        String userEmail = "bob@" + testDomain.domain().asString();
        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNotNull();
        assertThat(user.username()).isEqualTo(Username.of(userEmail));

        String vcardContent = listContactDomainMembersAsVcard(testDomain);
        assertThat(vcardContent).contains(userEmail);
    }

    @Test
    void shouldNotRegisterUserWhenCalendarFeatureNotPresent() {
        String userEmail = "no-calendar@" + testDomain.domain().asString();
        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "mail": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNull();

        String vcardContent = listContactDomainMembersAsVcard(testDomain);
        assertThat(vcardContent).doesNotContain(userEmail);
    }

    @Test
    void shouldNotFailWhenUserAlreadyExists() {
        String userEmail = "existing@" + testDomain.domain().asString();
        userDAO.add(Username.of(userEmail)).block();

        String json = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(userEmail);

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSUser user = userDAO.retrieve(Username.of(userEmail)).block();
        assertThat(user).isNotNull();
    }

    @Test
    void shouldHandleMultipleUsers() {
        String aliceEmail = "alice@" + testDomain.domain().asString();
        String bobEmail = "bob@" + testDomain.domain().asString();

        String json1 = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": true,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(aliceEmail);

        String json2 = """
            {
                "internalEmail": "%s",
                "isPaying": true,
                "canUpgrade": false,
                "features": {
                    "calendar": {}
                }
            }
            """.formatted(bobEmail);

        testee.handleMessage(json1.getBytes(StandardCharsets.UTF_8)).block();
        testee.handleMessage(json2.getBytes(StandardCharsets.UTF_8)).block();

        assertThat(userDAO.retrieve(Username.of(aliceEmail)).block()).isNotNull();
        assertThat(userDAO.retrieve(Username.of(bobEmail)).block()).isNotNull();

        String vcardContent = listContactDomainMembersAsVcard(testDomain);
        assertThat(vcardContent).contains(aliceEmail).contains(bobEmail);
    }

    private String listContactDomainMembersAsVcard(OpenPaaSDomain domain) {
        return cardDavClient.listContactDomainMembers(domain.id())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }
}
