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

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collector;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.common.collect.ImmutableTable;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ConfigurationResolver {
    private final Set<ConfigurationEntryResolver> resolvers;

    @Inject
    public ConfigurationResolver(Set<ConfigurationEntryResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public Mono<ConfigurationDocument> resolve(Set<EntryIdentifier> request, MailboxSession session) {
        return Flux.fromIterable(resolvers)
            .concatMap(resolver -> resolver.resolve(request, session))
            .collect(toConfigurationTable())
            .map(ConfigurationDocument::new);
    }

    public Mono<ConfigurationDocument> resolveAll(MailboxSession session) {
        // Returns only explicitly implemented configuration resolvers
        return Flux.fromIterable(resolvers)
            .flatMap(resolver -> resolver.resolveAll(session))
            .collect(toConfigurationTable())
            .map(ConfigurationDocument::new);
    }

    private static Collector<ConfigurationEntry, ?, ImmutableTable<ModuleName, ConfigurationKey, JsonNode>> toConfigurationTable() {
        return ImmutableTable.toImmutableTable(ConfigurationEntry::moduleName, ConfigurationEntry::configurationKey, ConfigurationResolver::nodeOrJsonNull);
    }

    private static JsonNode nodeOrJsonNull(ConfigurationEntry entry) {
        return Optional.ofNullable(entry.node())
            .orElse(NullNode.getInstance());
    }
}
