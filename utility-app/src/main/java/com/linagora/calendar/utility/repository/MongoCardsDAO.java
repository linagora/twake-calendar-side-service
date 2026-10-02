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

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Contacts (vCards) of the esn-sabre database.
 */
public class MongoCardsDAO {

    public record Card(ObjectId id, String cardData) {
    }

    public record SortName(ObjectId id, String fnSort) {
    }

    private static final String COLLECTION_NAME = "cards";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_CARD_DATA = "carddata";
    private static final String FIELD_FN_SORT = "fn_sort";
    private static final Bson WITHOUT_SORT_NAME = Filters.exists(FIELD_FN_SORT, false);

    private final MongoCollection<Document> collection;

    public MongoCardsDAO(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME);
    }

    public Mono<Long> countAll() {
        return Mono.from(collection.countDocuments());
    }

    public Mono<Long> countWithoutSortName() {
        return Mono.from(collection.countDocuments(WITHOUT_SORT_NAME));
    }

    /**
     * Cards stored before esn-sabre stored their sortable full name, read in _id order through a single cursor.
     */
    public Flux<Card> findWithoutSortName(int batchSize) {
        return Flux.from(collection.find(WITHOUT_SORT_NAME)
                .projection(Projections.include(FIELD_CARD_DATA))
                .sort(Sorts.ascending(FIELD_ID))
                .batchSize(batchSize))
            .map(doc -> new Card(doc.getObjectId(FIELD_ID), cardData(doc)));
    }

    /**
     * Stores sortable full names, leaving untouched the cards that got one meanwhile: esn-sabre computes it from
     * the latest version of the card when the card is written.
     *
     * @return the number of updated cards
     */
    public Mono<Long> setSortNames(List<SortName> sortNames) {
        if (sortNames.isEmpty()) {
            return Mono.just(0L);
        }

        List<WriteModel<Document>> updates = sortNames.stream()
            .<WriteModel<Document>>map(sortName -> new UpdateOneModel<>(
                Filters.and(Filters.eq(FIELD_ID, sortName.id()), WITHOUT_SORT_NAME),
                Updates.set(FIELD_FN_SORT, sortName.fnSort())))
            .toList();

        return Mono.from(collection.bulkWrite(updates, new BulkWriteOptions().ordered(false)))
            .map(result -> (long) result.getModifiedCount());
    }

    private static String cardData(Document doc) {
        return doc.getString(FIELD_CARD_DATA);
    }
}
