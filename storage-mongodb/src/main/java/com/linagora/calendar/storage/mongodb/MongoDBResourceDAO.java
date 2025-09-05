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

import static com.linagora.calendar.storage.model.Resource.DELETED;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.bson.Document;
import org.bson.types.ObjectId;

import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.ResourceNotFoundException;
import com.linagora.calendar.storage.ResourceUpdateRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBResourceDAO implements ResourceDAO {
    public record Timestamps(Instant creation, Instant updatedAt) {
    }

    public static final String COLLECTION = "resources";
    public static final String ID_FIELD = "_id";
    public static final String ADMINISTRATORS_FIELD = "administrators";
    public static final String CREATOR_FIELD = "creator";
    public static final String DELETED_FIELD = "deleted";
    public static final String DESCRIPTION_FIELD = "description";
    public static final String DOMAIN_FIELD = "domain";
    public static final String ICON_FIELD = "icon";
    public static final String NAME_FIELD = "name";
    public static final String CREATION_FIELD = "creation";
    public static final String UPDATED_FIELD = "updatedAt";
    public static final String TYPE_FIELD = "type";
    public static final String TIMESTAMPS = "timestamps";
    public static final String ADMIN_ID = "id";
    public static final String ADMIN_OBJECT_TYPE = "objectType";

    private final MongoCollection<Document> collection;
    public final Clock clock;

    @Inject
    public MongoDBResourceDAO(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection(COLLECTION);
        this.clock = clock;
        Mono.from(collection.createIndex(ascending(DOMAIN_FIELD), new IndexOptions())).block();
        Mono.from(collection.createIndex(ascending(NAME_FIELD), new IndexOptions())).block();
        Mono.from(collection.createIndex(ascending(DELETED_FIELD), new IndexOptions())).block();
    }

    @Override
    public Mono<ResourceId> insert(ResourceInsertRequest request) {
        Document doc = toDocument(request);
        return Mono.from(collection.insertOne(doc))
            .map(result -> new ResourceId(doc.getObjectId(ID_FIELD).toHexString()));
    }

    @Override
    public Flux<Resource> findAll() {
        return Flux.from(collection.find()).map(this::fromDocument);
    }

    @Override
    public Mono<Resource> findById(ResourceId id) {
        return Mono.from(collection.find(eq(ID_FIELD, new ObjectId(id.value()))).first())
            .map(this::fromDocument);
    }

    @Override
    public Flux<Resource> findByDomain(OpenPaaSId domainId) {
        return Flux.from(collection.find(and(eq(DOMAIN_FIELD, new ObjectId(domainId.value())))))
            .map(this::fromDocument);
    }

    @Override
    public Mono<Resource> update(ResourceId id, ResourceUpdateRequest request) {
        return Mono.from(collection.find(and(eq(ID_FIELD, new ObjectId(id.value())), eq(DELETED_FIELD, !DELETED))).first())
            .switchIfEmpty(Mono.error(new ResourceNotFoundException(id)))
            .map(this::fromDocument)
            .flatMap(existing -> {
                Resource updated = existing.update(request, clock.instant());
                Document doc = toDocument(updated);
                return Mono.from(collection.replaceOne(eq(ID_FIELD, new ObjectId(id.value())), doc, new ReplaceOptions().upsert(false)))
                    .thenReturn(updated);
            });
    }

    @Override
    public Mono<Void> softDelete(ResourceId id) {
        return Mono.from(collection.updateOne(
                and(
                    eq(ID_FIELD, new ObjectId(id.value())),
                    eq(DELETED_FIELD, !DELETED)),
                Updates.set(DELETED_FIELD, DELETED)))
            .flatMap(result -> {
                if (result.getMatchedCount() == 0) {
                    return Mono.error(new ResourceNotFoundException(id));
                }
                return Mono.empty();
            });
    }

    @Override
    public Flux<Resource> search(OpenPaaSId domainId, String keyword, int limit) {
        String regexPattern = ".*" + Pattern.quote(keyword.trim().toLowerCase()) + ".*";
        return Flux.from(collection.find(and(
                eq(DOMAIN_FIELD, new ObjectId(domainId.value())),
                eq(DELETED_FIELD, !DELETED),
                Filters.regex(NAME_FIELD, regexPattern, "i")
            )).limit(limit)).map(this::fromDocument);
    }

    @Override
    public Mono<Boolean> exist(ResourceId resourceId, OpenPaaSId domainId) {
        return Mono.from(collection.find(and(
                eq(ID_FIELD, new ObjectId(resourceId.value())),
                eq(DOMAIN_FIELD, new ObjectId(domainId.value()))
            )).first())
            .filter(doc -> !doc.getBoolean(DELETED_FIELD, !DELETED))
            .map(doc -> true)
            .defaultIfEmpty(false);
    }

    private Document toDocument(ResourceInsertRequest request) {
        Date now = Date.from(clock.instant());
        return new Document().append(ADMINISTRATORS_FIELD, request.administrators().stream().map(this::toDocument).toList())
            .append(CREATOR_FIELD, new ObjectId(request.creator().value()))
            .append(DELETED_FIELD, !DELETED)
            .append(DESCRIPTION_FIELD, request.description())
            .append(DOMAIN_FIELD, new ObjectId(request.domain().value()))
            .append(ICON_FIELD, request.icon())
            .append(NAME_FIELD, request.name())
            .append(TIMESTAMPS, new Document()
                .append(CREATION_FIELD, now)
                .append(UPDATED_FIELD, now))
            .append(TYPE_FIELD, Resource.RESOURCE_TYPE);
    }

    private Document toDocument(Resource resource) {
        Document doc = new Document();
        doc.append(ADMINISTRATORS_FIELD, resource.administrators().stream().map(this::toDocument).toList())
            .append(CREATOR_FIELD, new ObjectId(resource.creator().value()))
            .append(DELETED_FIELD, resource.deleted())
            .append(DESCRIPTION_FIELD, resource.description())
            .append(DOMAIN_FIELD, new ObjectId(resource.domain().value()))
            .append(ICON_FIELD, resource.icon())
            .append(NAME_FIELD, resource.name())
            .append(TIMESTAMPS, new Document()
                .append(CREATION_FIELD, Date.from(resource.creation()))
                .append(UPDATED_FIELD, Date.from(resource.updated())))
            .append(TYPE_FIELD, resource.type());
        return doc;
    }

    private Resource fromDocument(Document doc) {
        Timestamps timestamps = fromTimestampDocument((Document) doc.get(TIMESTAMPS));

        return new Resource(
            new ResourceId(doc.getObjectId(ID_FIELD).toHexString()),
            ((List<Document>) doc.get(ADMINISTRATORS_FIELD)).stream()
                .map(this::fromAdminDocument).toList(),
            new OpenPaaSId(doc.getObjectId(CREATOR_FIELD).toHexString()),
            doc.getBoolean(DELETED_FIELD, !DELETED),
            doc.getString(DESCRIPTION_FIELD),
            new OpenPaaSId(doc.getObjectId(DOMAIN_FIELD).toHexString()),
            doc.getString(ICON_FIELD),
            doc.getString(NAME_FIELD),
            timestamps.creation(),
            timestamps.updatedAt(),
            doc.getString(TYPE_FIELD)
        );
    }

    private Document toDocument(ResourceAdministrator admin) {
        return new Document()
            .append(ADMIN_ID, admin.refId().value())
            .append(ADMIN_OBJECT_TYPE, admin.objectType());
    }

    private ResourceAdministrator fromAdminDocument(Document doc) {
        return new ResourceAdministrator(
            new OpenPaaSId(doc.getString(ADMIN_ID)),
            doc.getString(ADMIN_OBJECT_TYPE)
        );
    }

    private Timestamps fromTimestampDocument(Document doc) {
        return new Timestamps(
            doc.getDate(CREATION_FIELD).toInstant(),
            doc.getDate(UPDATED_FIELD).toInstant()
        );
    }
}
