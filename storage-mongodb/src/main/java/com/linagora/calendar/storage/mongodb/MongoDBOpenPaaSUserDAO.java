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
import static com.linagora.calendar.storage.mongodb.MongoObjectIds.toObjectId;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.linagora.calendar.storage.MigrationResult;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.exception.UserConflictException;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBOpenPaaSUserDAO implements OpenPaaSUserDAO {
    public static final String COLLECTION = "users";
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBOpenPaaSUserDAO.class);
    private static final String PASSWORD_FIELD = "password";
    private static final List<String> SEARCH_SCALAR_FIELDS = List.of("firstname", "lastname");
    private static final List<String> SEARCH_ARRAY_FIELDS = List.of("accounts.emails", "firstnames");
    private static final Duration SEARCH_MAX_TIME = Duration.ofSeconds(10);
    private static final Splitter QUERY_WORD_SPLITTER = Splitter.on(CharMatcher.whitespace()).omitEmptyStrings();

    private final MongoDatabase database;
    private final MongoDBOpenPaaSDomainDAO domainDAO;

    @Inject
    public MongoDBOpenPaaSUserDAO(MongoDatabase database, MongoDBOpenPaaSDomainDAO domainDAO) {
        this.database = database;
        this.domainDAO = domainDAO;
    }

    @Override
    public Mono<OpenPaaSUser> retrieve(OpenPaaSId id) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq("_id", toObjectId(id)))
                .first())
            .map(document -> new OpenPaaSUser(
                Username.of(document.getList("accounts", Document.class).get(0).getList("emails", String.class).get(0)),
                new OpenPaaSId(document.getObjectId("_id").toHexString()),
                document.getString("firstname"), document.getString("lastname")));
    }

    @Override
    public Mono<OpenPaaSUser> retrieve(Username username) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq("accounts.emails", username.asString()))
                .first())
            .map(document -> new OpenPaaSUser(username, new OpenPaaSId(document.getObjectId("_id").toHexString()),
                document.getString("firstname"), document.getString("lastname")));
    }

    @Override
    public Mono<OpenPaaSUser> add(Username username) {
        return add(username, "", "");
    }

    @Override
    public Mono<OpenPaaSUser> add(Username username, String firstName, String lastName) {
        return domainDAO.retrieve(username.getDomainPart().get())
            .switchIfEmpty(Mono.error(() -> new DomainNotFoundException(username.getDomainPart().get())))
            .map(domain -> new Document()
                .append("firstname", firstName)
                .append("lastname", lastName)
                .append("firstnames", computeFirstnames(firstName))
                .append(PASSWORD_FIELD, generatePasswordPlaceholder())
                .append("email", username.asString()) // not part of OpenPaaS datamodel but helps solve concurrency
                .append("domains", List.of(new Document("domain_id", toObjectId(domain.id()))))
                .append("accounts", List.of(new Document()
                    .append("type", "email")
                    .append("emails", List.of(username.asString())))))
            .flatMap(document -> Mono.from(database.getCollection(COLLECTION).insertOne(document)))
            .map(InsertOneResult::getInsertedId)
            .map(id -> new OpenPaaSUser(username, new OpenPaaSId(id.asObjectId().getValue().toHexString()), firstName, lastName))
            .onErrorResume(MongoWriteException.class, e -> {
                if (e.getError().getCode() == MONGO_DUPLICATE_KEY_CODE) {
                    return Mono.error(new UserConflictException(username));
                }
                return Mono.error(e);
            });
    }

    @Override
    public Mono<Void> update(OpenPaaSId id, Username newUsername, String newFirstname, String newLastname) {
        return domainDAO.retrieve(newUsername.getDomainPart().get())
            .switchIfEmpty(Mono.error(() -> new DomainNotFoundException(newUsername.getDomainPart().get())))
            .flatMap(domain -> Mono.from(database.getCollection(COLLECTION).updateOne(
                    Filters.eq("_id", toObjectId(id)),
                    Updates.combine(
                        Updates.set("firstname", newFirstname),
                        Updates.set("lastname", newLastname),
                        Updates.set("firstnames", computeFirstnames(newFirstname)),
                        Updates.set("email", newUsername.asString()),
                        Updates.set("domains", List.of(new Document("domain_id", toObjectId(domain.id())))),
                        Updates.set("accounts", List.of(new Document()
                            .append("type", "email")
                            .append("emails", List.of(newUsername.asString()))))
                    )))
                .flatMap(result -> {
                    if (result.getMatchedCount() == 0) {
                        return Mono.error(new UserNotFoundException(id));
                    }
                    return Mono.empty();
                })
                .then()
                .onErrorResume(MongoWriteException.class, e -> {
                    if (e.getError().getCode() == MONGO_DUPLICATE_KEY_CODE) {
                        return Mono.error(new UserConflictException(newUsername));
                    }
                    return Mono.error(e);
                })
            );
    }

    @Override
    public Mono<Void> delete(Username username) {
        return Mono.from(database.getCollection(COLLECTION)
                .deleteOne(Filters.or(
                    Filters.eq("email", username.asString()),
                    Filters.eq("accounts.emails", username.asString())
                )))
            .then();
    }

    @Override
    public Flux<OpenPaaSUser> list() {
        return Flux.from(database.getCollection(COLLECTION).find())
            .map(this::toOpenPaaSUser);
    }

    @Override
    public Flux<OpenPaaSUser> listByDomain(Domain domain) {
        return domainDAO.retrieve(domain)
            .flatMapMany(openPaaSDomain ->
                Flux.from(database.getCollection(COLLECTION)
                        .find(Filters.eq("domains.domain_id", toObjectId(openPaaSDomain.id()))))
                    .map(this::toOpenPaaSUser));
    }

    /**
     * Each word of the query has to be the prefix of one of the searched fields: 'jea dupo' finds Jean Dupont.
     * A word made only of what the collation ignores restricts nothing, hence is dropped.
     * Duplicated words are dropped, and only the first {@link #MAX_SEARCH_QUERY_WORDS} words are used.
     */
    @Override
    public Flux<OpenPaaSUser> search(Domain domain, String query, int limit) {
        List<String> words = QUERY_WORD_SPLITTER.splitToList(query);
        List<String> searchableWords = words.stream()
            .filter(CollatedPrefixSearch::isSearchable)
            .distinct()
            .limit(MAX_SEARCH_QUERY_WORDS)
            .toList();
        if (searchableWords.isEmpty() && !words.isEmpty()) {
            return Flux.empty();
        }

        return domainDAO.retrieve(domain)
            .flatMapMany(openPaaSDomain ->
                Flux.from(database.getCollection(COLLECTION)
                        .find(Filters.and(Stream.concat(
                            Stream.of(Filters.eq("domains.domain_id", toObjectId(openPaaSDomain.id()))),
                            searchableWords.stream()
                                .map(word -> CollatedPrefixSearch.filter(word, SEARCH_SCALAR_FIELDS, SEARCH_ARRAY_FIELDS)))
                            .toList()))
                        .collation(CollatedPrefixSearch.COLLATION)
                        .maxTime(SEARCH_MAX_TIME.toMillis(), TimeUnit.MILLISECONDS)
                        .limit(limit))
                    .map(this::toOpenPaaSUser));
    }

    private OpenPaaSUser toOpenPaaSUser(Document document) {
        String email = document
            .getList("accounts", Document.class)
            .getFirst()
            .getList("emails", String.class)
            .getFirst();

        return new OpenPaaSUser(Username.of(email),
            new OpenPaaSId(document.getObjectId("_id").toHexString()),
            document.getString("firstname"),
            document.getString("lastname"));
    }

    private List<String> computeFirstnames(String firstname) {
        if (firstname == null || firstname.trim().isEmpty()) {
            return List.of();
        }
        return Splitter.on(' ')
            .trimResults()
            .omitEmptyStrings()
            .splitToList(firstname);
    }

    private String generatePasswordPlaceholder() {
        return UUID.randomUUID().toString();
    }

    @Override
    public Mono<MigrationResult> addMissingFields() {
        return Flux.from(database.getCollection(COLLECTION).find())
            .flatMap(document -> addMissingFields(document)
                .switchIfEmpty(Mono.just(new MigrationResult(1, 0, 0))))
            .reduce(MigrationResult.empty(),
                (acc, result) -> new MigrationResult(
                    acc.processedUsers() + result.processedUsers(),
                    acc.upgradedUsers() + result.upgradedUsers(),
                    acc.errorCount() + result.errorCount()));
    }

    private Mono<MigrationResult> addMissingFields(Document document) {
        if (document.containsKey("email") && document.containsKey("firstnames")) {
            return Mono.empty();
        }

        Document updates = new Document();

        addMissingEmail(document, updates);
        updates.append("firstnames", computeFirstnames(document.getString("firstname")));

        return Mono.from(database.getCollection(COLLECTION)
                .updateOne(Filters.eq("_id", document.getObjectId("_id")), new Document("$set", updates)))
            .map(result -> new MigrationResult(1, result.getModifiedCount(), 0))
            .onErrorResume(e -> {
                LOGGER.error("Error adding missing fields", e);
                return Mono.just(new MigrationResult(1, 0, 1));
            });
    }

    private static void addMissingEmail(Document document, Document updates) {
        List<Document> accounts = document.getList("accounts", Document.class);
        if (accounts != null && !accounts.isEmpty()) {
            List<String> emails = accounts.get(0).getList("emails", String.class);
            if (emails != null && !emails.isEmpty()) {
                updates.append("email", emails.get(0));
            }
        }
    }
}
