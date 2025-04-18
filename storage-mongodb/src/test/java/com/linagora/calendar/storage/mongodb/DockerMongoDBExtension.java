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

import java.util.List;

import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.bson.Document;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class DockerMongoDBExtension implements BeforeAllCallback, AfterAllCallback, AfterEachCallback {
    public static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.0.10");
    public static final List<String> CLEANUP_COLLECTIONS = List.of("domains", "users");

    private static MongoDBConfiguration mongoDBConfiguration;

    private final List<String> cleanupCollections;

    public DockerMongoDBExtension(List<String> cleanupCollections) {
        this.cleanupCollections = cleanupCollections;
    }

    public DockerMongoDBExtension() {
        this(CLEANUP_COLLECTIONS);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        mongoDBContainer.start();
        init();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        mongoDBContainer.stop();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        for (String collection : cleanupCollections) {
            Mono.from(db.getCollection(collection).deleteMany(new Document())).block();
        }
    }

    private static MongoDatabase db;

    static void init() {
        mongoDBConfiguration = new MongoDBConfiguration(mongoDBContainer.getConnectionString(), "esn_docker");
        db = MongoDBConnectionFactory.instantiateDB(mongoDBConfiguration, new RecordingMetricFactory());
        MongoDBCollectionFactory.initialize(db);
    }

    public static MongoDBConfiguration getMongoDBConfiguration() {
        return mongoDBConfiguration;
    }

    public MongoDatabase getDb() {
        return db;
    }
}
