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

import java.util.List;

import org.assertj.core.api.SoftAssertions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.MigrationResult;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAOContract;

import reactor.core.publisher.Mono;

public class MongoDBOpenPaaSUserDAOTest implements OpenPaaSUserDAOContract {
    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension();

    private MongoDBOpenPaaSUserDAO mongoDBOpenPaaSUserDAO;

    @BeforeEach
    void setUp() {
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        domainDAO.add(USERNAME.getDomainPart().get()).block();
        domainDAO.add(USERNAME_2.getDomainPart().get()).block();
        mongoDBOpenPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongo.getDb(), domainDAO);
    }

    @Override
    public OpenPaaSUserDAO testee() {
        return mongoDBOpenPaaSUserDAO;
    }

    @Test
    void addMissingFieldsShouldAddEmailAndFirstnamesWhenMissing() {
        // Given a document with missing fields
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        ObjectId domainId = new ObjectId(domainDAO.retrieve(USERNAME.getDomainPart().get()).block().id().value());

        Document userWithoutFields = new Document()
            .append("firstname", "Jean Paul")
            .append("lastname", "Dupont")
            .append("password", "secret")
            .append("domains", List.of(new Document("domain_id", domainId)))
            .append("accounts", List.of(new Document()
                .append("type", "email")
                .append("emails", List.of(USERNAME.asString()))));

        ObjectId userId = Mono.from(mongo.getDb().getCollection("users").insertOne(userWithoutFields))
            .map(result -> result.getInsertedId().asObjectId().getValue())
            .block();

        // When is is migrated
        MigrationResult result = mongoDBOpenPaaSUserDAO.addMissingFields().block();
        Document updatedUser = Mono.from(mongo.getDb().getCollection("users")
                .find(new Document("_id", userId))
                .first())
            .block();

        SoftAssertions.assertSoftly(softly -> {
            // Then it is reported in migration results
            softly.assertThat(result.processedUsers()).isEqualTo(1);
            softly.assertThat(result.upgradedUsers()).isEqualTo(1);
            softly.assertThat(result.errorCount()).isEqualTo(0);

            // And the entry is correctly updated
            softly.assertThat(updatedUser.getString("email")).isEqualTo(USERNAME.asString());
            softly.assertThat(updatedUser.getList("firstnames", String.class)).containsExactly("Jean", "Paul");
        });
    }
}
