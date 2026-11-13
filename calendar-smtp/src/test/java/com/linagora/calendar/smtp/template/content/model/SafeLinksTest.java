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

package com.linagora.calendar.smtp.template.content.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeLinksTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://meet.linagora.com/abc",
        "http://meet.linagora.com/abc",
        "HTTPS://meet.linagora.com/abc"})
    void httpUrlShouldKeepHttpLinks(String value) {
        assertThat(SafeLinks.httpUrl(value)).contains(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",
        "JavaScript:alert(1)",
        " javascript:alert(1)",
        "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
        "vbscript:msgbox(1)",
        "file:///etc/passwd",
        "meet.linagora.com/abc",
        "//evil.example/abc",
        "not a url",
        ""})
    void httpUrlShouldRejectOtherValues(String value) {
        assertThat(SafeLinks.httpUrl(value)).isEmpty();
    }

    @Test
    void httpUrlShouldTrimValue() {
        assertThat(SafeLinks.httpUrl("  https://meet.linagora.com/abc \n")).contains("https://meet.linagora.com/abc");
    }
}
