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

import static org.assertj.core.api.AssertionsForClassTypes.tuple;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.DomainAdministrator;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAOContract;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;

import reactor.core.publisher.Mono;

public class MongoDBOpenPaaSDomainAdminDAOTest implements OpenPaaSDomainAdminDAOContract {

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension();

    private MongoDBOpenPaaSDomainDAO mongoDBOpenPaaSDomainDAO;

    @BeforeEach
    void setUp() {
        mongoDBOpenPaaSDomainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
    }

    @Override
    public OpenPaaSDomainDAO domainDAO() {
        return mongoDBOpenPaaSDomainDAO;
    }

    @Override
    public OpenPaaSDomainAdminDAO testee() {
        return mongoDBOpenPaaSDomainDAO;
    }

    @Test
    void shouldBeCompatibleWithLegacyDatabaseStructure() {
        ObjectId domainId = new ObjectId("68c27196b2316300576058c5");
        ObjectId user1 = new ObjectId("68c27196b2316300576058c4");
        ObjectId user2 = new ObjectId("68c27197b2316300576058c9");
        ObjectId user3 = new ObjectId("68c27197b2316300576058cb");

        Document legacyDomain = new Document()
            .append("_id", domainId)
            .append("__v", 4)
            .append("administrators", List.of(
                new Document()
                    .append("timestamps", new Document("creation", java.util.Date.from(Instant.parse("2025-09-11T06:52:06.600Z"))))
                    .append("user_id", user1),
                new Document()
                    .append("timestamps", new Document("creation", java.util.Date.from(Instant.parse("2025-09-11T07:22:40.591Z"))))
                    .append("user_id", user2),
                new Document()
                    .append("timestamps", new Document("creation", java.util.Date.from(Instant.parse("2025-09-11T07:23:33.740Z"))))
                    .append("user_id", user3)
            ))
            .append("company_name", "openpaas")
            .append("hostnames", List.of("localhost", "127.0.0.1", "open-paas.org"))
            .append("injections", List.of())
            .append("name", "open-paas.org")
            .append("schemaVersion", 1)
            .append("timestamps", new Document("creation", java.util.Date.from(Instant.parse("2025-09-11T06:52:06.600Z"))));

        Mono.from(mongo.getDb().getCollection(MongoDBOpenPaaSDomainDAO.COLLECTION).insertOne(legacyDomain)).block();

        // When
        List<DomainAdministrator> admins = testee().listAdmins(new OpenPaaSId(domainId.toHexString()))
            .collectList().block();

        // then
        assertThat(admins)
            .extracting(DomainAdministrator::userId, DomainAdministrator::creation)
            .containsExactlyInAnyOrder(
                tuple(new OpenPaaSId("68c27196b2316300576058c4"), Instant.parse("2025-09-11T06:52:06.600Z")),
                tuple(new OpenPaaSId("68c27197b2316300576058c9"), Instant.parse("2025-09-11T07:22:40.591Z")),
                tuple(new OpenPaaSId("68c27197b2316300576058cb"), Instant.parse("2025-09-11T07:23:33.740Z")));
    }
}
