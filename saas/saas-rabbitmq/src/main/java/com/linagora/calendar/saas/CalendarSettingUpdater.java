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

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.LanguageSettingReader;
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage;
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
        return Mono.justOrEmpty(IncomingLanguageSetting.maybeFrom(message))
            .flatMap(incoming -> resolveUsername(message)
                .flatMap(session -> updateSetting(session, incoming)));
    }

    private Mono<Void> updateSetting(MailboxSession session, IncomingLanguageSetting incoming) {
        return fetchStoredSetting(session)
            .switchIfEmpty(Mono.just(Optional.empty()))
            .filter(stored -> shouldUpdate(stored, incoming))
            .flatMap(stored -> applyUpdate(session, incoming))
            .doOnError(error -> LOGGER.error("Error updating calendar settings for user={}", session.getUser().asString(), error));
    }

    private boolean shouldUpdate(Optional<StoredLanguageSetting> storedOpt,
                                 IncomingLanguageSetting incoming) {
        if (storedOpt.isEmpty()) {
            return SHOULD_UPDATE;
        }

        StoredLanguageSetting stored = storedOpt.get();

        // If no version stored → always apply
        if (stored.version().isEmpty()) {
            return SHOULD_UPDATE;
        }
        return incoming.version() > stored.version().get();
    }

    private Mono<Void> applyUpdate(MailboxSession session, IncomingLanguageSetting incoming) {
        ConfigurationEntry langEntry = ConfigurationEntry.of(LANGUAGE_IDENTIFIER, TextNode.valueOf(incoming.locale().getLanguage()));
        ConfigurationEntry versionEntry = ConfigurationEntry.of(LANGUAGE_VERSION_IDENTIFIER, LongNode.valueOf(incoming.version()));

        return userConfigurationDAO.persistConfiguration(Set.of(langEntry, versionEntry), session);
    }

    private Mono<MailboxSession> resolveUsername(TWPCommonSettingsMessage message) {
        return openPaaSUserDAO.retrieve(Username.of(message.payload().email()))
            .map(OpenPaaSUser::username)
            .map(sessionProvider::createSession)
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.warn("Cannot resolve username: user not found for email={}", message.payload().email());
                return Mono.empty();
            }));
    }

    private Mono<Optional<StoredLanguageSetting>> fetchStoredSetting(MailboxSession session) {
        return userConfigurationDAO.retrieveConfiguration(session)
            .collectList()
            .map(StoredLanguageSetting::maybeFrom);
    }

    record StoredLanguageSetting(Locale locale, Optional<Long> version) {

        static Optional<StoredLanguageSetting> maybeFrom(List<ConfigurationEntry> entries) {
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
                .map(versionEntry -> versionEntry.node().asLong())
                .findFirst();
        }

        static Predicate<ConfigurationEntry> configurationEntryPredicate(EntryIdentifier entryIdentifier) {
            return configurationEntry -> configurationEntry.moduleName().equals(entryIdentifier.moduleName())
                && configurationEntry.configurationKey().equals(entryIdentifier.configurationKey());
        }
    }

    record IncomingLanguageSetting(Locale locale, Long version) {
        static Optional<IncomingLanguageSetting> maybeFrom(TWPCommonSettingsMessage message) {
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