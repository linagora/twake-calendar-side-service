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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

@Testcontainers
class CalendarUtilityCliTest {
    private static final Network SHARED_NETWORK = Network.newNetwork();

    private static final String COLLECTION_NAME = "schedulingobjects";

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:4.0.10")
        .withCommand("--setParameter", "ttlMonitorSleepSecs=5")
        .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("tcalendar-utility-mongo-test-" + UUID.randomUUID()))
        .withNetwork(SHARED_NETWORK)
        .withNetworkAliases("mongo");

    static final GenericContainer<?> utilityContainer = new GenericContainer<>("linagora/twake-calendar-utility:latest")
        .withCopyFileToContainer(MountableFile.forClasspathResource("configuration.properties"), "/root/conf/")
        .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("tcalendar-utility-" + UUID.randomUUID()))
        .withNetwork(SHARED_NETWORK);

    private static MongoClient client;
    private static MongoDatabase mongoDatabase;
    private MongoCollection<Document> collection;

    @BeforeAll
    static void beforeAll() throws IOException {
        client = MongoClients.create(mongo.getReplicaSetUrl());
        mongoDatabase = client.getDatabase("testdb");

        String dockerSaveFileUrl = Paths.get("target", "jib-image.tar")
            .toAbsolutePath()
            .toString();

        try (InputStream inputStream = Files.newInputStream(Paths.get(dockerSaveFileUrl))) {
            utilityContainer.getDockerClient()
                .loadImageCmd(inputStream)
                .exec();
        }
    }

    @AfterAll
    static void afterAll() {
        if (client != null) client.close();
    }

    @BeforeEach
    void setup() {
        Mono.from(mongoDatabase.getCollection(COLLECTION_NAME).drop())
            .onErrorResume(e -> Mono.empty())
            .block();
        Mono.from(mongoDatabase.createCollection(COLLECTION_NAME)).block();
        collection = mongoDatabase.getCollection(COLLECTION_NAME);
    }

    @AfterEach
    void afterEach() {
        if (utilityContainer.isRunning()) {
            utilityContainer.stop();
        }
    }

    @Test
    void cliShouldRunPurgeSuccessfully() {
        // Insert >100 old documents (should be deleted)
        Instant oldInstant = Instant.now().minus(Duration.ofDays(30));
        for (int i = 0; i < 120; i++) {
            insertTestDocument(oldInstant);
        }

        // Insert one recent document that should remain
        Instant recentInstant = Instant.now().minus(Duration.ofHours(1));
        insertTestDocument(recentInstant);

        // Ensure documents inserted
        long before = Mono.from(collection.countDocuments()).block();
        assertThat(before).isEqualTo(121);

        // Start utility container with purge command
        utilityContainer
            .withCommand("purgeInbox", "--retention-period", "10d")
            .waitingFor(Wait.forLogMessage(".*Purge completed successfully.*", 1)
                .withStartupTimeout(Duration.ofSeconds(30)))
            .start();

        assertThat(utilityContainer.getLogs())
            .contains("Purge completed successfully: deleted 120 items, skipped 1 recent docs");

        // Validate only the recent document remains
        long after = Mono.from(collection.countDocuments()).block();
        assertThat(after).isEqualTo(1);

        Document remainingDoc = Mono.from(collection.find().first()).block();
        assertThat(remainingDoc.getLong("lastmodified"))
            .isEqualTo(recentInstant.getEpochSecond());
    }

    private Document newDocWithLastModified(Instant instant) {
        return new Document("_id", new ObjectId())
            .append("lastmodified", instant.getEpochSecond());
    }

    private void insertTestDocument(Instant instant) {
        Document doc = newDocWithLastModified(instant);
        Mono.from(collection.insertOne(doc)).block();
    }
}