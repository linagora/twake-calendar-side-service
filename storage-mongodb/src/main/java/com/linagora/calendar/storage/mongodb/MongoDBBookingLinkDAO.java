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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.bson.Document;

import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPatchRequest;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBBookingLinkDAO implements BookingLinkDAO {

    public static final String COLLECTION = "booking_links";

    private static final String FIELD_PUBLIC_ID = "publicId";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_PRINCIPAL_ID = "principalId";
    private static final String FIELD_CALENDAR_ID = "calendarId";
    private static final String FIELD_DURATION_SECONDS = "durationSeconds";
    private static final String FIELD_ACTIVE = "active";
    private static final String FIELD_AVAILABILITY_RULES = "availabilityRules";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    private static final String RULE_TYPE = "type";
    private static final String RULE_TYPE_WEEKLY = "weekly";
    private static final String RULE_TYPE_FIXED = "fixed";
    private static final String RULE_DAY_OF_WEEK = "dayOfWeek";
    private static final String RULE_START = "start";
    private static final String RULE_END = "end";
    private static final String RULE_TIME_ZONE = "timeZone";
    private static final String RULE_ZONE = "zone";

    private final MongoDatabase database;
    private final Clock clock;

    @Inject
    public MongoDBBookingLinkDAO(MongoDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public static Mono<String> declareIndex(MongoCollection<Document> collection) {
        return Mono.from(collection.createIndex(Indexes.ascending(FIELD_USERNAME, FIELD_PUBLIC_ID), new IndexOptions().unique(true)))
            .then(Mono.from(collection.createIndex(Indexes.ascending(FIELD_PUBLIC_ID))))
            .then(Mono.from(collection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_USERNAME), Indexes.descending(FIELD_UPDATED_AT)))));
    }

    @Override
    public Mono<BookingLink> insert(Username username, BookingLinkInsertRequest request) {
        return Mono.fromCallable(() -> {
            Instant now = clock.instant();
            BookingLinkPublicId publicId = BookingLinkPublicId.generate();
            return BookingLink.builder()
                .username(username)
                .publicId(publicId)
                .calendarUrl(request.calendarUrl())
                .duration(request.eventDuration())
                .active(request.active())
                .availabilityRules(request.availabilityRules())
                .createdAt(now)
                .updatedAt(now)
                .build();
        }).flatMap(bookingLink ->
            Mono.from(database.getCollection(COLLECTION).insertOne(toDocument(bookingLink)))
                .thenReturn(bookingLink));
    }

    @Override
    public Mono<BookingLink> findByPublicId(BookingLinkPublicId publicId) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_PUBLIC_ID, publicId.value()))
                .first())
            .map(this::fromDocument);
    }

    @Override
    public Mono<BookingLink> findByPublicId(Username username, BookingLinkPublicId publicId) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.and(
                    Filters.eq(FIELD_USERNAME, username.asString()),
                    Filters.eq(FIELD_PUBLIC_ID, publicId.value())))
                .first())
            .map(this::fromDocument);
    }

    @Override
    public Flux<BookingLink> findByUsername(Username username) {
        return Flux.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_USERNAME, username.asString()))
                .sort(Sorts.descending(FIELD_UPDATED_AT)))
            .map(this::fromDocument);
    }

    @Override
    public Mono<BookingLink> update(Username username, BookingLinkPublicId publicId, BookingLinkPatchRequest request) {
        return Mono.fromCallable(() -> buildUpdateBson(request, clock.instant()))
            .flatMap(updateBson ->
                Mono.from(database.getCollection(COLLECTION)
                    .findOneAndUpdate(
                        Filters.and(
                            Filters.eq(FIELD_USERNAME, username.asString()),
                            Filters.eq(FIELD_PUBLIC_ID, publicId.value())),
                        updateBson,
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))))
            .switchIfEmpty(Mono.error(new BookingLinkNotFoundException(publicId)))
            .map(this::fromDocument);
    }

    @Override
    public Mono<BookingLinkPublicId> resetPublicId(Username username, BookingLinkPublicId publicId) {
        BookingLinkPublicId newPublicId = BookingLinkPublicId.generate();
        Document updateBson = new Document("$set", new Document()
            .append(FIELD_PUBLIC_ID, newPublicId.value())
            .append(FIELD_UPDATED_AT, Date.from(clock.instant())));

        return Mono.from(database.getCollection(COLLECTION)
                .findOneAndUpdate(
                    Filters.and(
                        Filters.eq(FIELD_USERNAME, username.asString()),
                        Filters.eq(FIELD_PUBLIC_ID, publicId.value())),
                    updateBson))
            .switchIfEmpty(Mono.error(new BookingLinkNotFoundException(publicId)))
            .thenReturn(newPublicId);
    }

    @Override
    public Mono<Void> delete(Username username, BookingLinkPublicId publicId) {
        return Mono.from(database.getCollection(COLLECTION)
                .deleteOne(Filters.and(
                    Filters.eq(FIELD_USERNAME, username.asString()),
                    Filters.eq(FIELD_PUBLIC_ID, publicId.value()))))
            .then();
    }

    private Document buildUpdateBson(BookingLinkPatchRequest request, Instant now) {
        Document setFields = new Document(FIELD_UPDATED_AT, Date.from(now));
        Document unsetFields = new Document();

        if (request.calendarUrl().isModified()) {
            setFields.append(FIELD_PRINCIPAL_ID, request.calendarUrl().get().base().value());
            setFields.append(FIELD_CALENDAR_ID, request.calendarUrl().get().calendarId().value());
        }
        if (request.duration().isModified()) {
            setFields.append(FIELD_DURATION_SECONDS, request.duration().get().getSeconds());
        }
        if (request.active().isModified()) {
            setFields.append(FIELD_ACTIVE, request.active().get());
        }
        if (request.availabilityRules().isModified()) {
            setFields.append(FIELD_AVAILABILITY_RULES, serializeRules(request.availabilityRules().get()));
        } else if (request.availabilityRules().isRemoved()) {
            unsetFields.append(FIELD_AVAILABILITY_RULES, "");
        }

        Document updateBson = new Document("$set", setFields);
        if (!unsetFields.isEmpty()) {
            updateBson.append("$unset", unsetFields);
        }
        return updateBson;
    }

    private Document toDocument(BookingLink bookingLink) {
        Document doc = new Document()
            .append(FIELD_PUBLIC_ID, bookingLink.publicId().value())
            .append(FIELD_USERNAME, bookingLink.username().asString())
            .append(FIELD_PRINCIPAL_ID, bookingLink.calendarUrl().base().value())
            .append(FIELD_CALENDAR_ID, bookingLink.calendarUrl().calendarId().value())
            .append(FIELD_DURATION_SECONDS, bookingLink.duration().getSeconds())
            .append(FIELD_ACTIVE, bookingLink.active())
            .append(FIELD_CREATED_AT, Date.from(bookingLink.createdAt()))
            .append(FIELD_UPDATED_AT, Date.from(bookingLink.updatedAt()));

        bookingLink.availabilityRules().ifPresent(rules ->
            doc.append(FIELD_AVAILABILITY_RULES, serializeRules(rules)));

        return doc;
    }

    private List<Document> serializeRules(AvailabilityRules rules) {
        return rules.values().stream()
            .map(this::serializeRule)
            .toList();
    }

    private Document serializeRule(AvailabilityRule rule) {
        return switch (rule) {
            case WeeklyAvailabilityRule weekly -> new Document()
                .append(RULE_TYPE, RULE_TYPE_WEEKLY)
                .append(RULE_DAY_OF_WEEK, weekly.dayOfWeek().getValue())
                .append(RULE_START, weekly.start().toString())
                .append(RULE_END, weekly.end().toString())
                .append(RULE_TIME_ZONE, weekly.timeZone().map(ZoneId::getId).orElse(null));
            case FixedAvailabilityRule fixed -> new Document()
                .append(RULE_TYPE, RULE_TYPE_FIXED)
                .append(RULE_START, fixed.start().toInstant().toEpochMilli())
                .append(RULE_END, fixed.end().toInstant().toEpochMilli())
                .append(RULE_ZONE, fixed.start().getZone().getId());
        };
    }

    private BookingLink fromDocument(Document doc) {
        Username username = Username.of(doc.getString(FIELD_USERNAME));
        BookingLinkPublicId publicId = new BookingLinkPublicId(doc.get(FIELD_PUBLIC_ID, UUID.class));
        CalendarURL calendarURL = new CalendarURL(new OpenPaaSId(doc.getString(FIELD_PRINCIPAL_ID)), new OpenPaaSId(doc.getString(FIELD_CALENDAR_ID)));
        Duration duration = Duration.ofSeconds(doc.getLong(FIELD_DURATION_SECONDS));
        boolean active = doc.getBoolean(FIELD_ACTIVE);
        Instant createdAt = doc.getDate(FIELD_CREATED_AT).toInstant();
        Instant updatedAt = doc.getDate(FIELD_UPDATED_AT).toInstant();
        Optional<AvailabilityRules> availabilityRules = Optional.ofNullable(doc.getList(FIELD_AVAILABILITY_RULES, Document.class))
            .filter(rules -> !rules.isEmpty())
            .map(rules -> new AvailabilityRules(rules.stream().map(this::deserializeRule).toList()));

        return BookingLink.builder()
            .username(username)
            .publicId(publicId)
            .calendarUrl(calendarURL)
            .duration(duration)
            .active(active)
            .availabilityRules(availabilityRules)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();
    }

    private AvailabilityRule deserializeRule(Document doc) {
        String type = doc.getString(RULE_TYPE);
        return switch (type) {
            case RULE_TYPE_WEEKLY -> {
                DayOfWeek dayOfWeek = DayOfWeek.of(doc.getInteger(RULE_DAY_OF_WEEK));
                LocalTime start = LocalTime.parse(doc.getString(RULE_START));
                LocalTime end = LocalTime.parse(doc.getString(RULE_END));
                ZoneId timeZone = Optional.ofNullable(doc.getString(RULE_TIME_ZONE)).map(ZoneId::of).orElse(null);
                yield new WeeklyAvailabilityRule(dayOfWeek, start, end, timeZone);
            }
            case RULE_TYPE_FIXED -> {
                ZoneId zone = ZoneId.of(doc.getString(RULE_ZONE));
                ZonedDateTime start = Instant.ofEpochMilli(doc.getLong(RULE_START)).atZone(zone);
                ZonedDateTime end = Instant.ofEpochMilli(doc.getLong(RULE_END)).atZone(zone);
                yield new FixedAvailabilityRule(start, end);
            }
            default -> throw new IllegalArgumentException("Unknown availability rule type: " + type);
        };
    }
}
