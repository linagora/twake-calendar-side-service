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

package com.linagora.calendar.saas.drive.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;

import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStorageClient;

class TDriveConfigurationTest {
    private BaseConfiguration properties() {
        BaseConfiguration properties = new BaseConfiguration();
        properties.setProperty("twp.drive.enabled", true);
        properties.setProperty("twp.drive.instance.domain", "example.com");
        properties.setProperty("twp.drive.token.url.template", "https://broker.example.com/api/public/instances/{fqdn}/token");
        properties.setProperty("twp.drive.partner.token", "secret-partner-token");
        properties.setProperty("twp.drive.public.url.template", "https://{driveFqdn}/public?sharecode={sharecode}#/");
        return properties;
    }

    @Test
    void shouldBeDisabledByDefault() {
        assertThat(TDriveConfiguration.from(new BaseConfiguration()).enabled()).isFalse();
    }

    @Test
    void shouldIgnoreIrrelevantPropertiesWhenDisabled() {
        BaseConfiguration properties = properties();
        properties.setProperty("twp.drive.enabled", false);
        properties.setProperty("twp.drive.instance.domain", "invalid");
        properties.setProperty("twp.drive.timeout", "invalid");
        assertThatCode(() -> TDriveConfiguration.from(properties))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseToConstructDisabledImplementation() {
        assertThatThrownBy(() -> new TDriveAttachmentStorageClient(TDriveConfiguration.from(new BaseConfiguration())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
    }

    @Test
    void shouldParseDefaults() {
        TDriveConfiguration configuration = TDriveConfiguration.from(properties());
        assertThat(configuration.driveUrlTemplate()).isEqualTo("https://{fqdn}");
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(configuration.folderName()).isEqualTo("Calendar attachments");
        assertThat(configuration.trustAllSslCerts()).isFalse();
    }

    @Test
    void shouldParseOverridesAndRedactCredential() {
        BaseConfiguration properties = properties();
        properties.setProperty("twp.drive.drive.url.template", "http://proxy.example.com/{fqdn}/drive");
        properties.setProperty("twp.drive.folder.name", "Event files");
        properties.setProperty("twp.drive.timeout", "42s");
        properties.setProperty("twp.drive.trust.all.ssl.certs", true);
        TDriveConfiguration configuration = TDriveConfiguration.from(properties);
        assertThat(configuration.driveUrlTemplate()).isEqualTo("http://proxy.example.com/{fqdn}/drive");
        assertThat(configuration.folderName()).isEqualTo("Event files");
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(42));
        assertThat(configuration.trustAllSslCerts()).isTrue();
        assertThat(configuration.toString()).doesNotContain("secret-partner-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"twp.drive.instance.domain", "twp.drive.token.url.template", "twp.drive.partner.token", "twp.drive.public.url.template"})
    void shouldRequireEnabledConfiguration(String key) {
        BaseConfiguration properties = properties();
        properties.clearProperty(key);
        assertThatThrownBy(() -> TDriveConfiguration.from(properties)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
    }

    static Stream<Arguments> invalidProperties() {
        return Stream.of(
            Arguments.of("twp.drive.instance.domain", "/relative"),
            Arguments.of("twp.drive.instance.domain", "ftp://broker.example.com"),
            Arguments.of("twp.drive.instance.domain", "https://user:password@broker.example.com"),
            Arguments.of("twp.drive.instance.domain", "https://broker.example.com#fragment"),
            Arguments.of("twp.drive.instance.domain", "https://broker.example.com?email=someone"),
            Arguments.of("twp.drive.token.url.template", "https://broker.example.com/token"),
            Arguments.of("twp.drive.token.url.template", "https://broker.example.com/{fqdn}?query=yes"),
            Arguments.of("twp.drive.drive.url.template", "https://drive.example.com"),
            Arguments.of("twp.drive.public.url.template", "https://{driveFqdn}/public"),
            Arguments.of("twp.drive.public.url.template", "javascript:{sharecode}"),
            Arguments.of("twp.drive.partner.token", " "),
            Arguments.of("twp.drive.partner.token", "bad\r\ntoken"),
            Arguments.of("twp.drive.folder.name", "../attachments"),
            Arguments.of("twp.drive.folder.name", ".."),
            Arguments.of("twp.drive.timeout", "0s"),
            Arguments.of("twp.drive.timeout", "-1s"));
    }

    @ParameterizedTest
    @MethodSource("invalidProperties")
    void shouldRejectInvalidConfiguration(String key, String value) {
        BaseConfiguration properties = properties();
        properties.setProperty(key, value);
        assertThatThrownBy(() -> TDriveConfiguration.from(properties)).isInstanceOf(IllegalArgumentException.class);
    }
}
