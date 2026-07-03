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

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import java.time.Clock;
import java.util.Date;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.bson.Document;
import org.bson.types.ObjectId;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.TeamCalendarNotFoundException;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBTeamCalendarRepository implements TeamCalendarRepository {
    public static final String COLLECTION = "team_calendars";

    private static final String ID_FIELD = "_id";
    private static final String DOMAIN_ID_FIELD = "domainId";
    private static final String DOMAIN_NAME_FIELD = "domainName";
    private static final String NAME_FIELD = "name";
    private static final String DISPLAY_NAME_FIELD = "displayName";
    private static final String CREATION_FIELD = "creation";
    private static final String UPDATED_FIELD = "updated";

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public MongoDBTeamCalendarRepository(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection(COLLECTION);
        this.clock = clock;
    }

    public static Mono<Void> declareIndex(MongoCollection<Document> collection) {
        return Mono.from(collection.createIndex(Indexes.ascending(DOMAIN_ID_FIELD), new IndexOptions()))
            .then(Mono.from(collection.createIndex(Indexes.ascending(DOMAIN_ID_FIELD, NAME_FIELD), new IndexOptions())))
            .then();
    }

    @Override
    public Mono<TeamCalendar> create(TeamCalendarInsertRequest request) {
        Document document = toDocument(request);
        return Mono.from(collection.insertOne(document))
            .thenReturn(fromDocument(document));
    }

    @Override
    public Mono<Void> delete(TeamCalendarId id) {
        return Mono.justOrEmpty(asObjectId(id))
            .flatMap(objectId -> Mono.from(collection.deleteOne(eq(ID_FIELD, objectId))))
            .then();
    }

    @Override
    public Mono<TeamCalendar> retrieve(TeamCalendarId id) {
        return Mono.justOrEmpty(asObjectId(id))
            .flatMap(objectId -> Mono.from(collection.find(eq(ID_FIELD, objectId)).first()))
            .map(this::fromDocument);
    }

    @Override
    public Flux<TeamCalendar> retrieve(OpenPaaSId domainId, String name) {
        return Mono.justOrEmpty(asObjectId(domainId))
            .flatMapMany(domainObjectId -> Flux.from(collection.find(and(
                eq(DOMAIN_ID_FIELD, domainObjectId),
                eq(NAME_FIELD, name)))))
            .map(this::fromDocument);
    }

    @Override
    public Mono<Boolean> exists(OpenPaaSId domainId, String name) {
        return Mono.justOrEmpty(asObjectId(domainId))
            .flatMap(domainObjectId -> Mono.from(collection.find(and(
                    eq(DOMAIN_ID_FIELD, domainObjectId),
                    eq(NAME_FIELD, name)))
                .projection(Projections.include(ID_FIELD))
                .first()))
            .map(ignored -> true)
            .defaultIfEmpty(false);
    }

    @Override
    public Flux<TeamCalendar> listByDomain(OpenPaaSId domainId) {
        return Mono.justOrEmpty(asObjectId(domainId))
            .flatMapMany(domainObjectId -> Flux.from(collection.find(eq(DOMAIN_ID_FIELD, domainObjectId))))
            .map(this::fromDocument);
    }

    @Override
    public Mono<TeamCalendar> updateDisplayName(TeamCalendarId id, String displayName) {
        return Mono.justOrEmpty(asObjectId(id))
            .flatMap(objectId -> Mono.from(collection.findOneAndUpdate(
                eq(ID_FIELD, objectId),
                Updates.combine(Updates.set(DISPLAY_NAME_FIELD, displayName),
                    Updates.set(UPDATED_FIELD, Date.from(clock.instant()))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))))
            .switchIfEmpty(Mono.error(() -> new TeamCalendarNotFoundException(id)))
            .map(this::fromDocument);
    }

    private Optional<ObjectId> asObjectId(TeamCalendarId id) {
        return asObjectId(id.value());
    }

    private Optional<ObjectId> asObjectId(OpenPaaSId id) {
        return asObjectId(id.value());
    }

    private Optional<ObjectId> asObjectId(String value) {
        try {
            return Optional.of(new ObjectId(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Document toDocument(TeamCalendarInsertRequest request) {
        Date now = Date.from(clock.instant());
        return new Document()
            .append(ID_FIELD, new ObjectId())
            .append(DOMAIN_ID_FIELD, new ObjectId(request.domain().id().value()))
            .append(DOMAIN_NAME_FIELD, request.domain().domain().asString())
            .append(NAME_FIELD, request.name())
            .append(DISPLAY_NAME_FIELD, request.displayName())
            .append(CREATION_FIELD, now)
            .append(UPDATED_FIELD, now);
    }

    private TeamCalendar fromDocument(Document document) {
        OpenPaaSDomain domain = new OpenPaaSDomain(Domain.of(document.getString(DOMAIN_NAME_FIELD)),
            new OpenPaaSId(document.getObjectId(DOMAIN_ID_FIELD).toHexString()));

        return new TeamCalendar(new TeamCalendarId(document.getObjectId(ID_FIELD).toHexString()),
            domain,
            document.getString(NAME_FIELD),
            document.getString(DISPLAY_NAME_FIELD),
            document.getDate(CREATION_FIELD).toInstant(),
            document.getDate(UPDATED_FIELD).toInstant());
    }
}
