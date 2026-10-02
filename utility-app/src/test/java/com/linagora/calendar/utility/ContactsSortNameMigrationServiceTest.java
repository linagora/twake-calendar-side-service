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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

import com.linagora.calendar.utility.repository.MongoCardsDAO;
import com.linagora.calendar.utility.service.ContactsSortNameMigrationService;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ValidationOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Testcontainers
class ContactsSortNameMigrationServiceTest {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.6")
        .withCommand("--replSet", "docker-rs")
        .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("tcalendar-utility-mongo-test-" + UUID.randomUUID()));

    static final String COLLECTION_NAME = "cards";

    private static MongoClient client;
    private static MongoDatabase mongoDatabase;

    @BeforeAll
    static void beforeAll() {
        client = MongoClients.create(mongo.getReplicaSetUrl());
        mongoDatabase = client.getDatabase("testdb");
    }

    @AfterAll
    static void afterAll() {
        if (client != null) client.close();
    }

    private ContactsSortNameMigrationService testee;
    private MongoCollection<Document> mongoCollection;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setup() {
        Mono.from(mongoDatabase.createCollection(COLLECTION_NAME)).block();
        mongoCollection = mongoDatabase.getCollection(COLLECTION_NAME);

        output = new ByteArrayOutputStream();
        testee = new ContactsSortNameMigrationService(new MongoCardsDAO(mongoDatabase),
            new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
    }

    @AfterEach
    void afterEach() {
        Mono.from(mongoDatabase.getCollection(COLLECTION_NAME).drop()).block();
    }

    private static String vcard(String fullName) {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:" + fullName + "\r\nEND:VCARD\r\n";
    }

    private static Document card(String uri, Object cardData) {
        return new Document("_id", new ObjectId())
            .append("addressbookid", new ObjectId())
            .append("uri", uri)
            .append("carddata", cardData)
            .append("fn", "x");
    }

    private void insertCards(Document... cards) {
        Mono.from(mongoCollection.insertMany(Arrays.asList(cards))).block();
    }

    private Map<String, Object> sortNamesByUri() {
        return Flux.from(mongoCollection.find())
            .collectList()
            .block()
            .stream()
            .collect(Collectors.toMap(doc -> doc.getString("uri"), doc -> doc.containsKey("fn_sort") ? doc.get("fn_sort") : "<missing>"));
    }

    @Test
    void migrateShouldStoreSortableFullNameOfCardsMissingIt() {
        insertCards(card("elodie.vcf", vcard(" élodie Martin ")), card("bob.vcf", vcard("bob")));

        testee.migrate(10).block();

        assertThat(sortNamesByUri()).containsOnly(
            Map.entry("elodie.vcf", "élodie Martin"),
            Map.entry("bob.vcf", "bob"));
    }

    @Test
    void migrateShouldNotOverwriteExistingSortableFullName() {
        insertCards(card("kept.vcf", vcard("New name")).append("fn_sort", "Stored by sabre"), card("missing.vcf", vcard("Anna")));

        testee.migrate(10).block();

        assertThat(sortNamesByUri()).containsOnly(
            Map.entry("kept.vcf", "Stored by sabre"),
            Map.entry("missing.vcf", "Anna"));
    }

    @Test
    void migrateShouldStoreEmptySortableFullNameWhenNoneCanBeRead() {
        insertCards(card("nofn.vcf", "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;John\r\nEND:VCARD\r\n"),
            card("broken.vcf", "not a vcard"),
            card("nodata.vcf", null));

        testee.migrate(10).block();

        // Stored anyway, so that these cards are listed and not processed again
        assertThat(sortNamesByUri()).containsOnly(
            Map.entry("nofn.vcf", ""),
            Map.entry("broken.vcf", ""),
            Map.entry("nodata.vcf", ""));
    }

    @Test
    void migrateShouldHandleSeveralBatches() {
        insertCards(IntStream.range(0, 25)
            .mapToObj(i -> card("card" + i + ".vcf", vcard("Contact " + i)))
            .toArray(Document[]::new));

        testee.migrate(10).block();

        assertThat(sortNamesByUri().values()).hasSize(25).doesNotContain("<missing>");
        assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("Found 25 contacts, 25 without sortable full name")
            .contains("Batch 3/3 (100%) - 5 contacts updated")
            .contains("Migration completed successfully: updated 25 contacts");
    }

    @Test
    void migrateShouldSkipFailingBatchAndProcessTheNextOnes() {
        insertCards(IntStream.range(0, 25)
            .mapToObj(i -> card("card" + i + ".vcf", vcard("Contact " + i)))
            .toArray(Document[]::new));

        AtomicInteger calls = new AtomicInteger();
        MongoCardsDAO failingSecondBatch = new MongoCardsDAO(mongoDatabase) {
            @Override
            public Mono<Long> setSortNames(List<SortName> sortNames) {
                if (calls.incrementAndGet() == 2) {
                    return Mono.error(new RuntimeException("Simulated write failure"));
                }
                return super.setSortNames(sortNames);
            }
        };
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ContactsSortNameMigrationService failingTestee = new ContactsSortNameMigrationService(failingSecondBatch,
            new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(errors, true, StandardCharsets.UTF_8));

        // The failure is reported once every batch was processed
        assertThatThrownBy(() -> failingTestee.migrate(10).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("updated 15 contacts, 1 batches (10 contacts) failed");
        assertThat(calls).hasValue(3);
        assertThat(sortNamesByUri().values()).filteredOn("<missing>"::equals).hasSize(10);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("Batch 2/3 failed, skipping its 10 contacts");

        // Running it again processes the skipped contacts
        testee.migrate(10).block();

        assertThat(sortNamesByUri().values()).hasSize(25).doesNotContain("<missing>");
    }

    @Test
    void migrateShouldCountOnlyRejectedUpdatesOfABatchAsFailed() {
        // GIVEN a validator refusing a sortable full name on one card only
        Mono.from(mongoCollection.drop()).block();
        Mono.from(mongoDatabase.createCollection(COLLECTION_NAME, new CreateCollectionOptions()
            .validationOptions(new ValidationOptions().validator(
                Filters.or(Filters.exists("fn_sort", false), Filters.ne("uri", "rejected.vcf"))))))
            .block();
        insertCards(card("anna.vcf", vcard("Anna")), card("rejected.vcf", vcard("Rejected")), card("zoe.vcf", vcard("Zoe")));

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ContactsSortNameMigrationService migration = new ContactsSortNameMigrationService(new MongoCardsDAO(mongoDatabase),
            new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(errors, true, StandardCharsets.UTF_8));

        // WHEN the three cards are migrated in the same batch
        assertThatThrownBy(() -> migration.migrate(10).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("updated 2 contacts, 1 batches (1 contacts) failed");

        // THEN only the rejected update failed, the others of the batch were stored
        assertThat(sortNamesByUri()).containsOnly(
            Map.entry("anna.vcf", "Anna"),
            Map.entry("rejected.vcf", "<missing>"),
            Map.entry("zoe.vcf", "Zoe"));
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("Batch 1/1 partially failed: 2 contacts updated, 1 skipped");
    }

    @Test
    void migrateShouldBeIdempotent() {
        insertCards(card("anna.vcf", vcard("Anna")));

        testee.migrate(10).block();
        testee.migrate(10).block();

        assertThat(sortNamesByUri()).containsOnly(Map.entry("anna.vcf", "Anna"));
        assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("Found 1 contacts, 0 without sortable full name")
            .contains("Migration completed successfully: updated 0 contacts");
    }

    @Test
    void migrateShouldHandleEmptyCollection() {
        testee.migrate(10).block();

        assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("Migration completed successfully: updated 0 contacts");
    }

    @Test
    void migrateShouldFailOnInvalidBatchSize() {
        assertThatThrownBy(() -> testee.migrate(0).block())
            .isInstanceOf(IllegalArgumentException.class);
    }
}
