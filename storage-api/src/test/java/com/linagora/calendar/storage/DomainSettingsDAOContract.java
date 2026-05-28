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

import org.apache.james.core.Domain;
import org.junit.jupiter.api.Test;

public interface DomainSettingsDAOContract {

    Domain DOMAIN = Domain.of("linagora.com");
    Domain DOMAIN_2 = Domain.of("other.com");

    DomainSettings SETTINGS = DomainSettings.builder()
        .userSearchMode(UserSearchMode.LIMITED)
        .resourceSearchEnabled(false)
        .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.READ)
        .build();

    DomainSettingsDAO testee();

    @Test
    default void retrieveShouldReturnEmptyWhenNothingSaved() {
        assertThat(testee().retrieve(DOMAIN).blockOptional()).isEmpty();
    }

    @Test
    default void retrieveShouldReturnEmptyForUnknownDomain() {
        testee().save(DOMAIN, SETTINGS).block();

        assertThat(testee().retrieve(DOMAIN_2).blockOptional()).isEmpty();
    }

    @Test
    default void saveThenRetrieveShouldReturnSavedSettings() {
        testee().save(DOMAIN, SETTINGS).block();

        assertThat(testee().retrieve(DOMAIN).block()).isEqualTo(SETTINGS);
    }

    @Test
    default void saveShouldReplaceExistingDocument() {
        DomainSettings updated = DomainSettings.builder()
            .userSearchMode(UserSearchMode.DISABLED)
            .resourceSearchEnabled(true)
            .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.PRIVATE)
            .build();

        testee().save(DOMAIN, SETTINGS).block();
        testee().save(DOMAIN, updated).block();

        assertThat(testee().retrieve(DOMAIN).block()).isEqualTo(updated);
    }

    @Test
    default void saveShouldBeIndependentPerDomain() {
        DomainSettings settings2 = DomainSettings.builder()
            .userSearchMode(UserSearchMode.ENABLED)
            .build();

        testee().save(DOMAIN, SETTINGS).block();
        testee().save(DOMAIN_2, settings2).block();

        assertThat(testee().retrieve(DOMAIN).block()).isEqualTo(SETTINGS);
        assertThat(testee().retrieve(DOMAIN_2).block()).isEqualTo(settings2);
    }
}
