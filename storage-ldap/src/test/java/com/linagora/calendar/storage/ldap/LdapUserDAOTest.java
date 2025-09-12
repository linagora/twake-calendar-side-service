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

import java.util.List;
import java.util.Optional;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.plist.PropertyListConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.user.ldap.DockerLdapSingleton;
import org.apache.james.user.ldap.LDAPConnectionFactory;
import org.apache.james.user.ldap.LdapGenericContainer;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.unboundid.ldap.sdk.LDAPException;

public class LdapUserDAOTest {

    @RegisterExtension
    static LdapExtension ldapExtension = new LdapExtension(DockerLdapSingleton.ldapContainer);

    private LdapUserDAO ldapUserDAO;

    @BeforeEach
    void setUp() throws ConfigurationException, LDAPException {
        LdapRepositoryConfiguration configuration = LdapRepositoryConfiguration.from(ldapRepositoryConfiguration(ldapExtension.ldapContainer(), Optional.of(ADMIN)));
        ldapUserDAO = new LdapUserDAO(
            configuration,
            new LDAPConnectionFactory(configuration).getLdapConnectionPool());
    }

    @Test
    void getAllUsersShouldReturnAllUsers() throws Exception {
        LdapUser expected1 = LdapUser.builder()
            .uid("james-user")
            .cn("James User")
            .sn("User")
            .givenName("James")
            .mail(new MailAddress("james-user@james.org"))
            .telephoneNumber("+33612345678")
            .displayName("James User")
            .build();

        LdapUser expected2 = LdapUser.builder()
            .uid("james-user2")
            .cn("James User2")
            .sn("User2")
            .mail(new MailAddress("james-user2@james.org"))
            .build();

        LdapUser expected3 = LdapUser.builder()
            .uid("other-domain-user")
            .cn("other-domain-user")
            .sn("other-domain-user")
            .mail(new MailAddress("other-domain-user@other.domain"))
            .build();

        LdapUser expected4 = LdapUser.builder()
            .uid("no-mail-user")
            .cn("no-mail-user")
            .sn("no-mail-user")
            .build();

        List<LdapUser> actual = ldapUserDAO.getAllUsers();

        assertThat(actual).containsExactlyInAnyOrder(expected1, expected2, expected3, expected4);
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
