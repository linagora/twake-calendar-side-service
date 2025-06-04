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

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.user.ldap.LDAPConnectionFactory;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;

import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultEntry;

import jakarta.inject.Inject;
import reactor.core.publisher.Flux;

public class DefaultLdapDomainMemberProvider implements LdapDomainMemberProvider {

    private final LDAPConnectionPool ldapConnectionPool;

    @Inject
    public DefaultLdapDomainMemberProvider(LdapRepositoryConfiguration ldapRepositoryConfiguration) throws LDAPException {
        ldapConnectionPool = new LDAPConnectionFactory(ldapRepositoryConfiguration).getLdapConnectionPool();
    }

    @Override
    public Flux<LdapDomainMember> domainMembers(Domain domain) {
        return Flux.defer(() -> {
            String baseDn = getBaseDn(domain);
            String filter = "(objectClass=inetOrgPerson)";
            try (var connection = ldapConnectionPool.getConnection()) {
                var searchResult = connection.search(baseDn, com.unboundid.ldap.sdk.SearchScope.SUB, filter);
                return Flux.fromIterable(searchResult.getSearchEntries())
                    .map(DefaultLdapDomainMemberProvider::ldapDomainMember);
            } catch (Exception e) {
                return Flux.error(e);
            }
        });
    }

    private static LdapDomainMember ldapDomainMember(SearchResultEntry entry) {
        try {
            String uid = entry.getAttributeValue("uid");
            String cn = entry.getAttributeValue("cn");
            String sn = entry.getAttributeValue("sn");
            String givenName = entry.getAttributeValue("givenName");
            String mailStr = entry.getAttributeValue("mail");
            String telephoneNumber = entry.getAttributeValue("telephoneNumber");
            String displayName = entry.getAttributeValue("displayName");
            MailAddress mail = mailStr != null ? new MailAddress(mailStr) : null;
            return new LdapDomainMember(uid, cn, sn, givenName, mail, telephoneNumber, displayName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getBaseDn(Domain domain) {
        String[] domainParts = domain.name().split("\\.");
        StringBuilder dcBuilder = new StringBuilder();
        for (String part : domainParts) {
            if (!dcBuilder.isEmpty()) {
                dcBuilder.append(",");
            }
            dcBuilder.append("dc=").append(part);
        }
        return dcBuilder.toString();
    }
}
