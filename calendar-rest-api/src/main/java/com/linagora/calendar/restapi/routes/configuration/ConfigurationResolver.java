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

package com.linagora.calendar.restapi.routes.configuration;

import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Sets;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ConfigurationResolver {
    private final Set<ConfigurationEntryResolver> resolvers;
    private final FallbackConfigurationEntryResolver fallbackConfigurationEntryResolver;

    @Inject
    public ConfigurationResolver(Set<ConfigurationEntryResolver> resolvers, FallbackConfigurationEntryResolver fallbackConfigurationEntryResolver) {
        this.resolvers = resolvers;
        this.fallbackConfigurationEntryResolver = fallbackConfigurationEntryResolver;
    }

    public Mono<ConfigurationDocument> resolve(Set<EntryIdentifier> request, MailboxSession session) {
        return Flux.fromIterable(resolvers)
            .concatMap(resolver -> resolver.resolve(request, session))
            .collect(ImmutableTable.toImmutableTable(ConfigurationEntry::moduleName, ConfigurationEntry::configurationKey, ConfigurationEntry::node))
            .flatMap(resolved -> {
                Set<EntryIdentifier> resolvedIds = resolved.cellSet().stream()
                    .map(cell -> new EntryIdentifier(cell.getRowKey(), cell.getColumnKey()))
                    .collect(ImmutableSet.toImmutableSet());
                Set<EntryIdentifier> toBeResolved = Sets.difference(request, resolvedIds);

                return Flux.fromIterable(toBeResolved)
                    .flatMap(id -> fallbackConfigurationEntryResolver.resolve(id.moduleName(), id.configurationKey(), session).map(json -> new ConfigurationEntry(id.moduleName(), id.configurationKey(), json)), 4)
                    .collect(ImmutableTable.toImmutableTable(ConfigurationEntry::moduleName, ConfigurationEntry::configurationKey, ConfigurationEntry::node))
                    .map(fallback -> ImmutableTable.<ModuleName, ConfigurationKey, JsonNode>builder().putAll(resolved).putAll(fallback).build());
            })
            .map(ConfigurationDocument::new);
    }

    public Mono<ConfigurationDocument> resolveAll(MailboxSession session) {
        // Returns only explicitly implemented configuration resolvers
        return Flux.fromIterable(resolvers)
            .flatMap(resolver -> resolver.resolveAll(session))
            .collect(ImmutableTable.toImmutableTable(ConfigurationEntry::moduleName, ConfigurationEntry::configurationKey, ConfigurationEntry::node))
            .map(ConfigurationDocument::new);
    }
}
