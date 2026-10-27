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

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.UserContactResolver.UserContact;

class LdapUserContactMapperTest {

    @Test
    void toUserContactShouldWriteMappedAttributeUnderItsVCardProperty() {
        // Given the Twake workspace attribute mapped to the workplace vCard property, and a user having that attribute
        LdapUserContactMapper mapper = new LdapUserContactMapper(new LdapVCardPropertiesConfiguration(
            ImmutableMap.of("twakeWorkspaceUrl", "X-TWAKE-WORKPLACE-FQDN")));
        LdapUser ldapUser = LdapUser.builder()
            .cn("Alice Martin")
            .sn("Martin")
            .extraAttributes(ImmutableMap.of("twakeWorkspaceUrl", "alice.example.com"))
            .build();

        // When the contact of that user is built
        UserContact contact = mapper.toUserContact(ldapUser);

        // Then the attribute value is carried under the vCard property
        assertThat(contact.extraProperties())
            .isEqualTo(ImmutableListMultimap.of("X-TWAKE-WORKPLACE-FQDN", "alice.example.com"));
    }

    @Test
    void toUserContactShouldKeepEveryValueOfAttributesMappedToTheSameProperty() {
        // Given description and departmentNumber both mapped to X-INFO, and a user having both attributes
        LdapUserContactMapper mapper = new LdapUserContactMapper(new LdapVCardPropertiesConfiguration(
            ImmutableMap.of("description", "X-INFO", "departmentNumber", "X-INFO")));
        LdapUser ldapUser = LdapUser.builder()
            .cn("Alice Martin")
            .sn("Martin")
            .extraAttributes(ImmutableMap.of("departmentNumber", "R&D", "description", "abcdef"))
            .build();

        // When the contact of that user is built
        UserContact contact = mapper.toUserContact(ldapUser);

        // Then X-INFO carries both values, in the configuration order
        assertThat(contact.extraProperties())
            .isEqualTo(ImmutableListMultimap.of("X-INFO", "abcdef", "X-INFO", "R&D"));
    }

    @Test
    void toUserContactShouldSkipMappedAttributeAbsentFromUser() {
        // Given description and departmentNumber mapped, and a user having only a description
        LdapUserContactMapper mapper = new LdapUserContactMapper(new LdapVCardPropertiesConfiguration(
            ImmutableMap.of("description", "X-DESCRIPTION", "departmentNumber", "X-DEPARTMENT")));
        LdapUser ldapUser = LdapUser.builder()
            .cn("Alice Martin")
            .sn("Martin")
            .extraAttributes(ImmutableMap.of("description", "abcdef"))
            .build();

        // When the contact of that user is built
        UserContact contact = mapper.toUserContact(ldapUser);

        // Then only the present attribute is carried
        assertThat(contact.extraProperties())
            .isEqualTo(ImmutableListMultimap.of("X-DESCRIPTION", "abcdef"));
    }
}
