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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.exception.DomainNotFoundException;

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
        SettingsBasedResolver.ResolvedSettings resolved = settingsBasedResolver.resolveOrDefault(session)
            .block();
        assertThat(resolved.locale()).isEqualTo(Locale.of("fr"));
        assertThat(resolved.zoneId()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void shouldReturnDefaultWhenNoConfigFound() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create(); // empty table
        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);

        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver settingsBasedResolver = getSettingsBasedResolver(configurationResolver);

        assertThat(settingsBasedResolver.resolveOrDefault(session).block()).isEqualTo(SettingsBasedResolver.ResolvedSettings.DEFAULT);
    }

    @Test
    void shouldReturnDefaultWhenOnlyLocalePresent() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create();
        table.put(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey(),
            JsonNodeFactory.instance.textNode("fr"));

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.resolveOrDefault(session).block();
        assertThat(result).isEqualTo(SettingsBasedResolver.ResolvedSettings.DEFAULT);
    }

    @Test
    void shouldReturnDefaultWhenOnlyZoneIdPresent() {
        Table<ModuleName, ConfigurationKey, JsonNode> table = HashBasedTable.create();

        ObjectNode timezoneNode = JsonNodeFactory.instance.objectNode();
        timezoneNode.put("timeZone", "Asia/Ho_Chi_Minh");
        table.put(TIMEZONE_IDENTIFIER.moduleName(), TIMEZONE_IDENTIFIER.configurationKey(), timezoneNode);

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.eq(session)))
            .thenReturn(Mono.just(new ConfigurationDocument(table)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.resolveOrDefault(session).block();
        assertThat(result).isEqualTo(SettingsBasedResolver.ResolvedSettings.DEFAULT);
    }

    @Test
    void timezoneReaderShouldReturnEmptyWhenInvalidZoneId() {
        JsonNode invalidZone = JsonNodeFactory.instance.objectNode().put("timeZone", "invalid/zone");

        Optional<ZoneId> result = SettingsBasedResolver.TimeZoneSettingReader.INSTANCE.parse(invalidZone);
        assertThat(result).isEmpty();
    }

    @Test
    void twoUsersShouldFallbackToSecondUserSettingsWhenFirstUserErrors() {
        Username externalUser = Username.of("external@remote.com");
        Username senderUser = Username.of("sender@local.com");

        Table<ModuleName, ConfigurationKey, JsonNode> senderTable = HashBasedTable.create();
        senderTable.put(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey(), JsonNodeFactory.instance.textNode("fr"));
        ObjectNode timezoneNode = JsonNodeFactory.instance.objectNode();
        timezoneNode.put("timeZone", "Europe/Paris");
        senderTable.put(TIMEZONE_IDENTIFIER.moduleName(), TIMEZONE_IDENTIFIER.configurationKey(), timezoneNode);

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(),
                Mockito.argThat(s -> s != null && s.getUser().equals(externalUser))))
            .thenReturn(Mono.error(new DomainNotFoundException(Domain.of("remote.com"))));
        Mockito.when(configurationResolver.resolve(Mockito.anySet(),
                Mockito.argThat(s -> s != null && s.getUser().equals(senderUser))))
            .thenReturn(Mono.just(new ConfigurationDocument(senderTable)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.resolveOrDefault(externalUser, senderUser).block();
        assertThat(result.locale()).isEqualTo(Locale.of("fr"));
        assertThat(result.zoneId()).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void twoUsersShouldUseFirstUserSettingsWhenAvailable() {
        Username recipientUser = Username.of("recipient@local.com");
        Username senderUser = Username.of("sender@local.com");

        Table<ModuleName, ConfigurationKey, JsonNode> recipientTable = HashBasedTable.create();
        recipientTable.put(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey(), JsonNodeFactory.instance.textNode("vi"));
        ObjectNode timezoneNode = JsonNodeFactory.instance.objectNode();
        timezoneNode.put("timeZone", "Asia/Ho_Chi_Minh");
        recipientTable.put(TIMEZONE_IDENTIFIER.moduleName(), TIMEZONE_IDENTIFIER.configurationKey(), timezoneNode);

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(),
                Mockito.argThat(s -> s != null && s.getUser().equals(recipientUser))))
            .thenReturn(Mono.just(new ConfigurationDocument(recipientTable)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.resolveOrDefault(recipientUser, senderUser).block();
        assertThat(result.locale()).isEqualTo(Locale.of("vi"));
        assertThat(result.zoneId()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void twoUsersShouldReturnDefaultWhenBothUsersError() {
        Username externalUser = Username.of("external@remote.com");
        Username unknownSender = Username.of("unknown@other.com");

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(), Mockito.any(MailboxSession.class)))
            .thenReturn(Mono.error(new DomainNotFoundException(Domain.of("notfound"))));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.resolveOrDefault(externalUser, unknownSender).block();
        assertThat(result).isEqualTo(SettingsBasedResolver.ResolvedSettings.DEFAULT);
    }

    @Test
    void twoUsersShouldFallbackToSecondUserWhenFirstUserReturnsEmpty() {
        Username recipientUser = Username.of("recipient@local.com");
        Username senderUser = Username.of("sender@local.com");

        Table<ModuleName, ConfigurationKey, JsonNode> emptyTable = HashBasedTable.create();

        Table<ModuleName, ConfigurationKey, JsonNode> senderTable = HashBasedTable.create();
        senderTable.put(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey(), JsonNodeFactory.instance.textNode("de"));
        ObjectNode timezoneNode = JsonNodeFactory.instance.objectNode();
        timezoneNode.put("timeZone", "Europe/Berlin");
        senderTable.put(TIMEZONE_IDENTIFIER.moduleName(), TIMEZONE_IDENTIFIER.configurationKey(), timezoneNode);

        ConfigurationResolver configurationResolver = Mockito.mock(ConfigurationResolver.class);
        Mockito.when(configurationResolver.resolve(Mockito.anySet(),
                Mockito.argThat(s -> s != null && s.getUser().equals(recipientUser))))
            .thenReturn(Mono.just(new ConfigurationDocument(emptyTable)));
        Mockito.when(configurationResolver.resolve(Mockito.anySet(),
                Mockito.argThat(s -> s != null && s.getUser().equals(senderUser))))
            .thenReturn(Mono.just(new ConfigurationDocument(senderTable)));

        SettingsBasedResolver resolver = getSettingsBasedResolver(configurationResolver);

        SettingsBasedResolver.ResolvedSettings result = resolver.resolveOrDefault(recipientUser, senderUser).block();
        assertThat(result.locale()).isEqualTo(Locale.of("de"));
        assertThat(result.zoneId()).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    private SettingsBasedResolver getSettingsBasedResolver(ConfigurationResolver configurationResolver) {
        return SettingsBasedResolver.of(configurationResolver, new SimpleSessionProvider(new RandomMailboxSessionIdGenerator()),
            Set.of(SettingsBasedResolver.LanguageSettingReader.INSTANCE, SettingsBasedResolver.TimeZoneSettingReader.INSTANCE));
    }
}
