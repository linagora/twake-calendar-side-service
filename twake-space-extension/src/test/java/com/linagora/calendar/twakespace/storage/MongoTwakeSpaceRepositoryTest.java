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

import java.util.List;

import org.apache.james.utils.UpdatableTickingClock;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;

import reactor.core.publisher.Mono;

class MongoTwakeSpaceRepositoryTest implements TwakeSpaceRepositoryContract {
    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(MongoTwakeSpaceRepository.COLLECTION));

    private UpdatableTickingClock clock;
    private MongoTwakeSpaceRepository repository;

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(NOW);
        repository = new MongoTwakeSpaceRepository(mongo.getDb(), clock);
    }

    @Override
    public TwakeSpaceRepository testee() {
        return repository;
    }

    @Override
    public UpdatableTickingClock clock() {
        return clock;
    }

    @Test
    void retrieveShouldHaveNoOrganizationForASpaceRecordedWithoutOne() {
        Mono.from(mongo.getDb().getCollection(MongoTwakeSpaceRepository.COLLECTION)
            .insertOne(new Document("_id", SPACE_ID.value()).append("domain", DOMAIN.asString()))).block();

        assertThat(repository.retrieve(SPACE_ID).block().organization()).isEmpty();
    }
}
