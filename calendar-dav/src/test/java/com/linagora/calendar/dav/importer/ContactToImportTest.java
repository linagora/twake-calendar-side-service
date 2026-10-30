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

package com.linagora.calendar.dav.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class ContactToImportTest {

    @Test
    void parseShouldSkipPropertiesWithEmptyName() {
        byte[] vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Someone\r\n:broken line\r\nEND:VCARD\r\n".getBytes(StandardCharsets.UTF_8);

        assertThat(ContactToImport.parse(vcard)).hasSize(1);
    }

    @Test
    void parseShouldKeepValidPropertiesWhenSomeCannotBeWritten() {
        byte[] vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Someone\r\n:broken line\r\nEND:VCARD\r\n".getBytes(StandardCharsets.UTF_8);

        List<ContactToImport> contacts = ContactToImport.parse(vcard);

        assertThat(new String(contacts.getFirst().payload(), StandardCharsets.UTF_8))
            .contains("FN:Someone")
            .doesNotContain("broken line");
    }

    @Test
    void parseShouldSkipPropertiesWithInvalidName() {
        byte[] vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Someone\r\n.SION:value\r\nEND:VCARD\r\n".getBytes(StandardCharsets.UTF_8);

        assertThat(ContactToImport.parse(vcard)).hasSize(1);
    }

    @Test
    void parseShouldImportOtherContactsWhenOneHasAnUnwritableProperty() {
        byte[] vcard = ("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Someone\r\n:broken line\r\nEND:VCARD\r\n"
            + "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Other\r\nEND:VCARD\r\n").getBytes(StandardCharsets.UTF_8);

        assertThat(ContactToImport.parse(vcard)).hasSize(2);
    }

    @Test
    void parseShouldPreserveValidContacts() {
        byte[] vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Someone\r\nEMAIL:someone@example.com\r\nEND:VCARD\r\n".getBytes(StandardCharsets.UTF_8);

        List<ContactToImport> contacts = ContactToImport.parse(vcard);

        assertThat(new String(contacts.getFirst().payload(), StandardCharsets.UTF_8))
            .contains("FN:Someone")
            .contains("EMAIL:someone@example.com");
    }
}
