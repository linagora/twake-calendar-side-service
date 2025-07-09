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

import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.james.mailbox.MailboxSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.ModuleName;

import reactor.core.publisher.Mono;

public class SettingsBasedResolverTest {
    private static final MailboxSession session = Mockito.mock(MailboxSession.class);

    @Test
    void shouldReturnResolvedSettingsWhenConfigExists() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create();
        table.put(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey(), JsonNodeFactory.instance.textNode("fr"));
        ObjectNode timezoneNode = JsonNodeFactory.instance.objectNode();
        timezoneNode.put("timeZone", "Asia/Ho_Chi_Minh");
        table.put(TIMEZONE_IDENTIFIER.moduleName(), TIMEZONE_IDENTIFIER.configurationKey(), timezoneNode);

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);

        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver settingsBasedResolver = getSettingsBasedResolver(configurationResolver);
        SettingsBasedResolver.ResolvedSettings resolved = settingsBasedResolver.readSavedSettings(session)
            .block();
        assertThat(resolved.locale()).isEqualTo(Locale.of("fr"));
        assertThat(resolved.zoneId()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void shouldReturnEmptyWhenNoConfigFound() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create(); // empty table
        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);

        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver settingsBasedResolver = getSettingsBasedResolver(configurationResolver);

        assertThat(settingsBasedResolver.readSavedSettings(session).blockOptional()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenOnlyLocalePresent() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create();
        table.put(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey(),
            JsonNodeFactory.instance.textNode("fr"));

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.readSavedSettings(session).block();
        assertThat(result).isNull(); // Because zoneId is missing
    }

    @Test
    void shouldReturnEmptyWhenOnlyZoneIdPresent() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create();

        ObjectNode timezoneNode = JsonNodeFactory.instance.objectNode();
        timezoneNode.put("timeZone", "Asia/Ho_Chi_Minh");
        table.put(TIMEZONE_IDENTIFIER.moduleName(), TIMEZONE_IDENTIFIER.configurationKey(), timezoneNode);

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.readSavedSettings(session).block();
        assertThat(result).isNull(); // Because locale is missing
    }



    @Test
    void timezoneReaderShouldReturnEmptyWhenInvalidZoneId() {
        JsonNode invalidZone = JsonNodeFactory.instance.objectNode().put("timeZone", "invalid/zone");

        Optional<ZoneId> result = SettingsBasedResolver.TimeZoneSettingReader.INSTANCE.parse(invalidZone);
        assertThat(result).isEmpty();
    }

    private SettingsBasedResolver getSettingsBasedResolver(ConfigurationResolver configurationResolver) {
        return SettingsBasedResolver.of(configurationResolver,
            Set.of(SettingsBasedResolver.LanguageSettingReader.INSTANCE, SettingsBasedResolver.TimeZoneSettingReader.INSTANCE));
    }
}
