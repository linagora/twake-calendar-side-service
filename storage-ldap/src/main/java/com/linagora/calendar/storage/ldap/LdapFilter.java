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

import java.util.Objects;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * A validated, RFC 4515 LDAP search filter.
 *
 * <p>This wrapper keeps the UnboundID {@link Filter} type out of the calling layers (e.g. webadmin),
 * while still validating the filter as early as possible. Use {@link #of(String)} to parse and
 * validate a raw filter string, catching {@link InvalidLdapFilterException} to report a client error.</p>
 */
public class LdapFilter {

    public static class InvalidLdapFilterException extends RuntimeException {
        public InvalidLdapFilterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static LdapFilter of(String filter) {
        try {
            return new LdapFilter(filter, Filter.create(filter));
        } catch (LDAPException e) {
            throw new InvalidLdapFilterException("Invalid LDAP filter: " + filter, e);
        }
    }

    private final String filterString;
    private final Filter filter;

    private LdapFilter(String filterString, Filter filter) {
        this.filterString = filterString;
        this.filter = filter;
    }

    public Filter asFilter() {
        return filter;
    }

    public String asString() {
        return filterString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LdapFilter other)) {
            return false;
        }
        return Objects.equals(filter, other.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filter);
    }

    @Override
    public String toString() {
        return filterString;
    }
}
