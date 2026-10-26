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

import static org.apache.james.user.ldap.DockerLdapSingleton.ADMIN;
import static org.apache.james.user.ldap.DockerLdapSingleton.ADMIN_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.plist.PropertyListConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.james.core.Username;
import org.apache.james.user.ldap.DockerLdapSingleton;
import org.apache.james.user.ldap.LDAPConnectionFactory;
import org.apache.james.user.ldap.LdapGenericContainer;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.UserContactResolver.UserContact;
import com.linagora.calendar.storage.UserNameResolver.UserNames;
import com.unboundid.ldap.sdk.LDAPException;

public class LdapUserContactResolverTest {

    @RegisterExtension
    static LdapExtension ldapExtension = new LdapExtension(DockerLdapSingleton.ldapContainer);

    private static final Username JAMES_USER = Username.of("james-user@james.org");
    private static final Username JAMES_USER2 = Username.of("james-user2@james.org");

    private LdapUserContactResolver resolver;

    @BeforeEach
    void setUp() throws ConfigurationException, LDAPException {
        LdapRepositoryConfiguration configuration = LdapRepositoryConfiguration.from(ldapRepositoryConfiguration(ldapExtension.ldapContainer(), Optional.of(ADMIN)));
        LdapVCardPropertiesConfiguration vcardPropertiesConfiguration = new LdapVCardPropertiesConfiguration(ImmutableMap.of(
            "description", "X-DESCRIPTION",
            "employeeNumber", "X-EMPLOYEE-NUMBER"));
        LdapUserDAO ldapUserDAO = new LdapUserDAO(configuration, new LDAPConnectionFactory(configuration).getLdapConnectionPool(), vcardPropertiesConfiguration);
        resolver = new LdapUserContactResolver(ldapUserDAO, new LdapUserContactMapper(vcardPropertiesConfiguration));
    }

    @Test
    void resolveShouldReturnContactFromLdapEntry() {
        // Given james-user, whose LDAP entry has cn, sn, givenName, displayName, telephoneNumber and a description
        // mapped to X-DESCRIPTION, but no employeeNumber
        // When the contact of james-user is resolved
        Optional<UserContact> result = resolver.resolve(JAMES_USER).block();

        // Then every field comes from its LDAP attribute, and only the mapped attributes present on the entry are carried
        assertThat(result).contains(UserContact.builder()
            .names(Optional.of(new UserNames("James", "User")))
            .givenName(Optional.of("James"))
            .familyName(Optional.of("User"))
            .displayName(Optional.of("James User"))
            .telephoneNumber(Optional.of("+33612345678"))
            .extraProperties(ImmutableListMultimap.of("X-DESCRIPTION", "abcdef"))
            .build());
    }

    @Test
    void resolveShouldDeriveMissingNamesFromCommonName() {
        // Given james-user2, whose LDAP entry has cn="James User2" and sn="User2" but no givenName, displayName
        // nor telephoneNumber
        // When the contact of james-user2 is resolved
        Optional<UserContact> result = resolver.resolve(JAMES_USER2).block();

        // Then the given name is the common name without the surname, and the display name is the common name
        assertThat(result).contains(UserContact.builder()
            .names(Optional.of(new UserNames("James", "User2")))
            .givenName(Optional.of("James"))
            .familyName(Optional.of("User2"))
            .displayName(Optional.of("James User2"))
            .extraProperties(ImmutableListMultimap.of("X-DESCRIPTION", "cn=active,ou=accountStatus,ou=whatever,o=gov,c=vn"))
            .build());
    }

    @Test
    void resolveShouldReturnEmptyWhenUserNotInLdap() {
        // Given a user unknown to the LDAP
        // When the contact of that user is resolved
        Optional<UserContact> result = resolver.resolve(Username.of("unknown@james.org")).block();

        // Then nothing is resolved
        assertThat(result).isEmpty();
    }

    private HierarchicalConfiguration<ImmutableNode> ldapRepositoryConfiguration(LdapGenericContainer ldapContainer, Optional<Username> administrator) {
        PropertyListConfiguration configuration = baseConfiguration(ldapContainer);
        configuration.addProperty("[@userIdAttribute]", "mail");
        administrator.ifPresent(username -> configuration.addProperty("[@administratorId]", username.asString()));
        return configuration;
    }

    private PropertyListConfiguration baseConfiguration(LdapGenericContainer ldapContainer) {
        PropertyListConfiguration configuration = new PropertyListConfiguration();
        configuration.addProperty("[@ldapHost]", ldapContainer.getLdapHost());
        configuration.addProperty("[@principal]", "cn=admin,dc=james,dc=org");
        configuration.addProperty("[@credentials]", ADMIN_PASSWORD);
        configuration.addProperty("[@userBase]", "ou=people,dc=james,dc=org");
        configuration.addProperty("[@userObjectClass]", "inetOrgPerson");
        configuration.addProperty("[@connectionTimeout]", "2000");
        configuration.addProperty("[@readTimeout]", "2000");
        return configuration;
    }
}
