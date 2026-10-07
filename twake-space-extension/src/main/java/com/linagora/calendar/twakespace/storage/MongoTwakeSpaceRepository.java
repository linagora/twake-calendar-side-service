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

package com.linagora.calendar.twakespace.storage;

import static com.linagora.calendar.storage.mongodb.MongoConstants.MONGO_DUPLICATE_KEY_CODE;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.bson.Document;
import org.bson.conversions.Bson;

import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceId;
import com.linagora.calendar.twakespace.model.TwakeSpace;
import com.mongodb.MongoWriteException;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

// Merges read the space, merge in memory and write it back if no other merge wrote it meanwhile, else retry.
public class MongoTwakeSpaceRepository implements TwakeSpaceRepository {
    public static final String COLLECTION = "twake_spaces";
    private static final String ID_FIELD = "_id";
    private static final String ORGANIZATION_FIELD = "organization";
    private static final String DOMAIN_FIELD = "domain";
    private static final String NAME_FIELD = "name";
    private static final String MEMBERS_FIELD = "members";
    private static final String EMAIL_FIELD = "email";
    private static final String ROLE_FIELD = "role";
    private static final String VALUE_FIELD = "value";
    private static final String TIMESTAMP_FIELD = "timestamp";
    private static final String DELETION_FIELD = "deletion";
    private static final String VERSION_FIELD = "version";
    private static final long UNVERSIONED = 0L;
    private static final int MAX_MERGE_ATTEMPTS = 20;

    private record Versioned(TwakeSpace space, long version) {
    }

    private static class ConcurrentMergeException extends RuntimeException {
    }

    private final MongoCollection<Document> collection;

    @Inject
    public MongoTwakeSpaceRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    @Override
    public Mono<TwakeSpace> merge(TwakeSpace change) {
        return Mono.defer(() -> retrieveVersioned(change.id())
                .flatMap(stored -> replace(stored.space().merge(change), stored.version()))
                .switchIfEmpty(Mono.defer(() -> insert(change))))
            .retryWhen(Retry.backoff(MAX_MERGE_ATTEMPTS, Duration.ofMillis(5))
                .filter(ConcurrentMergeException.class::isInstance));
    }

    @Override
    public Mono<TwakeSpace> retrieve(SpaceId spaceId) {
        return retrieveVersioned(spaceId).map(Versioned::space);
    }

    private Mono<Versioned> retrieveVersioned(SpaceId spaceId) {
        return Mono.from(collection.find(eq(ID_FIELD, spaceId.value())).first())
            .map(document -> new Versioned(toSpace(document), document.get(VERSION_FIELD, UNVERSIONED)));
    }

    private Mono<TwakeSpace> replace(TwakeSpace space, long version) {
        Bson sameVersion = version == UNVERSIONED ? exists(VERSION_FIELD, false) : eq(VERSION_FIELD, version);
        return Mono.from(collection.replaceOne(and(eq(ID_FIELD, space.id().value()), sameVersion), toDocument(space, version + 1)))
            .flatMap(result -> result.getMatchedCount() == 1 ? Mono.just(space) : Mono.error(new ConcurrentMergeException()));
    }

    private Mono<TwakeSpace> insert(TwakeSpace space) {
        return Mono.from(collection.insertOne(toDocument(space, UNVERSIONED + 1)))
            .thenReturn(space)
            .onErrorMap(MongoWriteException.class, e -> e.getError().getCode() == MONGO_DUPLICATE_KEY_CODE ? new ConcurrentMergeException() : e);
    }

    private static Document toDocument(TwakeSpace space, long version) {
        Document document = new Document(ID_FIELD, space.id().value())
            .append(VERSION_FIELD, version);
        space.organization().ifPresent(organization -> document.append(ORGANIZATION_FIELD, organization.value()));
        space.domain().ifPresent(domain -> document.append(DOMAIN_FIELD, domain.asString()));
        space.name().ifPresent(name -> document.append(NAME_FIELD, new Document(VALUE_FIELD, name.value())
            .append(TIMESTAMP_FIELD, Date.from(name.timestamp()))));
        document.append(MEMBERS_FIELD, space.members().entrySet().stream()
            .map(entry -> {
                Document member = new Document(EMAIL_FIELD, entry.getKey().asString())
                    .append(TIMESTAMP_FIELD, Date.from(entry.getValue().timestamp()));
                entry.getValue().role().ifPresent(role -> member.append(ROLE_FIELD, role));
                return member;
            })
            .toList());
        space.deletion().ifPresent(deletion -> document.append(DELETION_FIELD, Date.from(deletion)));
        return document;
    }

    private static TwakeSpace toSpace(Document document) {
        Map<Username, TwakeSpace.Membership> members = document.getList(MEMBERS_FIELD, Document.class, List.of()).stream()
            .collect(Collectors.toMap(member -> Username.of(member.getString(EMAIL_FIELD)),
                member -> new TwakeSpace.Membership(Optional.ofNullable(member.getString(ROLE_FIELD)),
                    member.getDate(TIMESTAMP_FIELD).toInstant())));
        return new TwakeSpace(new SpaceId(document.getString(ID_FIELD)),
            Optional.ofNullable(document.getString(ORGANIZATION_FIELD)).map(OrganizationId::new),
            Optional.ofNullable(document.getString(DOMAIN_FIELD)).map(Domain::of),
            Optional.ofNullable(document.get(NAME_FIELD, Document.class))
                .map(name -> new TwakeSpace.Name(name.getString(VALUE_FIELD), name.getDate(TIMESTAMP_FIELD).toInstant())),
            members,
            Optional.ofNullable(document.getDate(DELETION_FIELD)).map(Date::toInstant));
    }
}
