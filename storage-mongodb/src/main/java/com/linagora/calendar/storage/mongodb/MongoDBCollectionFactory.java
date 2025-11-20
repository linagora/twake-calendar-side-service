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

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBCollectionFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBCollectionFactory.class);

    public static final String USERS = MongoDBOpenPaaSUserDAO.COLLECTION;
    public static final String DOMAINS = MongoDBOpenPaaSDomainDAO.COLLECTION;
    public static final String SECRETLINKS = MongoDBSecretLinkStore.COLLECTION;
    public static final String ALARM_EVENT_LEDGE = MongoDBAlarmEventLedgerDAO.COLLECTION;
    public static final String AUTH_TICKETS = MongoDBTicketDAO.COLLECTION;

    public static void initialize(MongoDatabase database) {
        createUsersCollection(database);
        createDomainsCollection(database);
        createSecretLinksCollection(database);
        createAlarmEventLedgeCollection(database);
        createAuthTicketsCollection(database);
    }

    private static void createUsersCollection(MongoDatabase database) {
        if (!collectionExists(database, USERS)) {
            Mono.from(database.createCollection(USERS))
                .block();
        }

        Mono.from(database.getCollection(USERS)
                .createIndex(new Document("email", 1),
                    new IndexOptions()
                        .unique(true)
                        .partialFilterExpression(new Document("email", new Document("$exists", true)))))
            .block();

        Mono.from(database.getCollection(USERS)
                .createIndex(new Document("firstname", 1)))
            .block();

        Mono.from(database.getCollection(USERS)
                .createIndex(new Document("lastname", 1)))
            .block();

        try {
            Mono.from(database.getCollection(USERS)
                    .createIndex(new Document("accounts.emails", 1)))
                .block();
        } catch (Exception e) {
            // ESN migration trick
            LOGGER.warn("Incorrect index positioned on accounts.emails. Ignoring.", e);
        }
    }

    private static void createDomainsCollection(MongoDatabase database) {
        if (!collectionExists(database, DOMAINS)) {
            Mono.from(database.createCollection(DOMAINS))
                .block();
        }

        Mono.from(database.getCollection(DOMAINS)
            .createIndex(new Document("name", 1), new IndexOptions().unique(true)))
            .block();
    }

    private static void createSecretLinksCollection(MongoDatabase database) {
        if (!collectionExists(database, SECRETLINKS)) {
            Mono.from(database.createCollection(SECRETLINKS))
                .block();
        }

        Mono.from(database.getCollection(SECRETLINKS)
                .createIndex(Indexes.ascending(MongoDBSecretLinkStore.FIELD_USER_ID,
                    MongoDBSecretLinkStore.FIELD_CALENDAR_HOME_ID,
                    MongoDBSecretLinkStore.FIELD_CALENDAR_ID), new IndexOptions().unique(true)))
            .block();

        Mono.from(database.getCollection(SECRETLINKS)
                .createIndex(Indexes.ascending(MongoDBSecretLinkStore.FIELD_TOKEN,
                    MongoDBSecretLinkStore.FIELD_CALENDAR_HOME_ID,
                    MongoDBSecretLinkStore.FIELD_CALENDAR_ID), new IndexOptions().unique(true)))
            .block();
    }

    private static void createAlarmEventLedgeCollection(MongoDatabase database) {
        if (!collectionExists(database, ALARM_EVENT_LEDGE)) {
            Mono.from(database.createCollection(ALARM_EVENT_LEDGE)).block();
        }
        MongoDBAlarmEventLedgerDAO.declareIndex(database.getCollection(ALARM_EVENT_LEDGE)).block();
    }

    private static void createAuthTicketsCollection(MongoDatabase database) {
        if (!collectionExists(database, AUTH_TICKETS)) {
            Mono.from(database.createCollection(AUTH_TICKETS)).block();
        }
        MongoDBTicketDAO.declareIndex(database.getCollection(AUTH_TICKETS)).block();
    }

    private static boolean collectionExists(MongoDatabase database, String collectionName) {
        return Flux.from(database.listCollectionNames())
            .filter(collectionName::equals)
            .next()
            .blockOptional()
            .isPresent();
    }
}
