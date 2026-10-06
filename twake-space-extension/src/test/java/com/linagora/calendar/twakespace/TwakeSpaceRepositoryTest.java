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

package com.linagora.calendar.twakespace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;

class TwakeSpaceRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private static final String ORGANIZATION = "org";
    private static final Domain DOMAIN = Domain.of("space.tld");

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(TwakeSpaceRepository.COLLECTION));

    private TwakeSpaceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TwakeSpaceRepository(mongo.getDb(), new UpdatableTickingClock(NOW));
    }

    @Test
    void retrieveShouldReturnTheSavedSpace() {
        repository.save("space-1", ORGANIZATION, DOMAIN).block();

        assertThat(repository.retrieve("space-1").block())
            .isEqualTo(new TwakeSpaceRepository.TwakeSpace("space-1", ORGANIZATION, DOMAIN, Optional.empty()));
    }

    @Test
    void retrieveShouldBeEmptyForAnUnknownSpace() {
        assertThat(repository.retrieve("space-1").blockOptional()).isEmpty();
    }

    @Test
    void markDeletedShouldRecordTheDeletionTime() {
        repository.save("space-1", ORGANIZATION, DOMAIN).block();

        repository.markDeleted("space-1").block();

        assertThat(repository.retrieve("space-1").block())
            .isEqualTo(new TwakeSpaceRepository.TwakeSpace("space-1", ORGANIZATION, DOMAIN, Optional.of(NOW)));
    }

    @Test
    void markDeletedShouldKeepTheFirstDeletionTime() {
        UpdatableTickingClock clock = new UpdatableTickingClock(NOW);
        repository = new TwakeSpaceRepository(mongo.getDb(), clock);
        repository.save("space-1", ORGANIZATION, DOMAIN).block();
        repository.markDeleted("space-1").block();

        clock.setInstant(NOW.plusSeconds(3600));
        repository.markDeleted("space-1").block();

        assertThat(repository.retrieve("space-1").block().deletion()).contains(NOW);
    }
}
