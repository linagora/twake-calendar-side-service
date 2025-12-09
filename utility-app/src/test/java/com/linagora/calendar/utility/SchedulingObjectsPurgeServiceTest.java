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

package com.linagora.calendar.utility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.linagora.calendar.utility.repository.MongoSchedulingObjectsDAO;
import com.linagora.calendar.utility.service.SchedulingObjectsPurgeService;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Testcontainers
public class SchedulingObjectsPurgeServiceTest {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:4.0.10")
        .withCommand("--setParameter", "ttlMonitorSleepSecs=5")
        .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("tcalendar-utility-mongo-test-" + UUID.randomUUID()));


    private static MongoClient client;
    private static MongoDatabase mongoDatabase;

    static final String COLLECTION_NAME = "schedulingobjects";

    @BeforeAll
    static void beforeAll() {
        client = MongoClients.create(mongo.getReplicaSetUrl());
        mongoDatabase = client.getDatabase("testdb");
    }

    @AfterAll
    static void afterAll() {
        if (client != null) client.close();
    }

    private SchedulingObjectsPurgeService testee;
    private MongoCollection<Document> mongoCollection;

    @BeforeEach
    void setup() {
        Mono.from(mongoDatabase.createCollection(COLLECTION_NAME)).block();
        mongoCollection = mongoDatabase.getCollection(COLLECTION_NAME);

        MongoSchedulingObjectsDAO schedulingObjectsDAO = new MongoSchedulingObjectsDAO(mongoDatabase);
        testee = new SchedulingObjectsPurgeService(schedulingObjectsDAO, System.out, System.err);
    }

    @AfterEach
    void afterEach() {
        Mono.from(mongoDatabase.getCollection(COLLECTION_NAME).drop()).block();
    }

    private void insertDocuments(Document... docs) {
        Mono.from(mongoCollection.insertMany(Arrays.asList(docs))).block();
    }

    private Document newDocWithLastModified(Instant instant) {
        return new Document("_id", new ObjectId())
            .append("lastmodified", instant.getEpochSecond());
    }

    @Test
    void purgeShouldDeleteOldRecords() {
        Instant oldInstant = Instant.now().minus(Duration.ofDays(30));
        Instant recentInstant = Instant.now();

        insertDocuments(newDocWithLastModified(recentInstant), newDocWithLastModified(oldInstant));

        testee.purge(Duration.ofDays(10)).block();

        List<Document> remaining = Flux.from(mongoCollection.find()).collectList().block();

        assertThat(remaining)
            .hasSize(1)
            .as("Remaining document must have lastmodified equal to the recent timestamp")
            .first()
            .extracting(doc -> doc.getLong("lastmodified"))
            .isEqualTo(recentInstant.getEpochSecond());
    }

    @Test
    void purgeShouldNotDeleteWhenAllDocumentsAreRecent() {
        Instant recent1 = Instant.now().minus(Duration.ofDays(2));
        Instant recent2 = Instant.now().minus(Duration.ofDays(1));

        insertDocuments(newDocWithLastModified(recent1),
            newDocWithLastModified(recent2));

        testee.purge(Duration.ofDays(10)).block();

        List<Document> remaining = Flux.from(mongoCollection.find()).collectList().block();

        assertThat(remaining).hasSize(2);
    }

    @Test
    void purgeShouldDeleteAllOldRecords() {
        Instant old1 = Instant.now().minus(Duration.ofDays(50));
        Instant old2 = Instant.now().minus(Duration.ofDays(40));

        insertDocuments(newDocWithLastModified(old1),
            newDocWithLastModified(old2));

        testee.purge(Duration.ofDays(10)).block();

        List<Document> remaining = Flux.from(mongoCollection.find()).collectList().block();

        assertThat(remaining).isEmpty();
    }

    @Test
    void purgeShouldHandleEmptyCollection() {
        testee.purge(Duration.ofDays(10)).block();

        List<Document> remaining = Flux.from(mongoCollection.find()).collectList().block();

        assertThat(remaining).isEmpty();
    }

    @Test
    void purgeShouldHandleMultipleBatches() {
        Instant oldInstant = Instant.now().minus(Duration.ofDays(365));

        for (int i = 0; i < 150; i++) {
            insertDocuments(newDocWithLastModified(oldInstant));
        }

        testee.purge(Duration.ofDays(10)).block();

        List<Document> remaining = Flux.from(mongoCollection.find()).collectList().block();

        assertThat(remaining).isEmpty();
    }

    @Test
    void purgeShouldBatchDeleteOnlyOldRecords() {
        // 1. Create old records (should be deleted)
        Instant oldInstant = Instant.now().minus(Duration.ofDays(365));

        for (int i = 0; i < 120; i++) {
            insertDocuments(newDocWithLastModified(oldInstant));
        }

        // 2. Create recent records (should stay)
        Instant recentInstant = Instant.now().minus(Duration.ofDays(1));
        for (int i = 0; i < 5; i++) {
            insertDocuments(newDocWithLastModified(recentInstant));
        }

        testee.purge(Duration.ofDays(10)).block();

        List<Document> remaining = Flux.from(mongoCollection.find()).collectList().block();

        assertThat(remaining)
            .hasSize(5)
            .allSatisfy(doc -> assertThat(doc.getLong("lastmodified"))
                    .isEqualTo(recentInstant.getEpochSecond()));
    }

    @Test
    void purgeShouldFailOnInvalidRetention() {
        assertThatThrownBy(() -> testee.purge(Duration.ZERO).block())
            .isInstanceOf(IllegalArgumentException.class);
    }
}
