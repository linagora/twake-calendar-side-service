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

package com.linagora.calendar.storage.configuration.resolver;

import java.util.Set;

import org.apache.james.mailbox.MailboxSession;

import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.EntryIdentifier;

import reactor.core.publisher.Flux;

public interface ConfigurationEntryResolver {

    Flux<ConfigurationEntry> resolve(Set<EntryIdentifier> ids, MailboxSession session);

    default Flux<ConfigurationEntry> resolveAll(MailboxSession session) {
        return resolve(entryIdentifiers(), session);
    }

    Set<EntryIdentifier> entryIdentifiers();
}
