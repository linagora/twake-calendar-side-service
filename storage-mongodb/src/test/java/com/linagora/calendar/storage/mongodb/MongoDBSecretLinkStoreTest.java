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

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.secretlink.MemorySecretLinkStoreTest.SecretLinkPermissionCheckerHelper;
import com.linagora.calendar.storage.secretlink.SecretLinkStore;
import com.linagora.calendar.storage.secretlink.SecretLinkStoreContract;

import reactor.core.publisher.Mono;

public class MongoDBSecretLinkStoreTest implements SecretLinkStoreContract {

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of("domains", "users", "secretlinks"));

    private SecretLinkPermissionCheckerHelper secretLinkPermissionChecker;

    private MongoDBSecretLinkStore mongoDBSecretLinkStore;

    @BeforeEach
    void setUp() {
        secretLinkPermissionChecker = new SecretLinkPermissionCheckerHelper();
        MongoDBOpenPaaSDomainDAO mongoDBOpenPaaSDomainDAO = new MongoDBOpenPaaSDomainDAO(new DockerMongoDBExtension().getDb());
        MongoDBOpenPaaSUserDAO mongoDBOpenPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongo.getDb(),mongoDBOpenPaaSDomainDAO);
        mongoDBSecretLinkStore = new MongoDBSecretLinkStore(mongo.getDb(), secretLinkPermissionChecker, mongoDBOpenPaaSUserDAO);
        setupBeforeEach();

        mongoDBOpenPaaSDomainDAO.add(USERNAME.getDomainPart().get()).block();
        mongoDBOpenPaaSUserDAO.add(USERNAME).block();
        mongoDBOpenPaaSUserDAO.add(USERNAME_2).block();
    }

    @AfterEach
    void tearDown() {
        resetCollection();
    }

    @Override
    public SecretLinkStore testee() {
        return mongoDBSecretLinkStore;
    }

    @Override
    public void setPermissionChecker(boolean value) {
        secretLinkPermissionChecker.setPermissionGranted(value);
    }

    private void resetCollection() {
        Mono.from(mongo.getDb().getCollection("secretlinks").deleteMany(new Document())).block();
    }
}
