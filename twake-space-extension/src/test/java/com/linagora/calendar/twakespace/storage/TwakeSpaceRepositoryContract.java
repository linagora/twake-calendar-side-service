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

package com.linagora.calendar.twakespace.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceId;

public interface TwakeSpaceRepositoryContract {
    Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    SpaceId SPACE_ID = new SpaceId("space-1");
    OrganizationId ORGANIZATION = new OrganizationId("org");
    Domain DOMAIN = Domain.of("space.tld");

    TwakeSpaceRepository testee();

    UpdatableTickingClock clock();

    @Test
    default void retrieveShouldReturnTheSavedSpace() {
        testee().save(SPACE_ID, ORGANIZATION, DOMAIN).block();

        assertThat(testee().retrieve(SPACE_ID).block())
            .isEqualTo(new TwakeSpaceRepository.TwakeSpace(SPACE_ID, Optional.of(ORGANIZATION), DOMAIN, Optional.empty()));
    }

    @Test
    default void retrieveShouldBeEmptyForAnUnknownSpace() {
        assertThat(testee().retrieve(SPACE_ID).blockOptional()).isEmpty();
    }

    @Test
    default void markDeletedShouldRecordTheDeletionTime() {
        testee().save(SPACE_ID, ORGANIZATION, DOMAIN).block();

        testee().markDeleted(SPACE_ID).block();

        assertThat(testee().retrieve(SPACE_ID).block())
            .isEqualTo(new TwakeSpaceRepository.TwakeSpace(SPACE_ID, Optional.of(ORGANIZATION), DOMAIN, Optional.of(NOW)));
    }

    @Test
    default void markDeletedShouldKeepTheFirstDeletionTime() {
        testee().save(SPACE_ID, ORGANIZATION, DOMAIN).block();
        testee().markDeleted(SPACE_ID).block();

        clock().setInstant(NOW.plusSeconds(3600));
        testee().markDeleted(SPACE_ID).block();

        assertThat(testee().retrieve(SPACE_ID).block().deletion()).contains(NOW);
    }

    @Test
    default void markDeletedShouldIgnoreAnUnknownSpace() {
        testee().markDeleted(SPACE_ID).block();

        assertThat(testee().retrieve(SPACE_ID).blockOptional()).isEmpty();
    }
}
