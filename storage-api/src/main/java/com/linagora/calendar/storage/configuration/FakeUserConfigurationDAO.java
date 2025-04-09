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

package com.linagora.calendar.storage.configuration;

import java.util.Set;

import org.apache.james.mailbox.MailboxSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class FakeUserConfigurationDAO implements UserConfigurationDAO {
    @Override
    public Mono<Void> persistConfiguration(Set<ConfigurationEntry> configurationEntries, MailboxSession userSession) {
        return Mono.error(new RuntimeException("Not supported"));
    }

    @Override
    public Flux<ConfigurationEntry> retrieveConfiguration(MailboxSession mailboxSession) {
        return Flux.empty();
    }
}
