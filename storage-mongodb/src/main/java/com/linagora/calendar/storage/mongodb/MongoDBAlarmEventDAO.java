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

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Indexes.ascending;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.bson.Document;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBAlarmEventDAO implements AlarmEventDAO {
    public static final String COLLECTION = "twake_calendar_alarm_events";
    private final MongoCollection<Document> collection;

    @Inject
    public MongoDBAlarmEventDAO(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        Mono.from(collection.createIndex(ascending("eventUid", "recipient"), new IndexOptions())).block();
        Mono.from(collection.createIndex(ascending("alarmTime"), new IndexOptions())).block();
        Mono.from(collection.createIndex(ascending("eventStartTime"), new IndexOptions())).block();
    }

    @Override
    public Mono<AlarmEvent> find(EventUid eventUid, MailAddress recipient) {
        return Mono.from(collection.find(
            Filters.and(
                eq("eventUid", eventUid.value()),
                eq("recipient", recipient.asString())
            )).first())
            .map(this::fromDocument);
    }

    @Override
    public Mono<Void> create(AlarmEvent alarmEvent) {
        return Mono.from(collection.insertOne(toDocument(alarmEvent))).then();
    }

    @Override
    public Mono<Void> update(AlarmEvent alarmEvent) {
        return Mono.from(collection.replaceOne(
            Filters.and(
                eq("eventUid", alarmEvent.eventUid().value()),
                eq("recipient", alarmEvent.recipient().asString())
            ),
            toDocument(alarmEvent),
            new ReplaceOptions().upsert(true)
        )).then();
    }

    @Override
    public Mono<Void> delete(EventUid eventUid, MailAddress recipient) {
        return Mono.from(collection.deleteOne(
            Filters.and(
                eq("eventUid", eventUid.value()),
                eq("recipient", recipient.asString())
            ))).then();
    }

    @Override
    public Flux<AlarmEvent> findAlarmsToTrigger(Instant time) {
        return Flux.from(collection.find(
            Filters.and(
                lte("alarmTime", Date.from(time)),
                gt("eventStartTime", Date.from(time))
            ))).map(this::fromDocument)
            .filter(e -> time.isBefore(e.eventStartTime()));
    }

    private Document toDocument(AlarmEvent event) {
        Document doc = new Document()
            .append("eventUid", event.eventUid().value())
            .append("alarmTime", Date.from(event.alarmTime()))
            .append("eventStartTime", Date.from(event.eventStartTime()))
            .append("recurring", event.recurring())
            .append("recipient", event.recipient().asString())
            .append("ics", event.ics());
        event.recurrenceId().ifPresent(id -> doc.append("recurrenceId", id));
        return doc;
    }

    private AlarmEvent fromDocument(Document doc) {
        return new AlarmEvent(
            new EventUid(doc.getString("eventUid")),
            doc.getDate("alarmTime").toInstant(),
            doc.getDate("eventStartTime").toInstant(),
            doc.getBoolean("recurring", false),
            Optional.ofNullable(doc.getString("recurrenceId")),
            Throwing.supplier(() -> new MailAddress(doc.getString("recipient"))).get(),
            doc.getString("ics")
        );
    }
}
