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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

class OpenPaaSUserTest {
    private static final Username USERNAME = Username.of("jdoe@example.com");
    private static final OpenPaaSId ID = new OpenPaaSId("123");

    @Test
    void fullNameShouldCombineFirstnameAndLastname() {
        OpenPaaSUser user = new OpenPaaSUser(USERNAME, ID, "John", "DOE");
        assertThat(user.fullName()).isEqualTo("John DOE");
    }

    @Test
    void fullNameShouldDeduplicateEqualFirstnameAndLastname() {
        OpenPaaSUser user = new OpenPaaSUser(USERNAME, ID, "jdoe@example.com", "jdoe@example.com");
        assertThat(user.fullName()).isEqualTo("jdoe@example.com");
    }

    @Test
    void fullNameShouldFallbackToUsernameWhenFirstnameAndLastnameAreBlank() {
        OpenPaaSUser user = new OpenPaaSUser(USERNAME, ID, "", "");
        assertThat(user.fullName()).isEqualTo("jdoe@example.com");
    }

    @Test
    void fullNameShouldHandleSingleName() {
        OpenPaaSUser user = new OpenPaaSUser(USERNAME, ID, "Alice", null);
        assertThat(user.fullName()).isEqualTo("Alice");
    }
}
