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

import java.time.Clock;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventtoken.EventToken;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;
import com.linagora.calendar.storage.eventtoken.EventTokenId;
import com.linagora.calendar.storage.eventtoken.EventTokenRight;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBEventTokenDAO implements EventTokenDAO {

    public static final String COLLECTION = "event_tokens";

    private static final String FIELD_TOKEN = "token";
    private static final String FIELD_CALENDAR_HOME_ID = "calendarHomeId";
    private static final String FIELD_CALENDAR_ID = "calendarId";
    private static final String FIELD_EVENT_ID = "eventId";
    private static final String FIELD_RIGHT = "right";
    private static final String FIELD_EXPIRATION = "expiration";

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public MongoDBEventTokenDAO(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection(COLLECTION);
        this.clock = clock;
    }

    public static Mono<Void> declareIndex(MongoCollection<Document> collection) {
        return Mono.from(collection.createIndex(Indexes.ascending(FIELD_TOKEN), new IndexOptions().unique(true)))
            .then(Mono.from(collection.createIndex(Indexes.ascending(FIELD_CALENDAR_HOME_ID, FIELD_CALENDAR_ID, FIELD_EVENT_ID))))
            .then(Mono.from(collection.createIndex(Indexes.ascending(FIELD_EXPIRATION),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS).name("event_tokens_ttl_expiration"))))
            .then();
    }

    @Override
    public Mono<Void> insert(EventToken eventToken) {
        return Mono.from(collection.insertOne(toDocument(eventToken))).then();
    }

    @Override
    public Mono<EventToken> findById(EventTokenId id) {
        return Mono.from(collection.find(Filters.and(Filters.eq(FIELD_TOKEN, id.value()), notExpired())).first())
            .map(this::fromDocument);
    }

    @Override
    public Flux<EventToken> listByEvent(CalendarURL calendarURL, String eventId) {
        return Flux.from(collection.find(Filters.and(
                Filters.eq(FIELD_CALENDAR_HOME_ID, calendarURL.base().value()),
                Filters.eq(FIELD_CALENDAR_ID, calendarURL.calendarId().value()),
                Filters.eq(FIELD_EVENT_ID, eventId),
                notExpired())))
            .map(this::fromDocument);
    }

    @Override
    public Mono<Void> delete(EventTokenId id) {
        return Mono.from(collection.deleteOne(Filters.eq(FIELD_TOKEN, id.value()))).then();
    }

    // The TTL monitor removes expired documents only periodically: reads filter them out themselves.
    private Bson notExpired() {
        return Filters.gt(FIELD_EXPIRATION, Date.from(clock.instant()));
    }

    private Document toDocument(EventToken eventToken) {
        return new Document()
            .append(FIELD_TOKEN, eventToken.id().value())
            .append(FIELD_CALENDAR_HOME_ID, eventToken.calendarURL().base().value())
            .append(FIELD_CALENDAR_ID, eventToken.calendarURL().calendarId().value())
            .append(FIELD_EVENT_ID, eventToken.eventId())
            .append(FIELD_RIGHT, eventToken.right().name())
            .append(FIELD_EXPIRATION, Date.from(eventToken.expiration()));
    }

    private EventToken fromDocument(Document document) {
        return EventToken.builder()
            .id(new EventTokenId(document.getString(FIELD_TOKEN)))
            .calendarURL(new CalendarURL(new OpenPaaSId(document.getString(FIELD_CALENDAR_HOME_ID)),
                new OpenPaaSId(document.getString(FIELD_CALENDAR_ID))))
            .eventId(document.getString(FIELD_EVENT_ID))
            .right(EventTokenRight.fromString(document.getString(FIELD_RIGHT)))
            .expiration(document.getDate(FIELD_EXPIRATION).toInstant())
            .build();
    }
}
