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

package com.linagora.calendar.storage;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.UsersRepository;

import com.linagora.calendar.storage.exception.UserConflictException;

import reactor.core.publisher.Mono;

/**
 * Lazily provisions an OpenPaaS user for a username known by the users repository (eg LDAP)
 * but not yet stored in the OpenPaaS user DAO.
 */
public class UserProvisioner {
    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final UsersRepository usersRepository;
    private final UserNameResolver userNameResolver;

    @Inject
    public UserProvisioner(OpenPaaSUserDAO userDAO,
                           OpenPaaSDomainDAO domainDAO,
                           UsersRepository usersRepository,
                           UserNameResolver userNameResolver) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.usersRepository = usersRepository;
        this.userNameResolver = userNameResolver;
    }

    public Mono<OpenPaaSUser> provisionIfExists(Username username) {
        return isLocalDomain(username)
            .filter(isLocal -> isLocal)
            .flatMap(any -> Mono.from(usersRepository.containsReactive(username)))
            .filter(exists -> exists)
            .flatMap(any -> provision(username));
    }

    private Mono<Boolean> isLocalDomain(Username username) {
        return Mono.justOrEmpty(username.getDomainPart())
            .flatMap(domainDAO::retrieve)
            .hasElement();
    }

    private Mono<OpenPaaSUser> provision(Username username) {
        return userNameResolver.resolve(username)
            .flatMap(optionalUserNames -> userDAO.add(username, optionalUserNames))
            .onErrorResume(UserConflictException.class, e -> userDAO.retrieve(username)
                .switchIfEmpty(Mono.error(e)));
    }
}
