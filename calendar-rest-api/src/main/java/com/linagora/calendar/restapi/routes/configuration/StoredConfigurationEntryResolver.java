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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationEntryResolver;

import reactor.core.publisher.Flux;

public class StoredConfigurationEntryResolver implements ConfigurationEntryResolver {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static Function<RestApiConfiguration, JsonNode> defaultLanguage() {
        return configuration -> TextNode.valueOf(configuration.getDefaultLanguage());
    }

    private static Function<RestApiConfiguration, JsonNode> defaultTimezone() {
        return configuration -> {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("timeZone", TextNode.valueOf(configuration.getDefaultTimezone()));
            objectNode.put("use24hourFormat", configuration.isDefaultUse24hFormat());
            return objectNode;
        };
    }

    private static Function<RestApiConfiguration, JsonNode> defaultBusinessHours() {
        return Throwing.function(RestApiConfiguration::getDefaultBusinessHours);
    }

    private static final Table<ModuleName, ConfigurationKey, Function<RestApiConfiguration, JsonNode>> TABLE = ImmutableTable.<ModuleName, ConfigurationKey, Function<RestApiConfiguration, JsonNode>>builder()
        .put(new ModuleName("core"), new ConfigurationKey("language"), defaultLanguage())
        .put(new ModuleName("core"), new ConfigurationKey("datetime"), defaultTimezone())
        .put(new ModuleName("core"), new ConfigurationKey("businessHours"), defaultBusinessHours())
        .put(new ModuleName("linagora.esn.calendar"), new ConfigurationKey("workingDays"), any -> NullNode.getInstance())
        .put(new ModuleName("linagora.esn.calendar"), new ConfigurationKey("hideDeclinedEvents"), any -> NullNode.getInstance())
        .put(new ModuleName("calendar"), new ConfigurationKey("alarmEmails"), any -> NullNode.getInstance())

        .build();

    public static final ImmutableSet<EntryIdentifier> KEYS = TABLE.cellSet()
        .stream()
        .map(cell -> new EntryIdentifier(cell.getRowKey(), cell.getColumnKey()))
        .collect(ImmutableSet.toImmutableSet());

    private final RestApiConfiguration configuration;
    private final UserConfigurationDAO userConfigurationDAO;

    @Inject
    public StoredConfigurationEntryResolver(RestApiConfiguration configuration, UserConfigurationDAO userConfigurationDAO) {
        this.configuration = configuration;
        this.userConfigurationDAO = userConfigurationDAO;
    }

    @Override
    public Flux<ConfigurationEntry> resolve(Set<EntryIdentifier> ids, MailboxSession session) {
        return userConfigurationDAO.retrieveConfiguration(session)
            .collectMap(e -> new EntryIdentifier(e.moduleName(), e.configurationKey()), e -> e)
            .flatMapMany(userConfiguration -> Flux.fromIterable(Sets.intersection(ids, KEYS))
                .map(resolveSpecificEntry(userConfiguration)));
    }

    private Function<EntryIdentifier, ConfigurationEntry> resolveSpecificEntry(Map<EntryIdentifier, ConfigurationEntry> userConfigurationEntries) {
        return id -> Optional.ofNullable(userConfigurationEntries.get(id))
            .orElseGet(() -> new ConfigurationEntry(id.moduleName(), id.configurationKey(),
                TABLE.get(id.moduleName(), id.configurationKey()).apply(configuration)));
    }

    @Override
    public Set<EntryIdentifier> entryIdentifiers() {
        return KEYS;
    }
}
