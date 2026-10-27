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

import org.apache.james.core.Username;

import com.linagora.calendar.storage.UserContactResolver;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class LdapUserContactResolver implements UserContactResolver {
    private final LdapUserDAO ldapUserDAO;
    private final LdapUserContactMapper ldapUserContactMapper;

    @Inject
    public LdapUserContactResolver(LdapUserDAO ldapUserDAO, LdapUserContactMapper ldapUserContactMapper) {
        this.ldapUserDAO = ldapUserDAO;
        this.ldapUserContactMapper = ldapUserContactMapper;
    }

    @Override
    public Mono<Optional<UserContact>> resolve(Username username) {
        return Mono.fromCallable(() -> ldapUserDAO.findByMail(username)
                .map(ldapUserContactMapper::toUserContact))
            .subscribeOn(Schedulers.boundedElastic());
    }
}
