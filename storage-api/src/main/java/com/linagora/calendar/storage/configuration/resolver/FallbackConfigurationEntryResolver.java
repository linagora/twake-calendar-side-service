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

import org.apache.james.mailbox.MailboxSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.ModuleName;

import reactor.core.publisher.Mono;

public interface FallbackConfigurationEntryResolver {

    Mono<JsonNode> resolve(ModuleName moduleName, ConfigurationKey configurationKey, MailboxSession session);

    class EmptyFallbackConfigurationEntryResolver implements FallbackConfigurationEntryResolver {

        @Override
        public Mono<JsonNode> resolve(ModuleName moduleName, ConfigurationKey configurationKey, MailboxSession session) {
            return Mono.empty();
        }
    }
}
