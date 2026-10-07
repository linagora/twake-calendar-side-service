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

package com.linagora.calendar.twakespace;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.bson.Document;

import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class TwakeSpaceRepository {
    // Spaces recorded before the organization was stored have none.
    public record TwakeSpace(SpaceId id, Optional<OrganizationId> organization, Domain domain, Optional<Instant> deletion) {
    }

    public static final String COLLECTION = "twake_spaces";
    private static final String ID_FIELD = "_id";
    private static final String ORGANIZATION_FIELD = "organization";
    private static final String DOMAIN_FIELD = "domain";
    private static final String DELETION_FIELD = "deletion";

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public TwakeSpaceRepository(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection(COLLECTION);
        this.clock = clock;
    }

    public Mono<Void> save(SpaceId spaceId, OrganizationId organization, Domain domain) {
        return Mono.from(collection.replaceOne(eq(ID_FIELD, spaceId.value()),
                new Document(ID_FIELD, spaceId.value())
                    .append(ORGANIZATION_FIELD, organization.value())
                    .append(DOMAIN_FIELD, domain.asString()),
                new ReplaceOptions().upsert(true)))
            .then();
    }

    public Mono<Void> markDeleted(SpaceId spaceId) {
        return Mono.from(collection.updateOne(and(eq(ID_FIELD, spaceId.value()), exists(DELETION_FIELD, false)),
                Updates.set(DELETION_FIELD, Date.from(clock.instant()))))
            .then();
    }

    public Mono<TwakeSpace> retrieve(SpaceId spaceId) {
        return Mono.from(collection.find(eq(ID_FIELD, spaceId.value())).first())
            .map(document -> new TwakeSpace(new SpaceId(document.getString(ID_FIELD)),
                Optional.ofNullable(document.getString(ORGANIZATION_FIELD)).map(OrganizationId::new),
                Domain.of(document.getString(DOMAIN_FIELD)),
                Optional.ofNullable(document.getDate(DELETION_FIELD)).map(Date::toInstant)));
    }
}
