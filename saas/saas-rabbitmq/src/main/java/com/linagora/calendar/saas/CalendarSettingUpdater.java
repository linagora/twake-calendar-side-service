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

import static com.linagora.calendar.storage.configuration.ConfigurationEntryUtils.EMPTY_CONFIGURATION_ENTRIES;
import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationEntryUtils;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.LanguageSettingReader;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage;
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage.IncomingLanguageSetting;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsUpdater;

import reactor.core.publisher.Mono;

public class CalendarSettingUpdater implements TWPSettingsUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarSettingUpdater.class);

    public static final EntryIdentifier LANGUAGE_VERSION_IDENTIFIER =
        new EntryIdentifier(new ModuleName("core"), new ConfigurationKey("language_version"));

    private static final boolean SHOULD_UPDATE = true;

    private final UserConfigurationDAO userConfigurationDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final SimpleSessionProvider sessionProvider;

    public CalendarSettingUpdater(UserConfigurationDAO userConfigurationDAO,
                                  OpenPaaSUserDAO openPaaSUserDAO,
                                  SimpleSessionProvider sessionProvider) {
        this.userConfigurationDAO = userConfigurationDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.sessionProvider = sessionProvider;
    }

    @Override
    public Mono<Void> updateSettings(TWPCommonSettingsMessage message) {
        return message.languageSettings()
            .map(incomingLanguageSetting -> resolveUsername(message)
                .flatMap(session -> updateSetting(session, incomingLanguageSetting))
                .then())
            .orElse(Mono.empty())
            .doOnError(error -> LOGGER.error("Error occurred while updating calendar settings for user={} ", message.payload().email(), error));
    }

    private Mono<Void> updateSetting(MailboxSession session, IncomingLanguageSetting incomingLanguageSetting) {
        return getConfigurationEntries(session)
            .filter(configurationEntries -> shouldUpdate(StoredLanguageSetting.fromConfigurationEntries(configurationEntries), incomingLanguageSetting))
            .flatMap(configurationEntries -> applyUpdate(session, incomingLanguageSetting, configurationEntries));
    }

    private Mono<Set<ConfigurationEntry>> getConfigurationEntries(MailboxSession session) {
        return userConfigurationDAO.retrieveConfiguration(session)
            .collect(Collectors.toUnmodifiableSet())
            .switchIfEmpty(Mono.just(EMPTY_CONFIGURATION_ENTRIES));
    }

    private boolean shouldUpdate(Optional<StoredLanguageSetting> storedLanguageSetting,
                                 IncomingLanguageSetting incomingLanguageSetting) {
        return storedLanguageSetting
            .map(StoredLanguageSetting::version)
            .flatMap(Functions.identity())
            .map(storedVersion -> incomingLanguageSetting.version() > storedVersion)
            .orElse(SHOULD_UPDATE);
    }

    private Mono<Void> applyUpdate(MailboxSession session, IncomingLanguageSetting incomingLanguageSetting,
                                   Set<ConfigurationEntry> existingConfigurationEntries) {
        Locale incomingLocale = incomingLanguageSetting.toLocale();
        ConfigurationEntry languageEntry = ConfigurationEntry.of(LANGUAGE_IDENTIFIER, TextNode.valueOf(incomingLocale.getLanguage()));
        ConfigurationEntry versionEntry = ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(incomingLanguageSetting.version()));
        Set<ConfigurationEntry> newIncomingEntries = Set.of(languageEntry, versionEntry);
        Set<ConfigurationEntry> mergedConfiguration = ConfigurationEntryUtils.mergeIncomingWithExistingConfiguration(newIncomingEntries, existingConfigurationEntries);
        return userConfigurationDAO.persistConfiguration(mergedConfiguration, session);
    }

    private Mono<MailboxSession> resolveUsername(TWPCommonSettingsMessage message) {
        Username username = Username.of(message.payload().email());
        return openPaaSUserDAO.retrieve(username)
            .map(OpenPaaSUser::username)
            .map(sessionProvider::createSession)
            .switchIfEmpty(Mono.defer(() -> Mono.error(new UserNotFoundException(username))));
    }

    record StoredLanguageSetting(Locale locale, Optional<Long> version) {

        static Optional<StoredLanguageSetting> fromConfigurationEntries(Set<ConfigurationEntry> entries) {
            return findLocale(entries)
                .map(locale -> new StoredLanguageSetting(locale, findLanguageVersion(entries)));
        }

        static Optional<Locale> findLocale(Set<ConfigurationEntry> entries) {
            return entries.stream()
                .filter(configurationEntryPredicate(LANGUAGE_IDENTIFIER))
                .flatMap(languageEntry -> LanguageSettingReader.INSTANCE.parse(languageEntry.node()).stream())
                .findFirst();
        }

        static Optional<Long> findLanguageVersion(Set<ConfigurationEntry> entries) {
            return entries.stream()
                .filter(configurationEntryPredicate(LANGUAGE_VERSION_IDENTIFIER))
                .map(versionEntry -> {
                    Preconditions.checkArgument(versionEntry.node().isNumber(), "version is not number");
                    return versionEntry.node().asLong();
                })
                .findFirst();
        }

        static Predicate<ConfigurationEntry> configurationEntryPredicate(EntryIdentifier entryIdentifier) {
            return configurationEntry -> configurationEntry.moduleName().equals(entryIdentifier.moduleName())
                && configurationEntry.configurationKey().equals(entryIdentifier.configurationKey());
        }
    }
}