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

import org.junit.jupiter.api.Test;

class MailtoUriTest {

    @Test
    void stripMailtoPrefixShouldSanitizeMalformedMailtoAddress() {
        assertThat(MailtoUri.stripMailtoPrefix(" mailto:<alice@linagora.com> "))
            .isEqualTo("alice@linagora.com");
    }

    @Test
    void stripMailtoPrefixShouldSanitizeTrailingAngleBracket() {
        assertThat(MailtoUri.stripMailtoPrefix("mailto:alice@linagora.com>"))
            .isEqualTo("alice@linagora.com");
    }

    @Test
    void stripMailtoPrefixShouldBeCaseInsensitive() {
        assertThat(MailtoUri.stripMailtoPrefix("MAILTO:alice@linagora.com"))
            .isEqualTo("alice@linagora.com");
    }

    @Test
    void stripMailtoPrefixShouldKeepPlainAddress() {
        assertThat(MailtoUri.stripMailtoPrefix("alice@linagora.com"))
            .isEqualTo("alice@linagora.com");
    }

    @Test
    void hasMailtoPrefixShouldBeCaseInsensitiveAndSanitizeAddress() {
        assertThat(MailtoUri.hasMailtoPrefix(" <MAILTO:alice@linagora.com> "))
            .isTrue();
    }
}
