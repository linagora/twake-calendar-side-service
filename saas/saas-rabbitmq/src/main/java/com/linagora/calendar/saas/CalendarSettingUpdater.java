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

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import com.linagora.tmail.saas.rabbitmq.settings.TWPCommonSettingsMessage;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsUpdater;

import reactor.core.publisher.Mono;

public class CalendarSettingUpdater implements TWPSettingsUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarSettingUpdater.class);

    // Version of the TWP settings last applied. Historically only tracked the language, hence its stored key.
    public static final EntryIdentifier TWP_SETTINGS_VERSION_IDENTIFIER =
        new EntryIdentifier(new ModuleName("core"), new ConfigurationKey("language_version"));
    public static final EntryIdentifier THEME_IDENTIFIER =
        new EntryIdentifier(new ModuleName("core"), new ConfigurationKey("theme"));

    private static final boolean SHOULD_UPDATE = true;

    private final UserConfigurationDAO userConfigurationDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final SaaSUserProvisioner userProvisioner;
    private final SimpleSessionProvider sessionProvider;

    public CalendarSettingUpdater(UserConfigurationDAO userConfigurationDAO,
                                  OpenPaaSUserDAO openPaaSUserDAO,
                                  SaaSUserProvisioner userProvisioner,
                                  SimpleSessionProvider sessionProvider) {
        this.userConfigurationDAO = userConfigurationDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.userProvisioner = userProvisioner;
        this.sessionProvider = sessionProvider;
    }

    @Override
    public Mono<Void> updateSettings(TWPCommonSettingsMessage message) {
        Username username = Username.of(message.payload().email());
        return resolveUser(username)
            .flatMap(user -> updateSettingsIfPresent(user, message))
            .doOnError(error -> LOGGER.error("Error occurred while updating calendar settings for user={} ", message.payload().email(), error));
    }

    private Mono<Void> updateSettingsIfPresent(OpenPaaSUser user, TWPCommonSettingsMessage message) {
        Set<ConfigurationEntry> incomingEntries = incomingEntries(message);
        if (incomingEntries.isEmpty()) {
            return Mono.empty();
        }
        return updateSettings(sessionProvider.createSession(user.username()), incomingEntries, message.version());
    }

    private Set<ConfigurationEntry> incomingEntries(TWPCommonSettingsMessage message) {
        return Stream.of(
                message.languageSettings()
                    .map(languageSetting -> ConfigurationEntry.of(LANGUAGE_IDENTIFIER, TextNode.valueOf(languageSetting.toLocale().getLanguage()))),
                message.payload().theme()
                    .map(theme -> ConfigurationEntry.of(THEME_IDENTIFIER, TextNode.valueOf(theme))))
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableSet());
    }

    private Mono<Void> updateSettings(MailboxSession session, Set<ConfigurationEntry> incomingEntries, long incomingVersion) {
        return getConfigurationEntries(session)
            .filter(configurationEntries -> shouldUpdate(findStoredVersion(configurationEntries), incomingVersion))
            .flatMap(configurationEntries -> applyUpdate(session, incomingEntries, incomingVersion, configurationEntries));
    }

    private Mono<Set<ConfigurationEntry>> getConfigurationEntries(MailboxSession session) {
        return userConfigurationDAO.retrieveConfiguration(session)
            .collect(Collectors.toUnmodifiableSet())
            .switchIfEmpty(Mono.just(EMPTY_CONFIGURATION_ENTRIES));
    }

    private boolean shouldUpdate(Optional<Long> storedVersion, long incomingVersion) {
        return storedVersion
            .map(version -> incomingVersion > version)
            .orElse(SHOULD_UPDATE);
    }

    private Mono<Void> applyUpdate(MailboxSession session, Set<ConfigurationEntry> incomingEntries, long incomingVersion,
                                   Set<ConfigurationEntry> existingConfigurationEntries) {
        ConfigurationEntry versionEntry = ConfigurationEntry.of(TWP_SETTINGS_VERSION_IDENTIFIER, LongNode.valueOf(incomingVersion));
        Set<ConfigurationEntry> newIncomingEntries = Stream.concat(incomingEntries.stream(), Stream.of(versionEntry))
            .collect(Collectors.toUnmodifiableSet());
        Set<ConfigurationEntry> mergedConfiguration = ConfigurationEntryUtils.mergeIncomingWithExistingConfiguration(newIncomingEntries, existingConfigurationEntries);
        return userConfigurationDAO.persistConfiguration(mergedConfiguration, session);
    }

    private Mono<OpenPaaSUser> resolveUser(Username username) {
        return openPaaSUserDAO.retrieve(username)
            .flatMap(userProvisioner::reimportFromDirectory)
            .switchIfEmpty(Mono.defer(() -> userProvisioner.provisionUser(username)));
    }

    private static Optional<Long> findStoredVersion(Set<ConfigurationEntry> entries) {
        return entries.stream()
            .filter(entry -> entry.moduleName().equals(TWP_SETTINGS_VERSION_IDENTIFIER.moduleName())
                && entry.configurationKey().equals(TWP_SETTINGS_VERSION_IDENTIFIER.configurationKey()))
            .map(versionEntry -> {
                Preconditions.checkArgument(versionEntry.node().isNumber(), "version is not number");
                return versionEntry.node().asLong();
            })
            .findFirst();
    }
}
