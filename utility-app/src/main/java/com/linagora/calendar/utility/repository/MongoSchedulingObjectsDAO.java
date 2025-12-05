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

package com.linagora.calendar.utility.repository;

import java.time.Instant;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.bson.Document;
import org.bson.types.ObjectId;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoSchedulingObjectsDAO {

    private static final String COLLECTION_NAME = "schedulingobjects";
    private static final String FIELD_LAST_MODIFIED = "lastmodified";
    private static final String FIELD_ID = "_id";
    private static final Document SORT_BY_ID_ASC = new Document(FIELD_ID, 1);

    private final MongoCollection<Document> collection;

    public MongoSchedulingObjectsDAO(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME);
    }

    public Flux<ObjectId> findOlderThan(Instant threshold) {
        long epochSeconds = threshold.getEpochSecond();

        return Flux.from(collection.find(Filters.lt(FIELD_LAST_MODIFIED, epochSeconds))
                .sort(SORT_BY_ID_ASC)
                .projection(Projections.include(FIELD_ID)))
            .map(doc -> doc.getObjectId(FIELD_ID));
    }

    public Mono<Void> deleteByIds(List<ObjectId> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Mono.empty();
        }

        return Mono.from(collection.deleteMany(Filters.in(FIELD_ID, ids)))
            .then();
    }

    public Mono<Long> countOlderThan(Instant threshold) {
        long epochSeconds = threshold.getEpochSecond();
        return Mono.from(collection.countDocuments(Filters.lt(FIELD_LAST_MODIFIED, epochSeconds)));
    }
}
