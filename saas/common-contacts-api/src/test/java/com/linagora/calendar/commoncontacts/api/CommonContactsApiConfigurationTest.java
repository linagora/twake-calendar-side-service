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

package com.linagora.calendar.commoncontacts.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.commoncontacts.api.CommonContactsApiConfiguration.Secret;

class CommonContactsApiConfigurationTest {

    private static PropertiesConfiguration configuration(String content, boolean splitOnComa) throws Exception {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        if (splitOnComa) {
            configuration.setListDelimiterHandler(new DefaultListDelimiterHandler(','));
        }
        configuration.read(new StringReader(content));
        return configuration;
    }

    @Test
    void shouldBeDisabledWhenPortIsNotConfigured() throws Exception {
        CommonContactsApiConfiguration configuration = CommonContactsApiConfiguration.parse(configuration("", true));

        assertThat(configuration).isEqualTo(CommonContactsApiConfiguration.DISABLED);
        assertThat(configuration.enabled()).isFalse();
    }

    @Test
    void shouldParsePortAndSecrets() throws Exception {
        CommonContactsApiConfiguration configuration = CommonContactsApiConfiguration.parse(configuration("""
            common.contact.api.port=8081
            common.contact.api.secrets=abcdef,ghijz
            """, true));

        assertThat(configuration.port()).contains(8081);
        assertThat(configuration.secrets()).containsExactlyInAnyOrder(new Secret("abcdef"), new Secret("ghijz"));
    }

    @Test
    void shouldSplitSecretsWhenListDelimiterIsDisabled() throws Exception {
        CommonContactsApiConfiguration configuration = CommonContactsApiConfiguration.parse(configuration("""
            common.contact.api.port=8081
            common.contact.api.secrets=abcdef,ghijz
            """, false));

        assertThat(configuration.secrets()).containsExactlyInAnyOrder(new Secret("abcdef"), new Secret("ghijz"));
    }

    @Test
    void shouldTrimSecretsAndIgnoreEmptyOnes() throws Exception {
        CommonContactsApiConfiguration configuration = CommonContactsApiConfiguration.parse(configuration("""
            common.contact.api.port=8081
            common.contact.api.secrets= abcdef , ,ghijz,
            """, true));

        assertThat(configuration.secrets()).containsExactlyInAnyOrder(new Secret("abcdef"), new Secret("ghijz"));
    }

    @Test
    void shouldRejectPortWithoutSecrets() {
        assertThatThrownBy(() -> CommonContactsApiConfiguration.parse(configuration("""
            common.contact.api.port=8081
            """, true)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidPort() {
        assertThatThrownBy(() -> CommonContactsApiConfiguration.parse(configuration("""
            common.contact.api.port=70000
            common.contact.api.secrets=abcdef
            """, true)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptRandomPort() {
        assertThat(new CommonContactsApiConfiguration(Optional.of(0), Set.of(new Secret("abcdef"))).enabled())
            .isTrue();
    }

    @Test
    void secretsShouldNotBeExposedByToString() throws Exception {
        CommonContactsApiConfiguration configuration = CommonContactsApiConfiguration.parse(configuration("""
            common.contact.api.port=8081
            common.contact.api.secrets=abcdef
            """, true));

        assertThat(configuration.toString()).doesNotContain("abcdef");
    }
}
