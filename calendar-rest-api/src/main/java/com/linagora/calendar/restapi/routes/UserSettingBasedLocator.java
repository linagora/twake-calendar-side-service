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

package com.linagora.calendar.restapi.routes;

import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;

import java.util.Locale;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;

import reactor.core.publisher.Mono;

public class UserSettingBasedLocator {
    public static final Logger LOGGER = LoggerFactory.getLogger(UserSettingBasedLocator.class);

    private final SettingsBasedResolver settingsBasedLocator;

    private final SimpleSessionProvider sessionProvider;

    @Inject
    @Singleton
    public UserSettingBasedLocator(ConfigurationResolver configurationResolver, SimpleSessionProvider sessionProvider) {
        this.settingsBasedLocator = SettingsBasedResolver.of(configurationResolver, Set.of(SettingsBasedResolver.LanguageSettingReader.INSTANCE));
        this.sessionProvider = sessionProvider;
    }

    public Mono<Language> getLanguage(MailboxSession mailboxSession) {
        return readLanguageFromSavedSettings(mailboxSession)
            .onErrorResume(error -> {
                LOGGER.error("Falling back to default language for session {} due to error: {}",
                    mailboxSession.getUser().asString(), error.getMessage());
                return Mono.just(Language.ENGLISH);
            })
            .defaultIfEmpty(Language.ENGLISH);
    }

    public Mono<Language> getLanguage(Username username) {
        return getLanguage(sessionProvider.createSession(username));
    }

    public Mono<Language> getLanguage(Username username, Username fallbackUsername) {
        return readLanguageFromUsername(username)
            .onErrorResume(error -> {
                LOGGER.warn("Failed to read language for user {}: {}, trying fallback user {}",
                    username.asString(), error.getMessage(), fallbackUsername.asString());
                return readLanguageFromUsername(fallbackUsername);
            })
            .switchIfEmpty(Mono.defer(() -> {
                LOGGER.warn("No language setting found for user {}, trying fallback user {}",
                    username.asString(), fallbackUsername.asString());
                return readLanguageFromUsername(fallbackUsername);
            }))
            .onErrorResume(error -> {
                LOGGER.error("Failed to read language for fallback user {}: {}, using default",
                    fallbackUsername.asString(), error.getMessage());
                return Mono.just(Language.ENGLISH);
            })
            .defaultIfEmpty(Language.ENGLISH);
    }

    private Mono<Language> readLanguageFromUsername(Username username) {
        return readLanguageFromSavedSettings(sessionProvider.createSession(username));
    }

    private Mono<Language> readLanguageFromSavedSettings(MailboxSession mailboxSession) {
        return settingsBasedLocator.readSavedSettings(mailboxSession)
            .flatMap(settings -> Mono.justOrEmpty(settings.get(LANGUAGE_IDENTIFIER, Locale.class)))
            .map(Language::new);
    }
}
