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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.exception.UserNotFoundException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryUserConfigurationDAO implements UserConfigurationDAO {

    private final ConcurrentHashMap<Username, Set<ConfigurationEntry>> configurationStore;

    private final OpenPaaSUserDAO userDAO;

    @Inject
    @Singleton
    public MemoryUserConfigurationDAO(OpenPaaSUserDAO userDAO) {
        this.userDAO = userDAO;
        this.configurationStore = new ConcurrentHashMap<>();
    }

    @Override
    public Mono<Void> persistConfiguration(Set<ConfigurationEntry> configurationEntries, MailboxSession userSession) {
        Username username = userSession.getUser();

        return userDAO.retrieve(username)
            .switchIfEmpty(Mono.error(new UserNotFoundException(username)))
            .flatMap(existUser -> Mono.fromCallable(() -> configurationStore.put(username, ImmutableSet.copyOf(configurationEntries))))
            .then(Mono.empty());
    }

    @Override
    public Flux<ConfigurationEntry> retrieveConfiguration(MailboxSession mailboxSession) {
        Username username = mailboxSession.getUser();
        return userDAO.retrieve(username)
            .switchIfEmpty(Mono.error(new UserNotFoundException(username)))
            .flatMapMany(user -> Mono.justOrEmpty(configurationStore.get(username))
                .map(ImmutableSet::copyOf)
                .flatMapMany(Flux::fromIterable));
    }
}
