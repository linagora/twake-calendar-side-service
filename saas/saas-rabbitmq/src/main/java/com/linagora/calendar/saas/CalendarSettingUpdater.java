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

import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;
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
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.LanguageSettingReader;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsUpdater;

import reactor.core.publisher.Mono;

public class CalendarSettingUpdater implements TWPSettingsUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarSettingUpdater.class);

    public static final EntryIdentifier LANGUAGE_VERSION_IDENTIFIER =
        new EntryIdentifier(new ModuleName("core"), new ConfigurationKey("language_version"));

    private static final boolean SHOULD_UPDATE = true;
    private static final List<ConfigurationEntry> EMPTY_ENTRIES = List.of();

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
        return Mono.justOrEmpty(IncomingLanguageSetting.extractFromMessage(message))
            .flatMap(incoming -> resolveUsername(message)
                .flatMap(session -> updateSetting(session, incoming)));
    }

    private Mono<Void> updateSetting(MailboxSession session, IncomingLanguageSetting incoming) {
        return userConfigurationDAO.retrieveConfiguration(session)
            .collectList()
            .switchIfEmpty(Mono.just(EMPTY_ENTRIES))
            .filter(configurationEntries -> shouldUpdate(StoredLanguageSetting.fromConfigurationEntries(configurationEntries), incoming))
            .flatMap(configurationEntries -> applyUpdate(session, incoming, configurationEntries))
            .doOnError(error -> LOGGER.error("Error updating calendar settings for user={}", session.getUser().asString(), error));
    }

    private boolean shouldUpdate(Optional<StoredLanguageSetting> storedLanguageSetting,
                                 IncomingLanguageSetting incoming) {
        return storedLanguageSetting
            .map(StoredLanguageSetting::version)
            .flatMap(Functions.identity())
            .map(storedVersion -> incoming.version() > storedVersion)
            .orElse(SHOULD_UPDATE);
    }

    private Mono<Void> applyUpdate(MailboxSession session, IncomingLanguageSetting incoming, List<ConfigurationEntry> existingConfigurationEntries) {
        ConfigurationEntry languageEntry = ConfigurationEntry.of(LANGUAGE_IDENTIFIER, TextNode.valueOf(incoming.locale().getLanguage()));
        ConfigurationEntry versionEntry = ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(incoming.version()));
        Set<ConfigurationEntry> newIncomingEntries = Set.of(languageEntry, versionEntry);
        Set<ConfigurationEntry> mergedConfiguration = mergeIncomingWithExistingConfiguration(newIncomingEntries, existingConfigurationEntries);
        return userConfigurationDAO.persistConfiguration(mergedConfiguration, session);
    }

    private Set<ConfigurationEntry> mergeIncomingWithExistingConfiguration(Set<ConfigurationEntry> incoming, List<ConfigurationEntry> stored) {
        return Stream.concat(stored.stream(), incoming.stream())
            .collect(Collectors.toMap(entry -> Pair.of(entry.moduleName(), entry.configurationKey()),
                entry -> entry,
                (oldVal, newVal) -> newVal))
            .values()
            .stream()
            .collect(Collectors.toUnmodifiableSet());
    }

    private Mono<MailboxSession> resolveUsername(TWPCommonSettingsMessage message) {
        Username username = Username.of(message.payload().email());
        return openPaaSUserDAO.retrieve(username)
            .map(OpenPaaSUser::username)
            .map(sessionProvider::createSession)
            .switchIfEmpty(Mono.defer(() -> Mono.error(new UserNotFoundException(username))));
    }

    record StoredLanguageSetting(Locale locale, Optional<Long> version) {

        static Optional<StoredLanguageSetting> fromConfigurationEntries(List<ConfigurationEntry> entries) {
            return findLocale(entries)
                .map(locale -> new StoredLanguageSetting(locale, findLanguageVersion(entries)));
        }

        static Optional<Locale> findLocale(List<ConfigurationEntry> entries) {
            return entries.stream()
                .filter(configurationEntryPredicate(LANGUAGE_IDENTIFIER))
                .flatMap(languageEntry -> LanguageSettingReader.INSTANCE.parse(languageEntry.node()).stream())
                .findFirst();
        }

        static Optional<Long> findLanguageVersion(List<ConfigurationEntry> entries) {
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

    record IncomingLanguageSetting(Locale locale, Long version) {
        static Optional<IncomingLanguageSetting> extractFromMessage(TWPCommonSettingsMessage message) {
            return message.payload().language()
                .flatMap(lang -> {
                    Locale locale = Locale.forLanguageTag(lang);
                    if (locale.getLanguage().isEmpty()) {
                        LOGGER.warn("Skip updating: invalid language tag '{}' for user email={}", lang, message.payload().email());
                        return Optional.empty();
                    }
                    return Optional.of(new IncomingLanguageSetting(locale, message.version()));
                });
        }
    }
}