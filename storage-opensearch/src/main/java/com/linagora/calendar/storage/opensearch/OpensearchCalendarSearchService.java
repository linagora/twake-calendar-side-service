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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.backends.opensearch.DocumentId;
import org.apache.james.backends.opensearch.OpenSearchIndexer;
import org.apache.james.backends.opensearch.ReactorOpenSearchClient;
import org.apache.james.backends.opensearch.RoutingKey;
import org.apache.james.core.MailAddress;
import org.apache.james.util.ReactorUtils;
import org.apache.james.vacation.api.AccountId;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Script;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.WriteResponseBase;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.UpdateRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.lambdas.Throwing;
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
import com.linagora.calendar.storage.opensearch.CalendarEventIndexMappingFactory.MultiField;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class OpensearchCalendarSearchService implements CalendarSearchService {

    private static final BiFunction<Integer, ObjectNode, Script> UPSERT_WITH_SEQUENCE_SCRIPT =
        (seq, doc) -> new Script.Builder()
            .inline(i -> i
                .lang("painless")
                .source("""
                        if (ctx._source.sequence == null || params.sequence > ctx._source.sequence) {
                            ctx._source.putAll(params.doc);
                        }
                    """)
                .params("sequence", JsonData.of(seq))
                .params("doc", JsonData.of(doc)))
            .build();

    private static final Function<AccountId, RoutingKey> ROUTING_KEY =
        accountId -> RoutingKey.fromString(accountId.getIdentifier());
    private static final String DELIMITER = ":";
    private static final boolean INDEX_CHECK_SEQUENCE = true;

    private final OpenSearchIndexer indexer;
    private final ReactorOpenSearchClient client;
    private final OpenSearchAsyncClient opensearchAsyncClient;
    private final ObjectMapper mapper;
    private final CalendarEventOpensearchConfiguration configuration;

    @Inject
    public OpensearchCalendarSearchService(ReactorOpenSearchClient client,
                                           OpenSearchAsyncClient opensearchAsyncClient,
                                           CalendarEventOpensearchConfiguration configuration) {
        this.client = client;
        this.opensearchAsyncClient = opensearchAsyncClient;
        this.configuration = configuration;
        this.indexer = new OpenSearchIndexer(client, configuration.writeAliasName());
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private DocumentId buildDocumentIdForEvent(AccountId accountId, EventFields eventFields) {
        if (eventFields.isRecurrentMaster() == null) {
            return DocumentId.fromString(String.join(DELIMITER, accountId.getIdentifier(), eventFields.uid().value(), "single"));
        }
        if (eventFields.isRecurrentMaster()) {
            return DocumentId.fromString(String.join(DELIMITER, accountId.getIdentifier(), eventFields.uid().value(), "master"));
        }
        return DocumentId.fromString(String.join(DELIMITER, accountId.getIdentifier(), eventFields.uid().value(), "recurrence",
            eventFields.recurrenceId().orElse(null)));
    }

    @Override
    public Mono<Void> index(AccountId accountId, CalendarEvents fields) {
        return doIndex(accountId, fields, INDEX_CHECK_SEQUENCE);
    }

    @Override
    public Mono<Void> reindex(AccountId accountId, CalendarEvents fields) {
        return doIndex(accountId, fields, !INDEX_CHECK_SEQUENCE);
    }

    private Mono<Void> doIndex(AccountId accountId, CalendarEvents fields, boolean checkSequence) {
        if (fields.events().isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(fields.events())
            .flatMap(event -> indexSingleEvent(accountId, event, checkSequence)
                    .onErrorResume(err -> Mono.error(CalendarSearchIndexingException.of("Error while indexing event", accountId, event.uid(), err))),
                ReactorUtils.DEFAULT_CONCURRENCY)
            .then();
    }

    private Mono<WriteResponseBase> indexSingleEvent(AccountId accountId,
                                                     EventFields eventFields,
                                                     boolean checkSequence) {

        DocumentId documentId = buildDocumentIdForEvent(accountId, eventFields);
        RoutingKey routingKey = ROUTING_KEY.apply(accountId);

        return Mono.fromCallable(() -> CalendarEventsDocument.fromEventFields(accountId, eventFields))
            .flatMap(eventsDocument -> {
                if (checkSequence && eventFields.sequence().isPresent()) {
                    return Throwing.supplier(() -> indexWithSequence(documentId, routingKey, eventFields.sequence().get(), eventsDocument)).get();
                }
                return indexWithoutSequence(documentId, routingKey, eventsDocument);
            });
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
                .order(SortOrder.Desc)
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

        ImmutableList.Builder<String> fieldsBuilder = ImmutableList.builder();
        fieldsBuilder.add(CalendarFields.SUMMARY,
            CalendarFields.DESCRIPTION,
            CalendarFields.LOCATION);
        if (configuration.searchSummaryPrefix()) {
            fieldsBuilder.add(CalendarFields.SUMMARY + "." + MultiField.SUMMARY_PREFIX);
        }

        Query summaryDescLocationQuery = QueryBuilders.multiMatch()
            .query(searchRequest.query())
            .fields(fieldsBuilder.build())
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

    private Mono<WriteResponseBase> indexWithoutSequence(DocumentId documentId,
                                                         RoutingKey routingKey,
                                                         CalendarEventsDocument eventsDocument) {
        return Mono.fromCallable(() -> mapper.writeValueAsString(eventsDocument))
            .flatMap(content -> indexer.index(documentId, content, routingKey));
    }

    public Mono<WriteResponseBase> indexWithSequence(DocumentId documentId,
                                                     RoutingKey routingKey,
                                                     Integer sequence,
                                                     CalendarEventsDocument eventsDocument) throws IOException {
        ObjectNode docMap = mapper.convertValue(eventsDocument, ObjectNode.class);
        UpdateRequest<ObjectNode, ObjectNode> request =
            new UpdateRequest.Builder<ObjectNode, ObjectNode>()
                .index(configuration.writeAliasName().getValue())
                .id(documentId.asString())
                .routing(routingKey.asString())
                .script(UPSERT_WITH_SEQUENCE_SCRIPT.apply(sequence, docMap))
                .scriptedUpsert(false)
                .upsert(docMap)
                .build();

        return toReactor(opensearchAsyncClient.update(request, ObjectNode.class))
            .map(updateResponse -> (WriteResponseBase) updateResponse);
    }

    private static <T> Mono<T> toReactor(CompletableFuture<T> async) {
        return Mono.fromFuture(async).publishOn(Schedulers.boundedElastic());
    }
}
