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

package com.linagora.calendar.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MailUsernamesTest {

    @Test
    void shouldConvertAnAddressThatIsAUsername() throws Exception {
        assertThat(MailUsernames.asUsername(new MailAddress("bob@example.com")))
            .contains(Username.of("bob@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$rg@example.com", "-bob@example.com", "$ba@[127.5.0.1]"})
    void shouldBeEmptyForAnAddressThatIsNotAUsername(String address) throws Exception {
        assertThat(MailUsernames.asUsername(new MailAddress(address))).isEmpty();
    }
}
