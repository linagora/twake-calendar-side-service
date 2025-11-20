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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.core.UTCDate;
import org.bson.Document;

import com.github.fge.lambdas.Throwing;
import com.linagora.tmail.james.jmap.ticket.Ticket;
import com.linagora.tmail.james.jmap.ticket.TicketStore;
import com.linagora.tmail.james.jmap.ticket.TicketValue;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;
import reactor.core.scala.publisher.SMono;
import scala.runtime.BoxedUnit;

public class MongoDBTicketDAO implements TicketStore {
    public static final String COLLECTION = "auth_tickets";

    private static final String FIELD_ID = "_id";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_CLIENT_ADDRESS = "clientAddress";
    private static final String FIELD_GENERATED_ON = "generatedOn";
    private static final String FIELD_VALID_UNTIL = "validUntil";
    private static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    private final MongoCollection<Document> collection;

    @Inject
    public MongoDBTicketDAO(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    public static Mono<Void> declareIndex(MongoCollection<Document> collection) {
        return Mono.from(collection.createIndex(
                Indexes.ascending(FIELD_VALID_UNTIL),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)
                    .name("auth_tickets_ttl_validUntil")))
            .then();
    }

    private Document toDocument(Ticket ticket) {
        return new Document()
            .append(FIELD_ID, ticket.value().value().toString())
            .append(FIELD_USERNAME, ticket.username().asString())
            .append(FIELD_CLIENT_ADDRESS, ticket.clientAddress().getHostAddress())
            .append(FIELD_GENERATED_ON, Date.from(ticket.generatedOn().date().toInstant()))
            .append(FIELD_VALID_UNTIL, Date.from(ticket.validUntil().date().toInstant()));
    }

    private Ticket toTicket(Document doc) throws UnknownHostException {
        return new Ticket(InetAddress.getByName(doc.getString(FIELD_CLIENT_ADDRESS)),
            new TicketValue(UUID.fromString(doc.getString(FIELD_ID))),
            UTCDate.from(doc.getDate(FIELD_GENERATED_ON), ZONE_UTC),
            UTCDate.from(doc.getDate(FIELD_VALID_UNTIL), ZONE_UTC),
            Username.of(doc.getString(FIELD_USERNAME)));
    }

    @Override
    public SMono<BoxedUnit> persist(Ticket ticket) {
        Mono<Void> mono = Mono.from(collection.insertOne(toDocument(ticket))).then();
        return SMono.fromPublisher(mono).map(any -> BoxedUnit.UNIT);
    }

    @Override
    public SMono<Ticket> retrieve(TicketValue ticketValue) {
        return SMono.fromPublisher(Mono.from(collection.find(Filters.eq(FIELD_ID, ticketValue.value().toString()))
                .first())
            .map(Throwing.function(this::toTicket)));
    }

    @Override
    public SMono<BoxedUnit> delete(TicketValue ticketValue) {
        Mono<Void> mono = Mono.from(collection.deleteOne(Filters.eq(FIELD_ID, ticketValue.value().toString())))
            .then();
        return SMono.fromPublisher(mono).map(any -> BoxedUnit.UNIT);
    }
}
