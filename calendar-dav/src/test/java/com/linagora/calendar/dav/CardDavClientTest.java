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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.OpenPaaSUser;

public class CardDavClientTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private CardDavClient testee;

    @BeforeEach
    void setupEach() throws Exception {
        testee = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
    }

    private OpenPaaSUser openPaaSUser() {
        return sabreDavExtension.newTestUser();
    }

    @Test
    void createContactShouldSucceed() {
        OpenPaaSUser user = openPaaSUser();
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();

        String actual = new String(testee.exportContact(user.username(), user.id(), addressBook).block(), StandardCharsets.UTF_8);

        assertThat(actual).contains("EMAIL;TYPE=Work:john.doe@example.com");
    }

    @Test
    void createContactShouldThrowWhenInvalidAddressBook() {
        OpenPaaSUser user = openPaaSUser();
        String addressBook = "invalid";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        assertThatThrownBy(() ->
            testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block()
        ).isInstanceOf(DavClientException.class);
    }

    @Test
    void exportContactShouldReturnMultipleContacts() {
        OpenPaaSUser user = openPaaSUser();
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcardUid2 = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);
        String vcard2 = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe 2
            EMAIL;TYPE=Work:john.doe2@example.com
            END:VCARD
            """.formatted(vcardUid2);

        testee.createContact(user.username(), user.id(), addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8)).block();
        testee.createContact(user.username(), user.id(), addressBook, vcardUid2, vcard2.getBytes(StandardCharsets.UTF_8)).block();

        String actual = new String(testee.exportContact(user.username(), user.id(), addressBook).block(), StandardCharsets.UTF_8);

        assertThat(actual).contains("EMAIL;TYPE=Work:john.doe@example.com");
        assertThat(actual).contains("EMAIL;TYPE=Work:john.doe2@example.com");
    }

    @Test
    void exportContactShouldReturnEmptyWhenNoContact() {
        OpenPaaSUser user = openPaaSUser();
        String addressBook = "collected";

        Optional<byte[]> actual = testee.exportContact(user.username(), user.id(), addressBook).blockOptional();

        assertThat(actual).isEmpty();
    }

    @Test
    void exportContactShouldThrowErrorWhenAddressBookNotFound() {
        OpenPaaSUser user = openPaaSUser();
        String addressBook = "notfound";

        assertThatThrownBy(() ->
            testee.exportContact(user.username(), user.id(), addressBook).block()
        ).isInstanceOf(DavClientException.class);
    }
}