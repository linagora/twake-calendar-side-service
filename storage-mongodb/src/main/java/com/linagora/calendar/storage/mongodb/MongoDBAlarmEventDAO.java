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
import com.linagora.calendar.storage.event.AlarmAction;
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
    public static final String EVENT_UID_FIELD = "eventUid";
    public static final String RECIPIENT_FIELD = "recipient";
    public static final String ALARM_TIME_FIELD = "alarmTime";
    public static final String EVENT_START_TIME_FIELD = "eventStartTime";
    public static final String RECURRING_FIELD = "recurring";
    public static final String ICS_FIELD = "ics";
    public static final String RECURRENCE_ID_FIELD = "recurrenceId";
    public static final String EVENT_PATH_FIELD = "eventPath";
    public static final String ACTION_FIELD = "action";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoDBAlarmEventDAO(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        Mono.from(collection.createIndex(ascending(EVENT_UID_FIELD, RECIPIENT_FIELD), new IndexOptions()
            .unique(true))).block();
        Mono.from(collection.createIndex(ascending(ALARM_TIME_FIELD), new IndexOptions())).block();
    }

    @Override
    public Mono<AlarmEvent> find(EventUid eventUid, MailAddress recipient) {
        return Mono.from(collection.find(
            Filters.and(
                eq(EVENT_UID_FIELD, eventUid.value()),
                eq(RECIPIENT_FIELD, recipient.asString())
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
                eq(EVENT_UID_FIELD, alarmEvent.eventUid().value()),
                eq(RECIPIENT_FIELD, alarmEvent.recipient().asString())
            ),
            toDocument(alarmEvent),
            new ReplaceOptions().upsert(true)
        )).then();
    }

    @Override
    public Mono<Void> delete(EventUid eventUid, MailAddress recipient) {
        return Mono.from(collection.deleteOne(
            Filters.and(
                eq(EVENT_UID_FIELD, eventUid.value()),
                eq(RECIPIENT_FIELD, recipient.asString())
            ))).then();
    }

    @Override
    public Flux<AlarmEvent> findAlarmsToTrigger(Instant time) {
        return Flux.from(collection.find(
            Filters.and(
                lte(ALARM_TIME_FIELD, Date.from(time))
            ))).map(this::fromDocument);
    }

    private Document toDocument(AlarmEvent event) {
        Document doc = new Document()
            .append(EVENT_UID_FIELD, event.eventUid().value())
            .append(ALARM_TIME_FIELD, Date.from(event.alarmTime()))
            .append(EVENT_START_TIME_FIELD, Date.from(event.eventStartTime()))
            .append(RECURRING_FIELD, event.recurring())
            .append(RECIPIENT_FIELD, event.recipient().asString())
            .append(ICS_FIELD, event.ics())
            .append(EVENT_PATH_FIELD, event.eventPath())
            .append(ACTION_FIELD, event.action().getValue());
        event.recurrenceId().ifPresent(id -> doc.append(RECURRENCE_ID_FIELD, id));
        return doc;
    }

    private AlarmEvent fromDocument(Document doc) {
        return new AlarmEvent(
            new EventUid(doc.getString(EVENT_UID_FIELD)),
            doc.getDate(ALARM_TIME_FIELD).toInstant(),
            doc.getDate(EVENT_START_TIME_FIELD).toInstant(),
            doc.getBoolean(RECURRING_FIELD, false),
            Optional.ofNullable(doc.getString(RECURRENCE_ID_FIELD)),
            Throwing.supplier(() -> new MailAddress(doc.getString(RECIPIENT_FIELD))).get(),
            doc.getString(ICS_FIELD),
            Optional.ofNullable(doc.getString(EVENT_PATH_FIELD)).orElse(""),
            Optional.ofNullable(doc.getString(ACTION_FIELD))
                .map(alarmActionStr -> AlarmAction.fromString(alarmActionStr)
                    .orElseThrow(() -> new IllegalArgumentException("Unexpected error parsing AlarmAction: " + alarmActionStr)))
                .orElse(AlarmAction.EMAIL)
        );
    }
}
