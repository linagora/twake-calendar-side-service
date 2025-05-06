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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.calendar.dav;

import java.util.UUID;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class OpenPaaSProvisioningService {
    public static final String DOMAIN = "open-paas.org";
    public static final String DATABASE = "esn_docker";

    private final MongoDBOpenPaaSDomainDAO domainDAO;
    private final MongoDBOpenPaaSUserDAO userDAO;

    public OpenPaaSProvisioningService(String mongoUri) {
        MongoClient mongoClient = MongoClients.create(mongoUri);
        MongoDatabase database = mongoClient.getDatabase(DATABASE);
        domainDAO = new MongoDBOpenPaaSDomainDAO(database);
        userDAO = new MongoDBOpenPaaSUserDAO(database, domainDAO);

        createDomainIfAbsent(Domain.of(DOMAIN)).block();
    }

    public Mono<OpenPaaSUser> createUser() {
        UUID randomUUID = UUID.randomUUID();
        return createUser(Username.fromLocalPartWithDomain("user_" + randomUUID, DOMAIN));
    }

    public Mono<OpenPaaSUser> createUser(Username username) {
        return userDAO.add(username);
    }

    public Mono<OpenPaaSDomain> createDomainIfAbsent(Domain domain) {
        return domainDAO.retrieve(domain)
            .switchIfEmpty(Mono.defer(() -> domainDAO.add(domain)));
    }
}