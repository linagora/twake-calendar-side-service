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
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;

import reactor.core.publisher.Mono;

class TwakeSpaceRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private static final SpaceId SPACE_ID = new SpaceId("space-1");
    private static final OrganizationId ORGANIZATION = new OrganizationId("org");
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
        repository.save(SPACE_ID, ORGANIZATION, DOMAIN).block();

        assertThat(repository.retrieve(SPACE_ID).block())
            .isEqualTo(new TwakeSpaceRepository.TwakeSpace(SPACE_ID, Optional.of(ORGANIZATION), DOMAIN, Optional.empty()));
    }

    @Test
    void retrieveShouldHaveNoOrganizationForASpaceRecordedWithoutOne() {
        Mono.from(mongo.getDb().getCollection(TwakeSpaceRepository.COLLECTION)
            .insertOne(new Document("_id", SPACE_ID.value()).append("domain", DOMAIN.asString()))).block();

        assertThat(repository.retrieve(SPACE_ID).block().organization()).isEmpty();
    }

    @Test
    void retrieveShouldBeEmptyForAnUnknownSpace() {
        assertThat(repository.retrieve(SPACE_ID).blockOptional()).isEmpty();
    }

    @Test
    void markDeletedShouldRecordTheDeletionTime() {
        repository.save(SPACE_ID, ORGANIZATION, DOMAIN).block();

        repository.markDeleted(SPACE_ID).block();

        assertThat(repository.retrieve(SPACE_ID).block())
            .isEqualTo(new TwakeSpaceRepository.TwakeSpace(SPACE_ID, Optional.of(ORGANIZATION), DOMAIN, Optional.of(NOW)));
    }

    @Test
    void markDeletedShouldKeepTheFirstDeletionTime() {
        UpdatableTickingClock clock = new UpdatableTickingClock(NOW);
        repository = new TwakeSpaceRepository(mongo.getDb(), clock);
        repository.save(SPACE_ID, ORGANIZATION, DOMAIN).block();
        repository.markDeleted(SPACE_ID).block();

        clock.setInstant(NOW.plusSeconds(3600));
        repository.markDeleted(SPACE_ID).block();

        assertThat(repository.retrieve(SPACE_ID).block().deletion()).contains(NOW);
    }
}
