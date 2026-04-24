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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.UserNameResolver;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class LdapUserNameResolver implements UserNameResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(LdapUserNameResolver.class);

    private final LdapUserDAO ldapUserDAO;

    @Inject
    public LdapUserNameResolver(LdapUserDAO ldapUserDAO) {
        this.ldapUserDAO = ldapUserDAO;
    }

    @Override
    public Mono<Optional<UserNames>> resolve(Username username) {
        return Mono.fromCallable(() -> {
            try {
                return ldapUserDAO.findByMail(username)
                    .map(ldapUser -> new UserNames(getFirstName(ldapUser), ldapUser.sn().orElse("")));
            } catch (Exception e) {
                LOGGER.error("Error looking up LDAP names for {}", username.asString(), e);
                return Optional.<UserNames>empty();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String getFirstName(LdapUser ldapUser) {
        String sn = ldapUser.sn().orElse("");
        String cn = ldapUser.cn().orElse("");
        if (!sn.isEmpty()) {
            return cn.replace(sn, "").trim();
        }
        return cn;
    }
}
