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

package com.linagora.calendar.storage.opensearch;

import static org.apache.james.backends.opensearch.IndexCreationFactory.RAW;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.backends.opensearch.DocumentId;
import org.apache.james.backends.opensearch.OpenSearchIndexer;
import org.apache.james.backends.opensearch.ReactorOpenSearchClient;
import org.apache.james.backends.opensearch.RoutingKey;
import org.apache.james.core.MailAddress;
import org.apache.james.util.ReactorUtils;
import org.apache.james.vacation.api.AccountId;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.exception.CalendarSearchIndexingException;
import com.linagora.calendar.storage.opensearch.CalendarEventIndexMappingFactory.CalendarFields;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class OpensearchCalendarSearchService implements CalendarSearchService {
    private static final Function<AccountId, RoutingKey> ROUTING_KEY =
        accountId -> RoutingKey.fromString(accountId.getIdentifier());
    private static final String DELIMITER = ":";

    private final OpenSearchIndexer indexer;
    private final ReactorOpenSearchClient client;
    private final ObjectMapper mapper;
    private final CalendarEventOpensearchConfiguration configuration;

    @Inject
    public OpensearchCalendarSearchService(ReactorOpenSearchClient client,
                                           CalendarEventOpensearchConfiguration configuration) {
        this.client = client;
        this.configuration = configuration;
        this.indexer = new OpenSearchIndexer(client, configuration.writeAliasName());
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private DocumentId buildDocumentIdForEvent(AccountId accountId, EventFields eventFields) {
        Supplier<String> masterRecurrence = () -> {
            if (eventFields.isRecurrentMaster() == null) {
                return "single";
            }
            if (eventFields.isRecurrentMaster()) {
                return "master";
            }
            return "recurrence";
        };

        return DocumentId.fromString(String.join(DELIMITER, accountId.getIdentifier(),
            eventFields.uid().value(),
            masterRecurrence.get(),
            Optional.ofNullable(eventFields.start())
                .map(Instant::toEpochMilli)
                .map(String::valueOf).orElse(null)));
    }

    @Override
    public Mono<Void> index(AccountId accountId, CalendarEvents fields) {
        if (fields.events().isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(fields.events())
            .flatMap(eventFields -> indexSingleEvent(accountId, eventFields), ReactorUtils.DEFAULT_CONCURRENCY)
            .then();
    }

    private Mono<IndexResponse> indexSingleEvent(AccountId accountId, EventFields eventFields) {
        return Mono.fromCallable(() -> mapper.writeValueAsString(CalendarEventsDocument.fromEventFields(accountId, eventFields)))
            .flatMap(content -> indexer.index(buildDocumentIdForEvent(accountId, eventFields), content,
                ROUTING_KEY.apply(accountId)))
            .onErrorResume(throwable
                -> Mono.error(CalendarSearchIndexingException.of("Error while indexing event", accountId, eventFields.uid(), throwable)));
    }

    @Override
    public Mono<Void> delete(AccountId accountId, EventUid eventUid) {
        Preconditions.checkArgument(eventUid != null, "eventUid can not be null");
        Preconditions.checkArgument(accountId != null, "accountId can not be null");

        Query query = QueryBuilders.bool()
            .must(accountIdQuery(accountId))
            .must(QueryBuilders.term()
                .field(CalendarFields.EVENT_UID)
                .value(FieldValue.of(eventUid.value()))
                .build()
                .toQuery())
            .build()
            .toQuery();

        return indexer.deleteAllMatchingQuery(query, ROUTING_KEY.apply(accountId))
            .onErrorResume(throwable
                -> Mono.error(CalendarSearchIndexingException.of("Error while deleting eventUid " + eventUid.value(), accountId, eventUid, throwable)))
            .then();
    }

    @Override
    public Flux<EventFields> search(AccountId accountId, EventSearchQuery query) {
        List<Query> mustClauses = new ArrayList<>();
        mustClauses.add(accountIdQuery(accountId));
        buildKeywordQuery(query).ifPresent(mustClauses::add);

        query.calendars().ifPresent(calendarURLList
            -> mustClauses.add(buildCalendarFilter(calendarURLList)));
        query.organizers().ifPresent(organizerList ->
            mustClauses.add(buildAddressFilter(organizerList, CalendarFields.ORGANIZER)));
        query.attendees().ifPresent(attendeeList ->
            mustClauses.add(buildAddressFilter(attendeeList, CalendarFields.ATTENDEES)));

        Query openSearchQuery = QueryBuilders.bool()
            .must(mustClauses)
            .build()
            .toQuery();

        SortOptions sortOption = new SortOptions.Builder()
            .field(f -> f
                .field(CalendarFields.START)
                .order(SortOrder.Asc)
                .missing(FieldValue.of("_last")))
            .build();

        SearchRequest request = new SearchRequest.Builder()
            .index(configuration.readAliasName().getValue())
            .from(query.offset())
            .size(query.limit())
            .query(openSearchQuery)
            .sort(sortOption)
            .build();

        return Mono.fromCallable(() -> client.search(request))
            .flatMap(Function.identity())
            .flatMapIterable(searchResponse -> ImmutableList.copyOf(searchResponse.hits().hits()))
            .filter(hit -> hit.source() != null)
            .map(hit -> mapper.convertValue(hit.source(), CalendarEventsDocument.class))
            .mapNotNull(CalendarEventsDocument::toEventFields)
            .onErrorResume(throwable
                -> Flux.error(CalendarSearchIndexingException.of("Error while searching events", accountId, throwable)));
    }

    @Override
    public Mono<Void> deleteAll(AccountId accountId) {
        Preconditions.checkArgument(accountId != null, "accountId can not be null");

        return indexer.deleteAllMatchingQuery(accountIdQuery(accountId), ROUTING_KEY.apply(accountId))
            .onErrorResume(throwable
                -> Mono.error(CalendarSearchIndexingException.of("Error while deleting all events for account " + accountId.getIdentifier(), accountId, throwable)))
            .then();
    }

    private Query accountIdQuery(AccountId accountId) {
        return QueryBuilders.term()
            .field(CalendarFields.ACCOUNT_ID)
            .value(FieldValue.of(accountId.getIdentifier()))
            .build()
            .toQuery();
    }

    private Optional<Query> buildKeywordQuery(EventSearchQuery searchRequest) {
        if (StringUtils.isBlank(searchRequest.query())) {
            return Optional.empty();
        }

        Query summaryDescLocationQuery = QueryBuilders.multiMatch()
            .query(searchRequest.query())
            .fields(Arrays.asList(
                CalendarFields.SUMMARY,
                CalendarFields.DESCRIPTION,
                CalendarFields.LOCATION))
            .build()
            .toQuery();

        Query organizerEmailQuery = objectFieldMatch(CalendarFields.ORGANIZER, CalendarFields.EMAIL, searchRequest.query());
        Query organizerCnQuery = objectFieldMatch(CalendarFields.ORGANIZER, CalendarFields.CN, searchRequest.query());
        Query attendeeEmailQuery = objectFieldMatch(CalendarFields.ATTENDEES, CalendarFields.EMAIL, searchRequest.query());
        Query attendeeCnQuery = objectFieldMatch(CalendarFields.ATTENDEES, CalendarFields.CN, searchRequest.query());

        return Optional.of(QueryBuilders.bool()
            .should(summaryDescLocationQuery)
            .should(organizerEmailQuery)
            .should(organizerCnQuery)
            .should(attendeeEmailQuery)
            .should(attendeeCnQuery)
            .minimumShouldMatch("1")
            .build()
            .toQuery());
    }

    private Query objectFieldMatch(String objectField, String subField, String value) {
        return QueryBuilders.match()
            .field(objectField + "." + subField)
            .query(FieldValue.of(value))
            .build()
            .toQuery();
    }

    private Query buildCalendarFilter(List<CalendarURL> calendarURLList) {
        return QueryBuilders.terms()
            .field(CalendarFields.CALENDAR_URL)
            .terms(new TermsQueryField.Builder()
                .value(calendarURLList.stream()
                    .map(calendarURL -> FieldValue.of(calendarURL.serialize()))
                    .toList())
                .build())
            .build()
            .toQuery();
    }

    private Query buildAddressFilter(List<MailAddress> addressMatchList, String objectField) {
        return QueryBuilders.terms()
            .field(String.join(".", objectField, CalendarFields.EMAIL, RAW))
            .terms(new TermsQueryField.Builder()
                .value(addressMatchList.stream()
                    .map(addr -> FieldValue.of(addr.asString()))
                    .toList())
                .build())
            .build()
            .toQuery();
    }
}
