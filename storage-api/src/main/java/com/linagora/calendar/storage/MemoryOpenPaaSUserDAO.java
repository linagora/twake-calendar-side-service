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

import static org.apache.james.util.ReactorUtils.publishIfPresent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.james.core.Username;

import com.linagora.calendar.storage.exception.UserConflictException;
import com.linagora.calendar.storage.exception.UserNotFoundException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryOpenPaaSUserDAO implements OpenPaaSUserDAO {
    private final ConcurrentHashMap<OpenPaaSId, OpenPaaSUser> hashMap = new ConcurrentHashMap();
    private final ConcurrentHashMap<Username, OpenPaaSId> usernameIndex = new ConcurrentHashMap<>();

    @Override
    public Mono<OpenPaaSUser> retrieve(OpenPaaSId id) {
        return Mono.fromCallable(() -> Optional.ofNullable(hashMap.get(id)))
            .handle(publishIfPresent());
    }

    @Override
    public Mono<OpenPaaSUser> retrieve(Username username) {
        return Mono.fromCallable(() -> Optional.ofNullable(usernameIndex.get(username)).map(hashMap::get))
            .handle(publishIfPresent());
    }

    @Override
    public Mono<OpenPaaSUser> add(Username username) {
        return add(username, username.asString(), username.asString());
    }

    @Override
    public Mono<OpenPaaSUser> add(Username username, String firstName, String lastName) {
        OpenPaaSId id = new OpenPaaSId(UUID.randomUUID().toString());
        OpenPaaSUser newUser = new OpenPaaSUser(username, id, firstName, lastName);

        return Mono.fromCallable(() -> {
            OpenPaaSId computedId = usernameIndex.computeIfAbsent(username, name -> {
                hashMap.put(id, newUser);
                return id;
            });

            if (computedId.equals(id)) {
                return newUser;
            } else {
                throw new UserConflictException(username);
            }
        });
    }

    @Override
    public Mono<Void> update(OpenPaaSId id, Username newUsername, String newFirstname, String newLastname) {
        return Mono.fromRunnable(() -> {
            OpenPaaSUser user = hashMap.computeIfPresent(id, (identifier, currentUser) -> {
                if (newUsername.equals(currentUser.username())) {
                    return new OpenPaaSUser(newUsername, id, newFirstname, newLastname);
                } else {
                    OpenPaaSId computedId = usernameIndex.computeIfAbsent(newUsername, name -> id);
                    if (computedId.equals(id)) {
                        usernameIndex.remove(currentUser.username());
                        return new OpenPaaSUser(newUsername, id, newFirstname, newLastname);
                    } else {
                        throw new UserConflictException(newUsername);
                    }
                }
            });

            if (user == null) {
                throw new UserNotFoundException(id);
            }
        });
    }

    @Override
    public Flux<OpenPaaSUser> list() {
        return Flux.fromIterable(hashMap.values());
    }
}
