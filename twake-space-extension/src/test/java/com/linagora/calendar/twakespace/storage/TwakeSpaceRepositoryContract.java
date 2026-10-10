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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.util.concurrency.ConcurrentTestRunner;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceId;
import com.linagora.calendar.twakespace.model.TwakeSpace;

public interface TwakeSpaceRepositoryContract {
    Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    SpaceId SPACE_ID = new SpaceId("space-1");
    OrganizationId ORGANIZATION = new OrganizationId("org");
    Domain DOMAIN = Domain.of("space.tld");
    Username ALICE = Username.of("alice@space.tld");
    Username BOB = Username.of("bob@space.tld");
    TwakeSpace SPACE = TwakeSpace.of(SPACE_ID, Optional.of(ORGANIZATION), Optional.of(DOMAIN));

    TwakeSpaceRepository testee();

    @Test
    default void retrieveShouldBeEmptyForAnUnknownSpace() {
        assertThat(testee().retrieve(SPACE_ID).blockOptional()).isEmpty();
    }

    @Test
    default void mergeShouldStoreAnUnknownSpace() {
        TwakeSpace space = SPACE.withName(new TwakeSpace.Name("Marketing", NOW))
            .withMembers(Map.of(ALICE, TwakeSpace.Membership.role("admin", NOW), BOB, TwakeSpace.Membership.removed(NOW)))
            .withDeletion(NOW);

        testee().merge(space).block();

        assertThat(testee().retrieve(SPACE_ID).block()).isEqualTo(space);
    }

    @Test
    default void mergeShouldReturnTheStoredSpace() {
        testee().merge(SPACE.withName(new TwakeSpace.Name("Marketing", NOW))).block();

        assertThat(testee().merge(SPACE.withMembers(Map.of(ALICE, TwakeSpace.Membership.role("admin", NOW)))).block())
            .isEqualTo(SPACE.withName(new TwakeSpace.Name("Marketing", NOW))
                .withMembers(Map.of(ALICE, TwakeSpace.Membership.role("admin", NOW))));
    }

    @Test
    default void mergeShouldKeepTheLatestValues() {
        testee().merge(SPACE.withName(new TwakeSpace.Name("Sales", NOW.plusSeconds(1)))).block();

        testee().merge(SPACE.withName(new TwakeSpace.Name("Marketing", NOW))).block();

        assertThat(testee().retrieve(SPACE_ID).block().name()).contains(new TwakeSpace.Name("Sales", NOW.plusSeconds(1)));
    }

    @Test
    default void concurrentMergesShouldAllBeKept() throws Exception {
        ConcurrentTestRunner.builder()
            .reactorOperation((threadNumber, step) -> testee().merge(SPACE.withMembers(Map.of(
                Username.of("user" + threadNumber + "@space.tld"), TwakeSpace.Membership.role("viewer", NOW)))).then())
            .threadCount(10)
            .operationCount(1)
            .runSuccessfullyWithin(Duration.ofMinutes(1));

        assertThat(testee().retrieve(SPACE_ID).block().members()).hasSize(10);
    }
}
