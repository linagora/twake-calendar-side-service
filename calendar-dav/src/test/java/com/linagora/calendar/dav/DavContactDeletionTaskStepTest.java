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

package com.linagora.calendar.dav;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class DavContactDeletionTaskStepTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private DavContactDeletionTaskStep testee;
    private CardDavClient cardDavClient;
    private OpenPaaSUser openPaaSUser;
    private OpenPaaSUser openPaaSUser2;

    @BeforeEach
    void setUp() throws SSLException {
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(),TECHNICAL_TOKEN_SERVICE_TESTING);
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        testee = new DavContactDeletionTaskStep(cardDavClient, new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO));

        this.openPaaSUser = sabreDavExtension.newTestUser();
        this.openPaaSUser2 = sabreDavExtension.newTestUser();
    }

    @Test
    void deleteUserDataShouldDeleteNonSystemAddressBooks() {
        String addressBookId = "testbook";
        String name = "Test Address Book";
        cardDavClient.createUserAddressBook(openPaaSUser.username(), openPaaSUser.id(), addressBookId, name).block();
        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(cardDavClient.listUserAddressBookIds(openPaaSUser.username(), openPaaSUser.id()).collectList().block())
            .extracting(CardDavClient.AddressBook::value)
            .doesNotContain(addressBookId);
    }

    @Test
    void deleteUserDataShouldDeleteContactsInSystemAddressBooks() {
        String addressBookId = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:John Doe
            EMAIL:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);
        cardDavClient.createContact(openPaaSUser.username(), openPaaSUser.id(), addressBookId, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();
        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(cardDavClient.exportContact(openPaaSUser.username(), openPaaSUser.id(), addressBookId).block())
            .isNull();
    }

    @Test
    void deleteUserDataShouldNotDeleteOtherUsersAddressBook() {
        String addressBookId = "testbook";
        String name = "Test Address Book";
        cardDavClient.createUserAddressBook(openPaaSUser.username(), openPaaSUser.id(), addressBookId, name).block();
        testee.deleteUserData(openPaaSUser2.username()).block();
        assertThat(cardDavClient.listUserAddressBookIds(openPaaSUser.username(), openPaaSUser.id()).collectList().block())
            .extracting(CardDavClient.AddressBook::value)
            .contains(addressBookId);
    }

    @Test
    void deleteUserDataShouldNotDeleteOtherUsersContacts() {
        String addressBookId = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:%s
            FN:John Doe
            EMAIL:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);
        cardDavClient.createContact(openPaaSUser.username(), openPaaSUser.id(), addressBookId, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();
        testee.deleteUserData(openPaaSUser2.username()).block();
        String actual = new String(cardDavClient.exportContact(openPaaSUser.username(), openPaaSUser.id(), addressBookId).block(), StandardCharsets.UTF_8);

        assertThat(actual).contains("UID:" + vcardUid);
    }
}
