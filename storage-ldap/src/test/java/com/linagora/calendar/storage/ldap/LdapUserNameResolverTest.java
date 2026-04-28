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

import com.linagora.calendar.storage.UserNameResolver;
import com.unboundid.ldap.sdk.LDAPException;

public class LdapUserNameResolverTest {

    @RegisterExtension
    static LdapExtension ldapExtension = new LdapExtension(DockerLdapSingleton.ldapContainer);

    private LdapUserNameResolver resolver;

    @BeforeEach
    void setUp() throws ConfigurationException, LDAPException {
        LdapRepositoryConfiguration configuration = LdapRepositoryConfiguration.from(ldapRepositoryConfiguration(ldapExtension.ldapContainer(), Optional.of(ADMIN)));
        LdapUserDAO ldapUserDAO = new LdapUserDAO(configuration, new LDAPConnectionFactory(configuration).getLdapConnectionPool());
        resolver = new LdapUserNameResolver(ldapUserDAO);
    }

    @Test
    void resolveShouldReturnNamesFromLdap() {
        Optional<UserNameResolver.UserNames> result = resolver.resolve(Username.of("james-user@james.org")).block();

        assertThat(result).contains(new UserNameResolver.UserNames("James", "User"));
    }

    @Test
    void resolveShouldDeriveFirstnameFromCnWhenSnPresent() {
        // james-user2 has cn="James User2", sn="User2", no givenName
        Optional<UserNameResolver.UserNames> result = resolver.resolve(Username.of("james-user2@james.org")).block();

        assertThat(result).contains(new UserNameResolver.UserNames("James", "User2"));
    }

    @Test
    void resolveShouldReturnEmptyWhenUserNotInLdap() {
        Optional<UserNameResolver.UserNames> result = resolver.resolve(Username.of("unknown@james.org")).block();

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
