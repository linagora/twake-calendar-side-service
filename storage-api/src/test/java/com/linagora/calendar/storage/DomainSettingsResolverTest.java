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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration2.MapConfiguration;
import org.apache.james.core.Domain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DomainSettingsResolverTest {

    static final Domain DOMAIN = Domain.of("linagora.com");

    private MemoryDomainSettingsDAO domainSettingsDAO;

    @BeforeEach
    void setUp() {
        domainSettingsDAO = new MemoryDomainSettingsDAO();
    }

    private DomainSettingsResolver resolver(Set<Domain> disabledDomains, Set<Domain> limitedDomains, Map<String, Object> configEntries) {
        return new DomainSettingsResolver(domainSettingsDAO, disabledDomains, limitedDomains, new MapConfiguration(configEntries));
    }

    private DomainSettingsResolver defaultResolver() {
        return resolver(Set.of(), Set.of(), Map.of());
    }

    @Nested
    class Resolve {
        @Test
        void resolveShouldReturnDefaultsWhenNothingConfigured() {
            DomainSettings result = defaultResolver().resolve(DOMAIN).block();

            assertThat(result.userSearchMode()).contains(DomainSettings.DEFAULT_USER_SEARCH_MODE);
            assertThat(result.resourceSearchEnabled()).contains(DomainSettings.DEFAULT_RESOURCE_SEARCH_ENABLED);
            assertThat(result.defaultCalendarPublicVisibility()).contains(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY);
        }

        @Test
        void resolveShouldUseDAOSettingsWhenPresent() {
            DomainSettings saved = DomainSettings.builder()
                .userSearchMode(UserSearchMode.LIMITED)
                .resourceSearchEnabled(false)
                .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.PRIVATE)
                .build();
            domainSettingsDAO.save(DOMAIN, saved).block();

            DomainSettings result = defaultResolver().resolve(DOMAIN).block();

            assertThat(result.userSearchMode()).contains(UserSearchMode.LIMITED);
            assertThat(result.resourceSearchEnabled()).contains(false);
            assertThat(result.defaultCalendarPublicVisibility()).contains(DefaultCalendarPublicVisibility.PRIVATE);
        }

        @Test
        void resolveShouldUseConfigWhenDAOSettingsNotPresent() {
            Map<String, Object> configEntries = Map.of(
                "resource.search.enabled", "false",
                "default.calendar.public.visibility", "private");
            DomainSettingsResolver resolver = resolver(Set.of(), Set.of(DOMAIN), configEntries);

            DomainSettings result = resolver.resolve(DOMAIN).block();

            assertThat(result.userSearchMode()).contains(UserSearchMode.LIMITED);
            assertThat(result.resourceSearchEnabled()).contains(false);
            assertThat(result.defaultCalendarPublicVisibility()).contains(DefaultCalendarPublicVisibility.PRIVATE);
        }

        @Test
        void resolveShouldUseDAOSettingsOverConfig() {
            DomainSettings saved = DomainSettings.builder()
                .userSearchMode(UserSearchMode.LIMITED)
                .resourceSearchEnabled(false)
                .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.PRIVATE)
                .build();
            domainSettingsDAO.save(DOMAIN, saved).block();

            Map<String, Object> configEntries = Map.of(
                "resource.search.enabled", "true",
                "default.calendar.public.visibility", "read");
            DomainSettingsResolver resolver = resolver(Set.of(DOMAIN), Set.of(), configEntries);

            DomainSettings result = resolver.resolve(DOMAIN).block();

            assertThat(result.userSearchMode()).contains(UserSearchMode.LIMITED);
            assertThat(result.resourceSearchEnabled()).contains(false);
            assertThat(result.defaultCalendarPublicVisibility()).contains(DefaultCalendarPublicVisibility.PRIVATE);
        }
    }

    @Nested
    class ResolveUserSearchMode {
        @Test
        void resolveUserSearchModeShouldReturnEnabledWhenNothingConfigured() {
            assertThat(defaultResolver().resolveUserSearchMode(DOMAIN).block())
                .isEqualTo(UserSearchMode.ENABLED);
        }

        @Test
        void resolveUserSearchModeShouldReturnDAOValueWhenPresent() {
            domainSettingsDAO.save(DOMAIN, DomainSettings.builder()
                .userSearchMode(UserSearchMode.LIMITED)
                .build()).block();

            assertThat(defaultResolver().resolveUserSearchMode(DOMAIN).block())
                .isEqualTo(UserSearchMode.LIMITED);
        }

        @Test
        void resolveUserSearchModeShouldFallbackToDisabledDomainsWhenDAOEmpty() {
            DomainSettingsResolver resolver = resolver(Set.of(DOMAIN), Set.of(), Map.of());

            assertThat(resolver.resolveUserSearchMode(DOMAIN).block())
                .isEqualTo(UserSearchMode.DISABLED);
        }

        @Test
        void resolveUserSearchModeShouldFallbackToLimitedDomainsWhenDAOEmpty() {
            DomainSettingsResolver resolver = resolver(Set.of(), Set.of(DOMAIN), Map.of());

            assertThat(resolver.resolveUserSearchMode(DOMAIN).block())
                .isEqualTo(UserSearchMode.LIMITED);
        }

        @Test
        void resolveUserSearchModeShouldPreferDAOOverDisabledDomains() {
            domainSettingsDAO.save(DOMAIN, DomainSettings.builder()
                .userSearchMode(UserSearchMode.LIMITED)
                .build()).block();
            DomainSettingsResolver resolver = resolver(Set.of(DOMAIN), Set.of(), Map.of());

            assertThat(resolver.resolveUserSearchMode(DOMAIN).block())
                .isEqualTo(UserSearchMode.LIMITED);
        }
    }

    @Nested
    class ResolveDefaultCalendarPublicVisibility {
        @Test
        void resolveDefaultCalendarPublicVisibilityShouldReturnDefaultWhenNothingConfigured() {
            assertThat(defaultResolver().resolveDefaultCalendarPublicVisibility(DOMAIN).block())
                .isEqualTo(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY);
        }

        @Test
        void resolveDefaultCalendarPublicVisibilityShouldReturnDAOValueWhenPresent() {
            domainSettingsDAO.save(DOMAIN, DomainSettings.builder()
                .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.PRIVATE)
                .build()).block();

            assertThat(defaultResolver().resolveDefaultCalendarPublicVisibility(DOMAIN).block())
                .isEqualTo(DefaultCalendarPublicVisibility.PRIVATE);
        }

        @Test
        void resolveDefaultCalendarPublicVisibilityShouldFallbackToConfigWhenDAOEmpty() {
            DomainSettingsResolver resolver = resolver(Set.of(), Set.of(),
                Map.of("default.calendar.public.visibility", "private"));

            assertThat(resolver.resolveDefaultCalendarPublicVisibility(DOMAIN).block())
                .isEqualTo(DefaultCalendarPublicVisibility.PRIVATE);
        }

        @Test
        void resolveDefaultCalendarPublicVisibilityShouldPreferDAOOverConfig() {
            domainSettingsDAO.save(DOMAIN, DomainSettings.builder()
                .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.READ)
                .build()).block();
            DomainSettingsResolver resolver = resolver(Set.of(), Set.of(),
                Map.of("default.calendar.public.visibility", "private"));

            assertThat(resolver.resolveDefaultCalendarPublicVisibility(DOMAIN).block())
                .isEqualTo(DefaultCalendarPublicVisibility.READ);
        }
    }
}
