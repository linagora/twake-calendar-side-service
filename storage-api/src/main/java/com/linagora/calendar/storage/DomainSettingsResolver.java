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

package com.linagora.calendar.storage;

import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.core.Domain;

import reactor.core.publisher.Mono;

public class DomainSettingsResolver {

    private final DomainSettingsDAO domainSettingsDAO;
    private final Set<Domain> userSearchDisabledDomains;
    private final Set<Domain> userSearchLimitedDomains;
    private final Configuration config;

    @Inject
    public DomainSettingsResolver(DomainSettingsDAO domainSettingsDAO,
                                  @Named("userSearchDisabledDomains") Set<Domain> userSearchDisabledDomains,
                                  @Named("userSearchLimitedDomains") Set<Domain> userSearchLimitedDomains,
                                  @Named("configuration") Configuration config) {
        this.domainSettingsDAO = domainSettingsDAO;
        this.userSearchDisabledDomains = userSearchDisabledDomains;
        this.userSearchLimitedDomains = userSearchLimitedDomains;
        this.config = config;
    }

    public Mono<DomainSettings> resolve(Domain domain) {
        return domainSettingsDAO.retrieve(domain)
            .switchIfEmpty(Mono.just(DomainSettings.DEFAULT_DOMAIN_SETTINGS))
            .map(settings -> applyFallbacks(domain, settings));
    }

    public Mono<UserSearchMode> resolveUserSearchMode(Domain domain) {
        return domainSettingsDAO.retrieve(domain)
            .flatMap(settings -> Mono.justOrEmpty(settings.userSearchMode()))
            .switchIfEmpty(Mono.fromCallable(() -> resolveUserSearchModeFromConfig(domain)));
    }

    public Mono<DefaultCalendarPublicVisibility> resolveDefaultCalendarPublicVisibility(Domain domain) {
        return domainSettingsDAO.retrieve(domain)
            .flatMap(settings -> Mono.justOrEmpty(settings.defaultCalendarPublicVisibility()))
            .defaultIfEmpty(configuredDefaultCalendarPublicVisibility());
    }

    private DomainSettings applyFallbacks(Domain domain, DomainSettings settings) {
        return DomainSettings.builder()
            .userSearchMode(settings.userSearchMode().orElseGet(() -> resolveUserSearchModeFromConfig(domain)))
            .resourceSearchEnabled(settings.resourceSearchEnabled()
                .orElse(configuredResourceSearchEnabled()))
            .defaultCalendarPublicVisibility(settings.defaultCalendarPublicVisibility()
                .orElse(configuredDefaultCalendarPublicVisibility()))
            .calendarPublicVisibilitySettingEnabled(settings.calendarPublicVisibilitySettingEnabled()
                .orElse(configuredCalendarPublicVisibilitySettingEnabled()))
            .build();
    }

    private UserSearchMode resolveUserSearchModeFromConfig(Domain domain) {
        if (userSearchDisabledDomains.contains(domain)) {
            return UserSearchMode.DISABLED;
        }
        if (userSearchLimitedDomains.contains(domain)) {
            return UserSearchMode.LIMITED;
        }
        return UserSearchMode.ENABLED;
    }

    private Boolean configuredResourceSearchEnabled() {
        return Optional.ofNullable(config.getString("resource.search.enabled"))
            .map(Boolean::parseBoolean)
            .orElse(DomainSettings.DEFAULT_RESOURCE_SEARCH_ENABLED);
    }

    private DefaultCalendarPublicVisibility configuredDefaultCalendarPublicVisibility() {
        return Optional.ofNullable(config.getString("default.calendar.public.visibility"))
            .map(DefaultCalendarPublicVisibility::deserialize)
            .orElse(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY);
    }

    private Boolean configuredCalendarPublicVisibilitySettingEnabled() {
        return Optional.ofNullable(config.getString("calendar.public.visibility.setting.enabled"))
            .map(Boolean::parseBoolean)
            .orElse(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY_SETTING_ENABLED);
    }
}
