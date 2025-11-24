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

import java.util.List;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.bson.Document;
import org.bson.types.ObjectId;

import com.google.common.base.Splitter;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.exception.UserConflictException;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBOpenPaaSUserDAO implements OpenPaaSUserDAO {
    public static final String COLLECTION = "users";

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
                .find(Filters.eq("_id", new ObjectId(id.value())))
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
        return add(username, username.asString(), username.asString());
    }

    @Override
    public Mono<OpenPaaSUser> add(Username username, String firstName, String lastName) {
        return domainDAO.retrieve(username.getDomainPart().get())
            .switchIfEmpty(Mono.error(() -> new DomainNotFoundException(username.getDomainPart().get())))
            .map(domain -> new Document()
                .append("firstname", firstName)
                .append("lastname", lastName)
                .append("firstnames", computeFirstnames(firstName))
                .append("password", "secret")
                .append("email", username.asString()) // not part of OpenPaaS datamodel but helps solve concurrency
                .append("domains", List.of(new Document("domain_id", new ObjectId(domain.id().value()))))
                .append("accounts", List.of(new Document()
                    .append("type", "email")
                    .append("emails", List.of(username.asString())))))
            .flatMap(document -> Mono.from(database.getCollection(COLLECTION).insertOne(document)))
            .map(InsertOneResult::getInsertedId)
            .map(id -> new OpenPaaSUser(username, new OpenPaaSId(id.asObjectId().getValue().toHexString()), firstName, lastName))
            .onErrorResume(e -> {
                if (e.getMessage().contains("E11000 duplicate key error collection")) {
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
                    Filters.eq("_id", new ObjectId(id.value())),
                    Updates.combine(
                        Updates.set("firstname", newFirstname),
                        Updates.set("lastname", newLastname),
                        Updates.set("firstnames", computeFirstnames(newFirstname)),
                        Updates.set("email", newUsername.asString()),
                        Updates.set("domains", List.of(new Document("domain_id", new ObjectId(domain.id().value())))),
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
                .onErrorResume(e -> {
                    if (e.getMessage().contains("E11000 duplicate key error collection")) {
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
    public Flux<OpenPaaSUser> search(Domain domain, String query, int limit) {
        Pattern searchPattern = Pattern.compile("^" + Pattern.quote(query),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        return domainDAO.retrieve(domain)
            .flatMapMany(openPaaSDomain ->
                Flux.from(database.getCollection(COLLECTION)
                        .find(Filters.and(Filters.eq("domains.domain_id", new ObjectId(openPaaSDomain.id().value())),
                            Filters.or(
                                Filters.regex("accounts.emails", searchPattern),
                                Filters.regex("firstname", searchPattern),
                                Filters.regex("lastname", searchPattern),
                                Filters.regex("firstnames", searchPattern))))
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
}
