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

package com.linagora.calendar.storage.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.apache.commons.configuration2.MapConfiguration;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

class LdapVCardPropertiesConfigurationTest {

    @Test
    void fromShouldReturnEmptyMappingWhenPropertyIsAbsent() {
        // Given a configuration without vCard property mapping
        MapConfiguration configuration = new MapConfiguration(Map.of());

        // When the configuration is read
        LdapVCardPropertiesConfiguration actual = LdapVCardPropertiesConfiguration.from(configuration);

        // Then no LDAP attribute is mapped
        assertThat(actual).isEqualTo(LdapVCardPropertiesConfiguration.EMPTY);
    }

    @Test
    void fromShouldReadMapping() {
        // Given the Twake workspace attribute mapped to the workplace vCard property
        MapConfiguration configuration = new MapConfiguration(Map.of(
            "ldap.vcard.extra.properties", "twakeWorkspaceUrl:X-TWAKE-WORKPLACE-FQDN"));

        // When the configuration is read
        LdapVCardPropertiesConfiguration actual = LdapVCardPropertiesConfiguration.from(configuration);

        // Then the attribute is mapped to that property
        assertThat(actual.vcardPropertyByLdapAttribute())
            .isEqualTo(ImmutableMap.of("twakeWorkspaceUrl", "X-TWAKE-WORKPLACE-FQDN"));
    }

    @Test
    void fromShouldReadSeveralMappings() {
        // Given two comma separated mappings surrounded by spaces
        MapConfiguration configuration = new MapConfiguration(Map.of(
            "ldap.vcard.extra.properties", " twakeWorkspaceUrl : X-TWAKE-WORKPLACE-FQDN , departmentNumber:X-DEPARTMENT "));

        // When the configuration is read
        LdapVCardPropertiesConfiguration actual = LdapVCardPropertiesConfiguration.from(configuration);

        // Then both attributes are mapped, spaces being ignored
        assertThat(actual.vcardPropertyByLdapAttribute())
            .isEqualTo(ImmutableMap.of("twakeWorkspaceUrl", "X-TWAKE-WORKPLACE-FQDN", "departmentNumber", "X-DEPARTMENT"));
    }

    @Test
    void fromShouldAcceptSeveralAttributesMappedToTheSameProperty() {
        // Given two LDAP attributes mapped to the same vCard property
        MapConfiguration configuration = new MapConfiguration(Map.of(
            "ldap.vcard.extra.properties", "description:X-INFO,departmentNumber:X-INFO"));

        // When the configuration is read
        LdapVCardPropertiesConfiguration actual = LdapVCardPropertiesConfiguration.from(configuration);

        // Then both attributes are mapped to that property, in the configuration order
        assertThat(actual.vcardPropertyByLdapAttribute())
            .containsExactly(Map.entry("description", "X-INFO"), Map.entry("departmentNumber", "X-INFO"));
    }

    @Test
    void fromShouldUpperCaseVCardProperty() {
        // Given a vCard property written in lower case
        MapConfiguration configuration = new MapConfiguration(Map.of(
            "ldap.vcard.extra.properties", "twakeWorkspaceUrl:x-twake-workplace-fqdn"));

        // When the configuration is read
        LdapVCardPropertiesConfiguration actual = LdapVCardPropertiesConfiguration.from(configuration);

        // Then the vCard property is upper cased, as parsed cards name it
        assertThat(actual.vcardPropertyByLdapAttribute())
            .isEqualTo(ImmutableMap.of("twakeWorkspaceUrl", "X-TWAKE-WORKPLACE-FQDN"));
    }

    @Test
    void fromShouldRejectEntryWithoutVCardProperty() {
        // Given an entry naming only the LDAP attribute
        MapConfiguration configuration = new MapConfiguration(Map.of(
            "ldap.vcard.extra.properties", "twakeWorkspaceUrl"));

        // When the configuration is read
        // Then it is rejected
        assertThatThrownBy(() -> LdapVCardPropertiesConfiguration.from(configuration))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromShouldRejectVCardPropertyThatIsNotExtended() {
        // Given an LDAP attribute mapped to a standard vCard property
        MapConfiguration configuration = new MapConfiguration(Map.of(
            "ldap.vcard.extra.properties", "description:NOTE"));

        // When the configuration is read
        // Then it is rejected, standard properties being built by the side service
        assertThatThrownBy(() -> LdapVCardPropertiesConfiguration.from(configuration))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
