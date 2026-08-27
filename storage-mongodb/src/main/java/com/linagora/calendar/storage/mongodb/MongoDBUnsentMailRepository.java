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
import static com.mongodb.client.model.Indexes.ascending;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.unsent.UnsentMailRepository;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBUnsentMailRepository implements UnsentMailRepository {
    public static final String COLLECTION = "twake_calendar_unsent_mails";

    private static final String FIELD_ID = "_id";
    private static final String FIELD_MAIL_FROM = "mailFrom";
    private static final String FIELD_RCPT_TO = "rcptTo";
    private static final String FIELD_MIME_MESSAGE = "mimeMessage";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_SENDING_TRIALS = "sendingTrials";
    private static final String FIELD_TRIAL_DATE = "date";
    private static final String FIELD_TRIAL_ERROR_MESSAGE = "errorMessage";

    private final MongoCollection<Document> collection;
    private final Clock clock;

    @Inject
    public MongoDBUnsentMailRepository(MongoDatabase database, Clock clock) {
        this.collection = database.getCollection(COLLECTION);
        this.clock = clock;
        declareIndexes().block();
    }

    private Mono<Void> declareIndexes() {
        return Mono.from(collection.createIndex(ascending(FIELD_CREATED_AT)))
            .then(Mono.from(collection.createIndex(ascending(FIELD_MAIL_FROM))))
            .then(Mono.from(collection.createIndex(ascending(FIELD_RCPT_TO))))
            .then();
    }

    @Override
    public Mono<UnsentMailId> store(Optional<MailAddress> mailFrom, List<MailAddress> rcptTo,
                                    byte[] mimeMessage, SendingTrial firstTrial) {
        return Mono.fromCallable(() -> new UnsentMail(UnsentMailId.generate(), mailFrom, rcptTo, mimeMessage,
                clock.instant(), ImmutableList.of(firstTrial)))
            .flatMap(unsentMail -> Mono.from(collection.insertOne(toDocument(unsentMail)))
                .thenReturn(unsentMail.id()));
    }

    @Override
    public Mono<UnsentMail> read(UnsentMailId id) {
        return Mono.from(collection.find(eq(FIELD_ID, id.value())).first())
            .map(this::fromDocument);
    }

    @Override
    public Flux<UnsentMailId> list(UnsentMailQuery query) {
        return Flux.from(applyLimit(collection.find(toFilter(query))
                .projection(Projections.include(FIELD_ID))
                .sort(ascending(FIELD_CREATED_AT)), query))
            .map(document -> new UnsentMailId(document.getString(FIELD_ID)));
    }

    @Override
    public Flux<UnsentMail> search(UnsentMailQuery query) {
        return Flux.from(applyLimit(collection.find(toFilter(query))
                .sort(ascending(FIELD_CREATED_AT)), query))
            .map(this::fromDocument);
    }

    @Override
    public Mono<Void> appendTrial(UnsentMailId id, SendingTrial trial) {
        return Mono.from(collection.updateOne(eq(FIELD_ID, id.value()),
                Updates.pushEach(FIELD_SENDING_TRIALS, ImmutableList.of(toDocument(trial)),
                    new PushOptions().slice(-UnsentMail.MAX_RETAINED_TRIALS))))
            .then();
    }

    @Override
    public Mono<Void> delete(UnsentMailId id) {
        return Mono.from(collection.deleteOne(eq(FIELD_ID, id.value()))).then();
    }

    @Override
    public Mono<Void> deleteAll() {
        return Mono.from(collection.deleteMany(Filters.empty())).then();
    }

    @Override
    public Mono<Long> count(UnsentMailQuery query) {
        return Mono.from(collection.countDocuments(toFilter(query),
            query.limit().map(limit -> new CountOptions().limit(limit))
                .orElseGet(CountOptions::new)));
    }

    private FindPublisher<Document> applyLimit(FindPublisher<Document> findPublisher, UnsentMailQuery query) {
        return query.limit()
            .map(findPublisher::limit)
            .orElse(findPublisher);
    }

    private Bson toFilter(UnsentMailQuery query) {
        ImmutableList.Builder<Bson> filters = ImmutableList.builder();
        query.sender().ifPresent(sender -> filters.add(eq(FIELD_MAIL_FROM, sender.asString())));
        query.recipient().ifPresent(recipient -> filters.add(eq(FIELD_RCPT_TO, recipient.asString())));

        return Optional.of(filters.build())
            .filter(list -> !list.isEmpty())
            .<Bson>map(Filters::and)
            .orElseGet(Filters::empty);
    }

    private Document toDocument(UnsentMail unsentMail) {
        Document document = new Document()
            .append(FIELD_ID, unsentMail.id().value())
            .append(FIELD_RCPT_TO, unsentMail.rcptTo().stream()
                .map(MailAddress::asString)
                .collect(ImmutableList.toImmutableList()))
            .append(FIELD_MIME_MESSAGE, new Binary(unsentMail.mimeMessage()))
            .append(FIELD_CREATED_AT, Date.from(unsentMail.createdAt()))
            .append(FIELD_SENDING_TRIALS, unsentMail.sendingTrials().stream()
                .map(this::toDocument)
                .collect(ImmutableList.toImmutableList()));
        unsentMail.mailFrom().ifPresent(mailFrom -> document.append(FIELD_MAIL_FROM, mailFrom.asString()));
        return document;
    }

    private Document toDocument(SendingTrial trial) {
        return new Document()
            .append(FIELD_TRIAL_DATE, Date.from(trial.date()))
            .append(FIELD_TRIAL_ERROR_MESSAGE, trial.errorMessage());
    }

    private UnsentMail fromDocument(Document document) {
        return new UnsentMail(new UnsentMailId(document.getString(FIELD_ID)),
            Optional.ofNullable(document.getString(FIELD_MAIL_FROM))
                .map(Throwing.function(MailAddress::new)),
            document.getList(FIELD_RCPT_TO, String.class).stream()
                .map(Throwing.function(MailAddress::new))
                .collect(ImmutableList.toImmutableList()),
            document.get(FIELD_MIME_MESSAGE, Binary.class).getData(),
            document.getDate(FIELD_CREATED_AT).toInstant(),
            Optional.ofNullable(document.getList(FIELD_SENDING_TRIALS, Document.class))
                .orElse(ImmutableList.of())
                .stream()
                .map(trial -> new SendingTrial(trial.getDate(FIELD_TRIAL_DATE).toInstant(),
                    trial.getString(FIELD_TRIAL_ERROR_MESSAGE)))
                .collect(ImmutableList.toImmutableList()));
    }
}
