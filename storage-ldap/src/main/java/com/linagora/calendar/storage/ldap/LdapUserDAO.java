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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;

public class LdapUserDAO {

    private static final Logger LOGGER = LoggerFactory.getLogger(LdapUserDAO.class);

    private final LdapRepositoryConfiguration ldapRepositoryConfiguration;
    private final LDAPConnectionPool ldapConnectionPool;

    @Inject
    public LdapUserDAO(LdapRepositoryConfiguration ldapRepositoryConfiguration,
                       LDAPConnectionPool ldapConnectionPool) {
        this.ldapRepositoryConfiguration = ldapRepositoryConfiguration;
        this.ldapConnectionPool = ldapConnectionPool;
    }

    public List<LdapUser> getAllUsers() throws LDAPSearchException {
        String baseDn = ldapRepositoryConfiguration.getUserBase();
        Filter filter = Filter.createEqualityFilter("objectClass", ldapRepositoryConfiguration.getUserObjectClass());
        SearchResult searchResult = ldapConnectionPool.search(baseDn, SearchScope.SUB, filter);
        return searchResult.getSearchEntries().stream()
            .map(LdapUser::fromLdapEntry)
            .toList();
    }
}
