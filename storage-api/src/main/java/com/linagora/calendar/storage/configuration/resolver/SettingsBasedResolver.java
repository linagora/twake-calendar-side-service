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
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ALARM_SETTING_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ENABLE_ALARM;
import static com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.exception.DomainNotFoundException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SettingsBasedResolver {

    interface SettingReader<T> {
        EntryIdentifier identifier();

        Optional<T> parse(JsonNode jsonNode);

        default Optional<T> parse(ConfigurationDocument configTable) {
            return Optional.ofNullable(configTable.table()
                    .get(identifier().moduleName(), identifier().configurationKey()))
                .flatMap(this::parse);
        }
    }

    class LanguageSettingReader implements SettingReader<Locale> {
        public static final LanguageSettingReader INSTANCE = new LanguageSettingReader();

        @Override
        public EntryIdentifier identifier() {
            return LANGUAGE_IDENTIFIER;
        }

        @Override
        public Optional<Locale> parse(JsonNode jsonNode) {
            return Optional.of(Locale.of(jsonNode.asText()));
        }
    }

    class TimeZoneSettingReader implements SettingReader<ZoneId> {
        public static final TimeZoneSettingReader INSTANCE = new TimeZoneSettingReader();
        public static final EntryIdentifier TIMEZONE_IDENTIFIER = new EntryIdentifier(new ModuleName("core"), new ConfigurationKey("datetime"));

        private static final Logger LOGGER = LoggerFactory.getLogger(SettingsBasedResolver.class);
        private static final String TIMEZONE_KEY = "timeZone";

        @Override
        public EntryIdentifier identifier() {
            return TIMEZONE_IDENTIFIER;
        }

        @Override
        public Optional<ZoneId> parse(JsonNode jsonNode) {
            try {
                return Optional.ofNullable(jsonNode.get(TIMEZONE_KEY))
                    .map(JsonNode::asText)
                    .map(ZoneId::of);
            } catch (Exception e) {
                LOGGER.error("Failed to parse time zone setting: {}", jsonNode.toPrettyString(), e);
                return Optional.empty();
            }
        }
    }

    record ResolvedSettings(Map<EntryIdentifier, Object> values) {
        public static final ResolvedSettings DEFAULT = new ResolvedSettings(
            Map.of(
                LANGUAGE_IDENTIFIER, Locale.ENGLISH,
                TIMEZONE_IDENTIFIER, ZoneId.of("UTC"),
                ALARM_SETTING_IDENTIFIER, ENABLE_ALARM));

        public Locale locale() {
            return get(LANGUAGE_IDENTIFIER, Locale.class)
                .orElseThrow(() -> new IllegalStateException("Locale setting not found"));
        }

        public ZoneId zoneId() {
            return get(TIMEZONE_IDENTIFIER, ZoneId.class)
                .orElseThrow(() -> new IllegalStateException("TimeZone setting not found"));
        }

        public <T> Optional<T> get(EntryIdentifier id, Class<T> type) {
            return Optional.ofNullable(values.get(id))
                .filter(type::isInstance)
                .map(type::cast);
        }
    }

    Mono<ResolvedSettings> resolveOrDefault(MailboxSession session);

    Mono<ResolvedSettings> resolveOrDefault(Username user);

    Mono<ResolvedSettings> resolveOrDefault(Username user, Username secondUser);

    static SettingsBasedResolver of(ConfigurationResolver configurationResolver, SimpleSessionProvider sessionProvider, Set<SettingReader<?>> readers) {
        return new Default(configurationResolver, sessionProvider,  readers);
    }

    class Default implements SettingsBasedResolver {

        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);

        private final ConfigurationResolver configurationResolver;
        private final SimpleSessionProvider sessionProvider;
        private final Set<SettingReader<?>> readers;
        private final Set<EntryIdentifier> identifiers;

        public Default(ConfigurationResolver configurationResolver,
                       SimpleSessionProvider sessionProvider,
                       Set<SettingReader<?>> readers) {
            this.configurationResolver = configurationResolver;
            this.sessionProvider = sessionProvider;
            this.readers = readers;
            this.identifiers = this.readers.stream()
                .map(SettingReader::identifier)
                .collect(Collectors.toSet());
        }

        @Override
        public Mono<ResolvedSettings> resolveOrDefault(MailboxSession session) {
            return readSavedSettings(session)
                .onErrorResume(error -> {
                    logError(session.getUser(), error);
                    return Mono.just(ResolvedSettings.DEFAULT);
                })
                .switchIfEmpty(Mono.just(ResolvedSettings.DEFAULT));
        }

        @Override
        public Mono<ResolvedSettings> resolveOrDefault(Username user) {
            return resolveOrDefault(sessionProvider.createSession(user));
        }

        @Override
        public Mono<ResolvedSettings> resolveOrDefault(Username user, Username secondUser) {
            return readSavedSettings(sessionProvider.createSession(user))
                .switchIfEmpty(readSavedSettings(sessionProvider.createSession(secondUser)))
                .onErrorResume(error -> {
                    logError(user, error);
                    return Mono.just(ResolvedSettings.DEFAULT);
                })
                .switchIfEmpty(Mono.just(ResolvedSettings.DEFAULT));
        }

        private Mono<ResolvedSettings> readSavedSettings(MailboxSession session) {
            return configurationResolver.resolve(identifiers, session)
                .flatMap(this::resolveSettingsFromDocument)
                .filter(this::containsAllRequiredIdentifiers)
                .map(ResolvedSettings::new);
        }

        private Mono<Map<EntryIdentifier, Object>> resolveSettingsFromDocument(ConfigurationDocument document) {
            return Flux.fromIterable(readers)
                .flatMap(reader ->
                    reader.parse(document)
                        .map(value -> Mono.just(Map.entry(reader.identifier(), (Object) value)))
                        .orElse(Mono.empty()))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
        }

        private boolean containsAllRequiredIdentifiers(Map<EntryIdentifier, Object> map) {
            return identifiers.stream().allMatch(map::containsKey);
        }

        private void logError(Username user, Throwable error) {
            if (!(error instanceof DomainNotFoundException)) {
                LOGGER.error("Error resolving user settings for {}, will use default settings: {}",
                    user.asString(), ResolvedSettings.DEFAULT, error);
            }
        }
    }
}
