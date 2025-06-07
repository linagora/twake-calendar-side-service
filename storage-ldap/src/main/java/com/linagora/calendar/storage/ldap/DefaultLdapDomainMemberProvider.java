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

import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class DefaultLdapDomainMemberProvider implements LdapDomainMemberProvider {

    public static final String EMPTY_SUB_INITIAL = null;
    public static final String[] EMPTY_SUB_ANY = new String[0];

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLdapDomainMemberProvider.class);

    private final LdapRepositoryConfiguration ldapRepositoryConfiguration;
    private final LDAPConnectionPool ldapConnectionPool;

    @Inject
    public DefaultLdapDomainMemberProvider(LdapRepositoryConfiguration ldapRepositoryConfiguration,
                                           LDAPConnectionPool ldapConnectionPool) {
        this.ldapRepositoryConfiguration = ldapRepositoryConfiguration;
        this.ldapConnectionPool = ldapConnectionPool;
    }

    @Override
    public Flux<LdapDomainMember> domainMembers(Domain domain) {
        return Flux.defer(() -> {
            String baseDn = ldapRepositoryConfiguration.getUserBase();
            try {
                Filter filter = Filter.createANDFilter(
                    Filter.createEqualityFilter("objectClass", ldapRepositoryConfiguration.getUserObjectClass()),
                    Filter.createSubstringFilter("mail", EMPTY_SUB_INITIAL, EMPTY_SUB_ANY, "@" + domain.name())
                );
                SearchResult searchResult = ldapConnectionPool.search(baseDn, SearchScope.SUB, filter);
                return Flux.fromIterable(searchResult.getSearchEntries())
                    .flatMap(DefaultLdapDomainMemberProvider::ldapDomainMember);
            } catch (LDAPSearchException e) {
                return Flux.error(e);
            }
        });
    }

    private static Mono<LdapDomainMember> ldapDomainMember(SearchResultEntry entry) {
        try {
            LdapDomainMember.Builder builder = LdapDomainMember.builder();
            Optional.ofNullable(entry.getAttributeValue("uid")).ifPresent(builder::uid);
            Optional.ofNullable(entry.getAttributeValue("cn")).ifPresent(builder::cn);
            Optional.ofNullable(entry.getAttributeValue("sn")).ifPresent(builder::sn);
            Optional.ofNullable(entry.getAttributeValue("givenName")).ifPresent(builder::givenName);
            Optional.ofNullable(entry.getAttributeValue("mail"))
                .map(Throwing.function(MailAddress::new))
                .ifPresent(builder::mail);
            Optional.ofNullable(entry.getAttributeValue("telephoneNumber")).ifPresent(builder::telephoneNumber);
            Optional.ofNullable(entry.getAttributeValue("displayName")).ifPresent(builder::displayName);
            return Mono.just(builder.build());
        } catch (Exception e) {
            LOGGER.error("Error while processing LDAP entry: {}", entry.getDN(), e);
            return Mono.empty();
        }
    }
}
