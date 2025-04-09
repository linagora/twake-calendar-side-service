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
import java.util.function.Supplier;

import org.apache.james.mailbox.MailboxSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;

import reactor.core.publisher.Flux;

public class ConstantConfigurationEntryResolver implements ConfigurationEntryResolver {
    private static Table<ModuleName, ConfigurationKey, Supplier<JsonNode>> TABLE = ImmutableTable.<ModuleName, ConfigurationKey, Supplier<JsonNode>>builder()
        .put(new ModuleName("core"), new ConfigurationKey("allowDomainAdminToManageUserEmails"), NullNode::getInstance)
        .put(new ModuleName("core"), new ConfigurationKey("homePage"), NullNode::getInstance)
        .build();
    public static final ImmutableSet<EntryIdentifier> KEYS = TABLE.cellSet()
        .stream()
        .map(cell -> new EntryIdentifier(cell.getRowKey(), cell.getColumnKey()))
        .collect(ImmutableSet.toImmutableSet());

    @Override
    public Flux<ConfigurationEntry> resolve(Set<EntryIdentifier> ids, MailboxSession session) {
        return Flux.fromIterable(Sets.intersection(ids, KEYS))
            .map(id -> new ConfigurationEntry(id.moduleName(), id.configurationKey(), TABLE.get(id.moduleName(), id.configurationKey()).get()));
    }

    @Override
    public Set<EntryIdentifier> entryIdentifiers() {
        return KEYS;
    }
}
