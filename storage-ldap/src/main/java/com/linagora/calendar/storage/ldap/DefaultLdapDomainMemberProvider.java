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
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.SearchResult;
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
    public Flux<LdapUser> domainMembers(Domain domain, Optional<LdapFilter> additionalFilter) {
        return Flux.defer(() -> {
            String baseDn = ldapRepositoryConfiguration.getUserBase();
            try {
                Filter filter = buildFilter(domain, additionalFilter);
                SearchResult searchResult = ldapConnectionPool.search(baseDn, SearchScope.SUB, filter);
                return Flux.fromIterable(searchResult.getSearchEntries())
                    .flatMap(entry -> Mono.fromCallable(() -> LdapUser.fromLdapEntry(entry)));
            } catch (LDAPSearchException e) {
                return Flux.error(e);
            }
        });
    }

    private Filter buildFilter(Domain domain, Optional<LdapFilter> additionalFilter) {
        Filter baseFilter = Filter.createANDFilter(
            Filter.createEqualityFilter("objectClass", ldapRepositoryConfiguration.getUserObjectClass()),
            Filter.createSubstringFilter("mail", EMPTY_SUB_INITIAL, EMPTY_SUB_ANY, "@" + domain.name()));

        return additionalFilter
            .map(extraFilter -> Filter.createANDFilter(baseFilter, extraFilter.asFilter()))
            .orElse(baseFilter);
    }
}
