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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsciiMailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"guest@xn--p1b6ci4b4b3a.xn--h2brj9c", "guest@\u0909\u0926\u093e\u0939\u0930\u0923.\u092d\u093e\u0930\u0924"})
    void shouldBuildAnInternetAddressForADomainHoldingCombiningMarks(String address) throws Exception {
        assertThat(AsciiMailAddress.toInternetAddress(new MailAddress(address)).getAddress())
            .isEqualTo("guest@xn--p1b6ci4b4b3a.xn--h2brj9c");
    }

    @Test
    void shouldConvertALettersOnlyDomain() {
        assertThat(AsciiMailAddress.asciiDomain("guest@b\u00fccher.example")).isEqualTo("guest@xn--bcher-kva.example");
    }

    @Test
    void shouldLeaveAnAddressWithoutDomainUnchanged() {
        assertThat(AsciiMailAddress.asciiDomain("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bob@domain.tld", "\"a@b\"@domain.tld", "b\u00f6b@domain.tld", "bob@[127.0.0.1]"})
    void shouldLeaveAnAsciiDomainUnchanged(String address) {
        assertThat(AsciiMailAddress.asciiDomain(address)).isEqualTo(address);
    }
}
