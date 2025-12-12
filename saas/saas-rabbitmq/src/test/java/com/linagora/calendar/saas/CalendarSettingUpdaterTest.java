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

package com.linagora.calendar.saas;

import static com.linagora.calendar.saas.CalendarSettingUpdater.LANGUAGE_VERSION_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.james.core.Username;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.MemoryUserConfigurationDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage;

public class CalendarSettingUpdaterTest {

    private CalendarSettingUpdater testee;
    private OpenPaaSUserDAO openPaaSUserDAO;
    private UserConfigurationDAO userConfigurationDAO;
    private final SimpleSessionProvider sessionProvider = new SimpleSessionProvider(new RandomMailboxSessionIdGenerator());
    private final Username USER = Username.of("tung@domain.tld");


    @BeforeEach
    void setup() {
        openPaaSUserDAO = new MemoryOpenPaaSUserDAO();
        userConfigurationDAO = new MemoryUserConfigurationDAO(openPaaSUserDAO);
        testee = new CalendarSettingUpdater(userConfigurationDAO, openPaaSUserDAO, sessionProvider);
        openPaaSUserDAO.add(USER).block();
    }

    @Test
    void shouldUpdateWhenStoredVersionIsEmpty() {
        // Given
        ConfigurationEntry existingLanguage = ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("fr"));
        userConfigurationDAO.persistConfiguration(Set.of(existingLanguage), sessionProvider.createSession(USER)).block();

        // When
        testee.updateSettings(newMessage(5, "en")).block();

        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .isNotNull()
            .hasSize(2)
            .containsExactlyInAnyOrder(ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("en")),
                ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(5)));
    }

    @Test
    void shouldUpdateWhenIncomingVersionIsHigher() {
        // Given: existing language + version=1
        ConfigurationEntry existingLanguage = ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("fr"));
        ConfigurationEntry existingVersion = ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(1));

        userConfigurationDAO.persistConfiguration(Set.of(existingLanguage, existingVersion), sessionProvider.createSession(USER)).block();

        // When
        testee.updateSettings(newMessage(2, "en")).block();

        // Then
        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .isNotNull()
            .hasSize(2)
            .containsExactlyInAnyOrder(ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("en")),
                ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(2)));
    }

    @Test
    void shouldUpdateWhenStoredSettingIsEmptyOptionalCase() {
        // Given: No existing config stored → Optional.empty()

        // When
        testee.updateSettings(newMessage(2, "en")).block();

        // Then
        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .isNotNull()
            .hasSize(2)
            .containsExactlyInAnyOrder(ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("en")),
                ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(2)));
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 1})
    void shouldSkipUpdateWhenIncomingVersionIsNotHigher(long version) {
        // Given
        ConfigurationEntry existingLanguage = ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("fr"));
        ConfigurationEntry existingVersion = ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(2));
        userConfigurationDAO.persistConfiguration(Set.of(existingLanguage, existingVersion), sessionProvider.createSession(USER)).block();

        // When
        testee.updateSettings(newMessage(version, "en")).block();

        List<ConfigurationEntry> entriesEqual = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entriesEqual)
            .isNotNull()
            .hasSize(2)
            .containsExactlyInAnyOrder(existingLanguage, existingVersion);
    }

    @Test
    void shouldNotPersistWhenPayloadHasNoLanguage() {
        TWPCommonSettingsMessage message = new TWPCommonSettingsMessage("source", "nick", "req-1",
            System.currentTimeMillis(), 10L, new TWPCommonSettingsMessage.Payload(USER.asString(), Optional.empty()));

        testee.updateSettings(message).block();

        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .isNotNull()
            .isEmpty();
    }

    @Test
    void shouldThrowWhenUserNotFound() {
        String unknownUser = UUID.randomUUID() + "@domain.tld";
        TWPCommonSettingsMessage message = new TWPCommonSettingsMessage("source", "nick", "req-404",
            System.currentTimeMillis(), 3L,
            new TWPCommonSettingsMessage.Payload(unknownUser, Optional.of("en")));

        assertThatThrownBy(() -> testee.updateSettings(message).block())
            .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void shouldHandleSequentialUpdatesCorrectly() {
        // v1
        testee.updateSettings(newMessage(1, "en")).block();

        // v2 → update
        testee.updateSettings(newMessage(2, "fr")).block();

        // v1 → skip
        testee.updateSettings(newMessage(1, "vi")).block();

        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .hasSize(2)
            .containsExactlyInAnyOrder(ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("fr")),
                ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(2)));
    }

    @Test
    void shouldNotUpdateWhenIncorrectLanguage() {
        // Given an invalid language tag
        // Ensure clean state for USER
        TWPCommonSettingsMessage message = new TWPCommonSettingsMessage("source", "nick", "req-invalid-lang",
            System.currentTimeMillis(), 1L,
            new TWPCommonSettingsMessage.Payload(USER.asString(), Optional.of("??invalid??")));

        // When
        testee.updateSettings(message).block();

        // Then: configuration should NOT be persisted
        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .isNotNull()
            .isEmpty();
    }

    @Test
    void shouldSupportLocaleFormatVariants() {
        // Given: persist update with language "en-US" and version 7
        long version = 7L;
        String localeVariant = "en-US";
        testee.updateSettings(newMessage(version, localeVariant)).block();

        // When: retrieve configuration
        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        // Then: language should be stored as "en-US" and version as 7
        assertThat(entries)
            .isNotNull()
            .hasSize(2)
            .containsExactlyInAnyOrder(ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("en")),
                ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(version)));
    }

    @Test
    void updatingAliceShouldNotAffectBob() {
        // Given
        Username alice = Username.of("alice@domain.tld");
        Username bob = Username.of("bob@domain.tld");

        openPaaSUserDAO.add(alice).block();
        openPaaSUserDAO.add(bob).block();

        // Update Alice only
        testee.updateSettings(new TWPCommonSettingsMessage("src", "nick", "req1", System.currentTimeMillis(), 1L,
            new TWPCommonSettingsMessage.Payload(alice.asString(), Optional.of("en")))).block();

        // Verify Alice updated
        List<ConfigurationEntry> aliceEntries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(alice))
            .collectList()
            .block();

        assertThat(aliceEntries)
            .hasSize(2);

        // Verify Bob remains empty
        List<ConfigurationEntry> bobEntries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(bob))
            .collectList()
            .block();

        assertThat(bobEntries).isEmpty();
    }

    @Test
    void shouldUpdateLanguageWithoutAffectingExistingSettings() {
        // Given: existing timezone + existing language + version
        ConfigurationEntry timezone = ConfigurationEntry.of(TIMEZONE_IDENTIFIER, TextNode.valueOf("Asia/Ho_Chi_Minh"));
        ConfigurationEntry language = ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("fr"));
        ConfigurationEntry version = ConfigurationEntry.of(CalendarSettingUpdater.LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(1));

        userConfigurationDAO.persistConfiguration(Set.of(timezone, language, version), sessionProvider.createSession(USER)).block();

        // When: update language to "en"
        testee.updateSettings(newMessage(2, "en")).block();

        // Then: timezone MUST remain unchanged
        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .hasSize(3)
            .containsExactlyInAnyOrder(timezone,
                ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("en")),
                ConfigurationEntry.of(CalendarSettingUpdater.LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(2)));
    }

    @Test
    void shouldAddLanguageWithoutAffectingExistingSettings() {
        // Given: user has unrelated setting (timezone), but NO language setting yet
        ConfigurationEntry timezone = ConfigurationEntry.of(TIMEZONE_IDENTIFIER, TextNode.valueOf("Asia/Ho_Chi_Minh"));

        userConfigurationDAO.persistConfiguration(Set.of(timezone), sessionProvider.createSession(USER)).block();

        // When: update language to "en" with version 2
        testee.updateSettings(newMessage(2, "en")).block();

        // Then: timezone MUST remain unchanged, and language + version added
        List<ConfigurationEntry> entries = userConfigurationDAO
            .retrieveConfiguration(sessionProvider.createSession(USER))
            .collectList()
            .block();

        assertThat(entries)
            .hasSize(3)
            .containsExactlyInAnyOrder(timezone,
                ConfigurationEntry.of(EntryIdentifier.LANGUAGE_IDENTIFIER, TextNode.valueOf("en")),
                ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(2)));
    }

    private TWPCommonSettingsMessage newMessage(long version, String language) {
        return new TWPCommonSettingsMessage("source", "nick", "req-1",
            System.currentTimeMillis(), version,
            new TWPCommonSettingsMessage.Payload(USER.asString(), Optional.ofNullable(language)));
    }
}