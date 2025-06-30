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

import java.util.Locale;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

public class SettingsBasedLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsBasedLocator.class);

    private final ConfigurationResolver configurationResolver;

    @Inject
    @Singleton
    public SettingsBasedLocator(ConfigurationResolver configurationResolver) {
        this.configurationResolver = configurationResolver;
    }

    public Mono<Locale> getLanguageUserSetting(MailboxSession mailboxSession) {
        Locale fallbackLanguage = Locale.ENGLISH;

        return configurationResolver.resolve(Set.of(LANGUAGE_IDENTIFIER), mailboxSession)
            .map(ConfigurationDocument::table)
            .filter(configTable -> configTable.contains(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey()))
            .mapNotNull(configTable -> configTable.get(LANGUAGE_IDENTIFIER.moduleName(), LANGUAGE_IDENTIFIER.configurationKey()))
            .map(jsonNode -> Locale.of(jsonNode.asText()))
            .onErrorResume(error -> {
                LOGGER.error("Error resolving user language setting for {}, will use fallback language: {}",
                    mailboxSession.getUser(), fallbackLanguage.getLanguage(), error);
                return Mono.just(fallbackLanguage);
            })
            .defaultIfEmpty(fallbackLanguage);
    }
}
