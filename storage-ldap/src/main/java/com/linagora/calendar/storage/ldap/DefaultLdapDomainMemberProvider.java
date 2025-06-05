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

import static com.linagora.calendar.storage.ldap.LdapStorageModule.LDAP_STORAGE_INJECTION;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class DefaultLdapDomainMemberProvider implements LdapDomainMemberProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLdapDomainMemberProvider.class);

    private final LdapRepositoryConfiguration ldapRepositoryConfiguration;
    private final LDAPConnectionPool ldapConnectionPool;

    @Inject
    public DefaultLdapDomainMemberProvider(@Named(LDAP_STORAGE_INJECTION) LdapRepositoryConfiguration ldapRepositoryConfiguration,
                                           @Named(LDAP_STORAGE_INJECTION) LDAPConnectionPool ldapConnectionPool) {
        this.ldapRepositoryConfiguration = ldapRepositoryConfiguration;
        this.ldapConnectionPool = ldapConnectionPool;
    }

    @Override
    public Flux<LdapDomainMember> domainMembers(Domain domain) {
        return Flux.defer(() -> {
            String baseDn = ldapRepositoryConfiguration.getUserBase();
            String filter = String.format("(&(objectClass=%s)(mail=*@%s))", ldapRepositoryConfiguration.getUserObjectClass(), domain.name());
            try {
                var searchResult = ldapConnectionPool.search(baseDn, SearchScope.SUB, filter);
                return Flux.fromIterable(searchResult.getSearchEntries())
                    .flatMap(DefaultLdapDomainMemberProvider::ldapDomainMember);
            } catch (LDAPSearchException e) {
                return Flux.error(e);
            }
        });
    }

    private static Mono<LdapDomainMember> ldapDomainMember(SearchResultEntry entry) {
        try {
            String uid = entry.getAttributeValue("uid");
            String cn = entry.getAttributeValue("cn");
            String sn = entry.getAttributeValue("sn");
            String givenName = entry.getAttributeValue("givenName");
            String mailStr = entry.getAttributeValue("mail");
            String telephoneNumber = entry.getAttributeValue("telephoneNumber");
            String displayName = entry.getAttributeValue("displayName");
            MailAddress mail = Optional.ofNullable(mailStr).map(Throwing.function(MailAddress::new)).orElse(null);
            return Mono.just(new LdapDomainMember(uid, cn, sn, givenName, mail, telephoneNumber, displayName));
        } catch (Exception e) {
            LOGGER.error("Error while processing LDAP entry: {}", entry.getDN(), e);
            return Mono.empty();
        }
    }
}
