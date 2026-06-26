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

package com.linagora.calendar.storage.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.apache.james.utils.UpdatableTickingClock;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.TeamCalendarRepositoryContract;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Flux;

public class MongoDBTeamCalendarRepositoryTest implements TeamCalendarRepositoryContract {
    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(MongoDBTeamCalendarRepository.COLLECTION));

    private MongoDBTeamCalendarRepository repository;
    private UpdatableTickingClock clock;

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(Instant.parse("2026-01-01T00:00:00Z"));
        repository = new MongoDBTeamCalendarRepository(mongo.getDb(), clock);
    }

    @Override
    public TeamCalendarRepository testee() {
        return repository;
    }

    @Override
    public UpdatableTickingClock clock() {
        return clock;
    }

    @Override
    public TeamCalendarId generateTeamCalendarId() {
        return new TeamCalendarId(new ObjectId().toHexString());
    }

    @Test
    void collectionFactoryShouldInitializeIndexes() {
        List<Document> indexes = Flux.from(mongo.getDb().getCollection(MongoDBTeamCalendarRepository.COLLECTION).listIndexes())
            .collectList()
            .block();

        assertThat(indexes)
            .extracting(index -> index.getString("name"))
            .contains("domainId_1", "domainId_1_name_1");
    }
}
