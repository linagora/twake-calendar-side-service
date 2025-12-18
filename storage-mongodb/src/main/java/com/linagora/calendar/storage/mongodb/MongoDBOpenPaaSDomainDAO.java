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

import static com.linagora.calendar.storage.mongodb.MongoConstants.MONGO_DUPLICATE_KEY_CODE;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.bson.Document;
import org.bson.types.ObjectId;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.linagora.calendar.storage.DomainAdministrator;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBOpenPaaSDomainDAO implements OpenPaaSDomainDAO, OpenPaaSDomainAdminDAO {
    public static final String COLLECTION = "domains";

    private static final String FIELD_ID = "_id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_COMPANY_NAME = "company_name";
    private static final String FIELD_HOSTNAMES = "hostnames";
    private static final String FIELD_ADMINISTRATORS = "administrators";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_TIMESTAMPS = "timestamps";
    private static final String FIELD_CREATION = "creation";
    private static final String FIELD_TIMESTAMP = "timestamp";

    private final MongoDatabase database;

    @Inject
    public MongoDBOpenPaaSDomainDAO(MongoDatabase database) {
        this.database = database;
    }

    @Override
    public Mono<OpenPaaSDomain> retrieve(OpenPaaSId id) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_ID, new ObjectId(id.value())))
                .first())
            .map(document -> new OpenPaaSDomain(Domain.of(document.getString(FIELD_NAME)), id));
    }

    @Override
    public Mono<OpenPaaSDomain> retrieve(Domain domain) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_NAME, domain.asString()))
            .first())
            .map(document -> new OpenPaaSDomain(domain, new OpenPaaSId(document.getObjectId(FIELD_ID).toHexString())));
    }

    @Override
    public Mono<OpenPaaSDomain> add(Domain domain) {
        Document document = new Document()
            .append(FIELD_TIMESTAMP, new Document()
                .append(FIELD_CREATION, new Date()))
            .append(FIELD_HOSTNAMES, ImmutableList.of())
            .append(FIELD_NAME, domain.asString())
            .append(FIELD_COMPANY_NAME, domain.asString())
            .append(FIELD_ADMINISTRATORS, ImmutableList.of());

        return Mono.from(database.getCollection(COLLECTION).insertOne(document))
            .map(InsertOneResult::getInsertedId)
            .map(id -> new OpenPaaSDomain(domain, new OpenPaaSId(id.asObjectId().getValue().toHexString())))
            .onErrorResume(MongoWriteException.class, e -> {
                if (e.getError().getCode() == MONGO_DUPLICATE_KEY_CODE) {
                    return Mono.error(new IllegalStateException(domain.asString() + " already exists", e));
                }
                return Mono.error(e);
            });
    }

    @Override
    public Flux<OpenPaaSDomain> list() {
        return Flux.from(database.getCollection(COLLECTION).find())
            .map(document -> new OpenPaaSDomain(
                Domain.of(document.getString(FIELD_NAME)),
                new OpenPaaSId(document.getObjectId(FIELD_ID).toHexString())));
    }

    @Override
    public Flux<DomainAdministrator> listAdmins(OpenPaaSId domainId) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_ID, new ObjectId(domainId.value())))
                .first())
            .switchIfEmpty(Mono.error(new DomainNotFoundException(domainId)))
            .flatMapIterable(document -> document.getList(FIELD_ADMINISTRATORS, Document.class))
            .map(adminDoc -> new DomainAdministrator(
                new OpenPaaSId(adminDoc.getObjectId(FIELD_USER_ID).toHexString()),
                fromTimestampDocument(adminDoc)));
    }

    @Override
    public Mono<Void> revokeAdmin(OpenPaaSId domainId, OpenPaaSId userId) {
        return Mono.from(database.getCollection(COLLECTION).updateOne(
                Filters.eq(FIELD_ID, new ObjectId(domainId.value())),
                Updates.pull(FIELD_ADMINISTRATORS,
                    new Document(FIELD_USER_ID, new ObjectId(userId.value())))))
            .then();
    }

    @Override
    public Mono<Void> addAdmins(OpenPaaSId domainId, List<OpenPaaSId> userIdList) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_ID, new ObjectId(domainId.value())))
                .first())
            .flatMap(document -> {
                List<Document> newAdmins = computeNewAdmins(document, userIdList);
                if (newAdmins.isEmpty()) {
                    return Mono.empty();
                }
                return pushNewAdmins(domainId, newAdmins);
            });
    }

    private Mono<Void> pushNewAdmins(OpenPaaSId domainId, List<Document> newAdmins) {
        return Mono.from(database.getCollection(COLLECTION).updateOne(
                Filters.eq(FIELD_ID, new ObjectId(domainId.value())),
                Updates.pushEach(FIELD_ADMINISTRATORS, newAdmins)))
            .then();
    }

    private List<Document> computeNewAdmins(Document domainDoc, List<OpenPaaSId> userIdList) {
        Set<String> existingAdminIds = domainDoc.getList(FIELD_ADMINISTRATORS, Document.class).stream()
            .map(doc -> doc.getObjectId(FIELD_USER_ID).toHexString())
            .collect(Collectors.toSet());

        return Sets.difference(
                userIdList.stream().map(OpenPaaSId::value).collect(Collectors.toSet()),
                existingAdminIds)
            .stream()
            .filter(Objects::nonNull)
            .map(ObjectId::new)
            .map(objectId -> new Document()
                .append(FIELD_USER_ID, objectId)
                .append(FIELD_TIMESTAMPS,
                    new Document(FIELD_CREATION, new Date())))
            .toList();
    }

    private Instant fromTimestampDocument(Document doc) {
        Document timestamps = doc.get(FIELD_TIMESTAMPS, Document.class);
        return timestamps.getDate(FIELD_CREATION).toInstant();
    }
}
