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

package com.linagora.calendar.restapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.restapi.RestApiConfiguration;

class TrustedOriginsTest {

    @Test
    void asOriginShouldDropPathQueryAndFragment() {
        assertThat(TrustedOrigins.asOrigin("https://calendar.example.com/calendar/#/settings?a=b"))
            .contains("https://calendar.example.com");
    }

    @Test
    void asOriginShouldNormalizeCaseAndDefaultPort() {
        assertThat(TrustedOrigins.asOrigin("HTTPS://Calendar.Example.COM:443"))
            .contains("https://calendar.example.com");
    }

    @Test
    void asOriginShouldKeepNonDefaultPort() {
        assertThat(TrustedOrigins.asOrigin("http://localhost:8080/"))
            .contains("http://localhost:8080");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "", "/relative/path", "file:///etc/passwd", "javascript:alert(1)", "https://", "ht tp://x"})
    void asOriginShouldRejectNonHttpOrOpaqueOrigins(String value) {
        assertThat(TrustedOrigins.asOrigin(value)).isEmpty();
    }

    @Test
    void containsShouldMatchOnlyExactOrigins() {
        TrustedOrigins trustedOrigins = TrustedOrigins.of(List.of("https://calendar.example.com/"));

        assertThat(trustedOrigins.contains("https://calendar.example.com")).isTrue();
        assertThat(trustedOrigins.contains("http://calendar.example.com")).isFalse();
        assertThat(trustedOrigins.contains("https://calendar.example.com.evil.example")).isFalse();
        assertThat(trustedOrigins.contains("https://calendar.example.com:8443")).isFalse();
    }

    @Test
    void ofShouldRejectInvalidOrigins() {
        assertThatThrownBy(() -> TrustedOrigins.of(List.of("not an url")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withShouldAddOrigins() {
        TrustedOrigins trustedOrigins = TrustedOrigins.of(List.of("https://a.example"))
            .with(List.of("https://b.example/path"));

        assertThat(trustedOrigins.origins()).containsExactlyInAnyOrder("https://a.example", "https://b.example");
    }

    @Test
    void extraTrustedOriginsShouldBeReadFromConfiguration() {
        BaseConfiguration configuration = new BaseConfiguration();
        configuration.addProperty("admin.password", "secret");
        configuration.addProperty("spa.excal.url", "https://excal.example");
        configuration.addProperty(TrustedOrigins.EXTRA_TRUSTED_ORIGINS_PROPERTY, "https://c.example, https://d.example:8443");

        TrustedOrigins trustedOrigins = TrustedOrigins.from(RestApiConfiguration.parseConfiguration(configuration), configuration);

        assertThat(trustedOrigins.origins())
            .contains("https://excal.example", "https://c.example", "https://d.example:8443");
    }
}
