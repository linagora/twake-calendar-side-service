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
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration.InstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.LocalPartTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStore;

class TDriveConfigurationTest {
    private BaseConfiguration properties() {
        BaseConfiguration properties = new BaseConfiguration();
        properties.setProperty("twp.drive.enabled", true);
        properties.setProperty("twp.drive.instance.resolver", "local-part");
        properties.setProperty("twp.drive.local-part.domain", "example.com");
        properties.setProperty("twp.drive.token.url.template", "https://broker.example.com/api/public/instances/{fqdn}/token");
        properties.setProperty("twp.drive.issuer.token", "secret-partner-token");
        properties.setProperty("twp.drive.public.url.template", "https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/");
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
        properties.setProperty("twp.drive.local-part.domain", "invalid");
        properties.setProperty("twp.drive.timeout", "invalid");
        assertThatCode(() -> TDriveConfiguration.from(properties))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseToConstructDisabledImplementation() {
        assertThatThrownBy(() -> new TDriveAttachmentStore(TDriveConfiguration.from(new BaseConfiguration()), new LocalPartTDriveInstanceResolver(Optional.of("example.com"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("disabled");
    }

    @Test
    void shouldParseDefaults() {
        TDriveConfiguration configuration = TDriveConfiguration.from(properties());
        assertThat(configuration.localPartDomain()).contains("example.com");
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(configuration.folderName()).isEqualTo("Calendar attachments");
        assertThat(configuration.trustAllSslCerts()).isFalse();
        assertThat(configuration.clouderyLookup()).isEmpty();
    }

    @Test
    void shouldRejectDomainlessUserWhenLocalPartDomainIsNotConfigured() {
        BaseConfiguration properties = properties();
        properties.clearProperty("twp.drive.local-part.domain");
        LocalPartTDriveInstanceResolver resolver = new LocalPartTDriveInstanceResolver(TDriveConfiguration.from(properties).localPartDomain());

        assertThatThrownBy(() -> resolver.resolve(Username.fromLocalPartWithoutDomain("alice")).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class,
                error -> assertThat(error.reason()).isEqualTo(Reason.INVALID_ATTACHMENT));
    }

    @Test
    void shouldSelectLdapWithoutClouderyLookupCredentials() {
        BaseConfiguration properties = properties();
        properties.setProperty("twp.drive.instance.resolver", "ldap");
        properties.clearProperty("twp.drive.local-part.domain");

        TDriveConfiguration configuration = TDriveConfiguration.from(properties);

        assertThat(configuration.instanceResolver()).isEqualTo(InstanceResolver.LDAP);
        assertThat(configuration.localPartDomain()).isEmpty();
        assertThat(configuration.clouderyLookup()).isEmpty();
    }

    @Test
    void shouldDefaultToClouderyLookupWithItsOwnCredential() {
        BaseConfiguration properties = properties();
        properties.clearProperty("twp.drive.instance.resolver");
        properties.setProperty("twp.drive.lookup.url", "https://broker.example.com/api/v2/instances");
        properties.setProperty("twp.drive.lookup.token", "lookup-secret");

        TDriveConfiguration configuration = TDriveConfiguration.from(properties);

        assertThat(configuration.instanceResolver()).isEqualTo(InstanceResolver.CLOUDERY);
        assertThat(configuration.localPartDomain()).isEmpty();
        assertThat(configuration.clouderyLookup()).hasValueSatisfying(lookup -> {
            assertThat(lookup.url()).hasToString("https://broker.example.com/api/v2/instances");
            assertThat(lookup.token()).isEqualTo("lookup-secret");
            assertThat(lookup.toString()).doesNotContain("lookup-secret");
        });
    }

    @Test
    void shouldRejectDefaultClouderyLookupWithoutItsOwnCredential() {
        BaseConfiguration properties = properties();
        properties.clearProperty("twp.drive.instance.resolver");
        properties.setProperty("twp.drive.lookup.url", "https://broker.example.com/api/v2/instances");

        assertThatThrownBy(() -> TDriveConfiguration.from(properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twp.drive.lookup.token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v2/instances", "https://user:password@manager.example.com/api/v2/instances",
        "https://manager.example.com/api/v2/instances?email=other@example.com", "https://manager.example.com/api/v2/instances#fragment"})
    void shouldRejectInvalidClouderyLookupUrl(String url) {
        BaseConfiguration properties = properties();
        properties.setProperty("twp.drive.instance.resolver", "cloudery");
        properties.setProperty("twp.drive.lookup.url", url);
        properties.setProperty("twp.drive.lookup.token", "lookup-secret");

        assertThatThrownBy(() -> TDriveConfiguration.from(properties)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldParseOverridesAndRedactCredential() {
        BaseConfiguration properties = properties();
        properties.setProperty("twp.drive.folder.name", "Event files");
        properties.setProperty("twp.drive.timeout", "42s");
        properties.setProperty("twp.drive.trust.all.ssl.certs", true);
        TDriveConfiguration configuration = TDriveConfiguration.from(properties);
        assertThat(configuration.folderName()).isEqualTo("Event files");
        assertThat(configuration.timeout()).isEqualTo(Duration.ofSeconds(42));
        assertThat(configuration.trustAllSslCerts()).isTrue();
        assertThat(configuration.toString()).doesNotContain("secret-partner-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"twp.drive.token.url.template", "twp.drive.issuer.token", "twp.drive.public.url.template"})
    void shouldRequireEnabledConfiguration(String key) {
        BaseConfiguration properties = properties();
        properties.clearProperty(key);
        assertThatThrownBy(() -> TDriveConfiguration.from(properties)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "~", "eyJhbGciOiJIUzI1NiJ9.payload.signature", "abc+/=_-"})
    void shouldPreservePrintableBearerCredentials(String token) {
        BaseConfiguration properties = properties();
        properties.setProperty("twp.drive.issuer.token", token);
        assertThat(TDriveConfiguration.from(properties).issuerToken()).isEqualTo(token);
    }

    static Stream<Arguments> invalidProperties() {
        return Stream.of(
            Arguments.of("twp.drive.instance.resolver", "unknown"),
            Arguments.of("twp.drive.local-part.domain", "/relative"),
            Arguments.of("twp.drive.local-part.domain", "ftp://broker.example.com"),
            Arguments.of("twp.drive.local-part.domain", "https://user:password@broker.example.com"),
            Arguments.of("twp.drive.local-part.domain", "https://broker.example.com#fragment"),
            Arguments.of("twp.drive.local-part.domain", "https://broker.example.com?email=someone"),
            Arguments.of("twp.drive.token.url.template", "https://broker.example.com/token"),
            Arguments.of("twp.drive.token.url.template", "https://broker.example.com/{fqdn}?query=yes"),
            Arguments.of("twp.drive.public.url.template", "https://{instanceLabel}-drive.{instanceSuffix}/public"),
            Arguments.of("twp.drive.public.url.template", "https://{driveFqdn}/public?sharecode={sharecode}"),
            Arguments.of("twp.drive.public.url.template", "javascript:{sharecode}"),
            Arguments.of("twp.drive.issuer.token", " "),
            Arguments.of("twp.drive.issuer.token", "bad\r\ntoken"),
            Arguments.of("twp.drive.issuer.token", "bad\ttoken"),
            Arguments.of("twp.drive.issuer.token", "bad\u007ftoken"),
            Arguments.of("twp.drive.issuer.token", "caf\u00e9"),
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
