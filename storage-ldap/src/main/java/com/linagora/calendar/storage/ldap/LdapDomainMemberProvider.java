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

import org.apache.james.core.Domain;

import reactor.core.publisher.Flux;

public interface LdapDomainMemberProvider {

    Flux<LdapUser> domainMembers(Domain domain, Optional<LdapFilter> additionalFilter);

    default Flux<LdapUser> domainMembers(Domain domain) {
        return domainMembers(domain, Optional.empty());
    }

}
