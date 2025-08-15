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

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.linagora.calendar.storage.AlarmEvent;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class MongoDBAlarmEventLedgerDAO {

    public static final String COLLECTION = "twake_calendar_alarm_events_ledge";
    public static final String EVENT_UID_FIELD = "eventUid";
    public static final String RECIPIENT_FIELD = "recipient";
    public static final String ALARM_TIME_FIELD = "alarmTime";
    public static final String CREATED_AT_FIELD = "createdAt";
    private static final long RETENTION_DAYS = 7;

    private final MongoCollection<Document> collection;

    @Inject
    public MongoDBAlarmEventLedgerDAO(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    public static Mono<Void> declareIndex(MongoCollection<Document> collection) {
        Bson uniqKeys = Indexes.compoundIndex(
            Indexes.ascending(EVENT_UID_FIELD),
            Indexes.ascending(RECIPIENT_FIELD),
            Indexes.ascending(ALARM_TIME_FIELD));

        Mono<String> createUniq = Mono.from(collection.createIndex(uniqKeys, new IndexOptions()
            .unique(true)
            .name("uniq_event_alarm_recipient")));

        Mono<String> createTtl = Mono.from(collection.createIndex(Indexes.ascending(CREATED_AT_FIELD),
            new IndexOptions()
                .expireAfter(RETENTION_DAYS, TimeUnit.DAYS)
                .name("ttl_createdAt_%sd".formatted(RETENTION_DAYS))));

        return Mono.when(createUniq, createTtl).then();
    }

    public Mono<Void> insert(AlarmEvent alarmEvent) {
        Document doc = new Document()
            .append(EVENT_UID_FIELD, alarmEvent.eventUid().value())
            .append(ALARM_TIME_FIELD, Date.from(alarmEvent.alarmTime()))
            .append(RECIPIENT_FIELD, alarmEvent.recipient().asPrettyString().toLowerCase(Locale.US))
            .append(CREATED_AT_FIELD, new Date());

        return Mono.from(collection.insertOne(doc))
            .then();
    }
}
