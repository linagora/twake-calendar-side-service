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

package com.linagora.calendar.restapi.routes;

import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.CardDavSearchException;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.dto.ContactSearchResponse;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ContactSearchRoute extends CalendarRoute {
    private static final String LIMIT_PARAM = "limit";
    private static final String OFFSET_PARAM = "offset";
    private static final int DEFAULT_LIMIT = 30;
    private static final int DEFAULT_OFFSET = 0;
    private static final int MAX_CONCURRENT_BOOK_SEARCHES = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(ContactSearchRoute.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record SearchRequest(String query, List<AddressBookRef> addressBooks, int limit, int offset) {
        public SearchRequest {
            query = query == null ? StringUtils.EMPTY : query;
            addressBooks = addressBooks == null ? List.of() : addressBooks;
            if (addressBooks.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Address book must not be null");
            }
            // Keep the first occurrence of each requested book and preserve book order.
            addressBooks = addressBooks.stream().distinct().toList();
            if ((long) offset + limit > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid pagination");
            }
        }

        private record Body(String query, List<AddressBookRef> addressBooks) {
        }

        static Mono<SearchRequest> parse(HttpServerRequest request) {
            return Mono.defer(() -> {
                QueryStringDecoder parameters = new QueryStringDecoder(request.uri());
                int limit = parameter(parameters, LIMIT_PARAM, DEFAULT_LIMIT, 1);
                int offset = parameter(parameters, OFFSET_PARAM, DEFAULT_OFFSET, 0);
                return request.receive().aggregate().asString()
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body must be an object")))
                    .flatMap(payload -> Mono.fromCallable(() -> OBJECT_MAPPER.readValue(payload, Body.class))
                        .onErrorMap(JsonProcessingException.class, error -> new IllegalArgumentException("Invalid request body", error)))
                    .map(body -> new SearchRequest(body.query(), body.addressBooks(), limit, offset));
            });
        }

        private static int parameter(QueryStringDecoder parameters, String name, int defaultValue, int minimum) {
            List<String> values = parameters.parameters().get(name);
            if (values == null) {
                return defaultValue;
            }
            if (values.size() != 1) {
                throw new IllegalArgumentException("Repeated " + name);
            }
            int value = Integer.parseInt(values.getFirst());
            if (value < minimum) {
                throw new IllegalArgumentException("Invalid " + name);
            }
            return value;
        }

        int fetchLimit() {
            return offset + limit;
        }

        List<AddressBookURL> addressBookURLs() {
            return addressBooks.stream()
                .map(AddressBookRef::toURL)
                .toList();
        }

        public record AddressBookRef(String userId, String addressBookId) {
            private static final Pattern VALID_PATH_SEGMENT = Pattern.compile("[a-zA-Z0-9._~-]+");

            AddressBookURL toURL() {
                validateSegment(userId);
                validateSegment(addressBookId);
                return new AddressBookURL(new OpenPaaSId(userId), addressBookId);
            }

            private static void validateSegment(String value) {
                if (value == null || !VALID_PATH_SEGMENT.matcher(value).matches() || value.equals(".") || value.equals("..")) {
                    throw new IllegalArgumentException("Address book IDs must be non-empty URI path segments");
                }
            }
        }
    }

    public record SearchResponse(@JsonProperty("_embedded") SearchResponse.Embedded embedded) {
        public static SearchResponse from(List<JsonNode> contacts) {
            return new SearchResponse(new Embedded(contacts));
        }

        public byte[] serialize() throws JsonProcessingException {
            return OBJECT_MAPPER.writeValueAsBytes(this);
        }

        public record Embedded(@JsonProperty("dav:item") List<JsonNode> items) {
        }
    }

    private final CardDavClient cardDavClient;

    @Inject
    public ContactSearchRoute(Authenticator authenticator, MetricFactory metricFactory, CardDavClient cardDavClient) {
        super(authenticator, metricFactory);
        this.cardDavClient = cardDavClient;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/contacts/api/contacts/search");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return SearchRequest.parse(request)
            .flatMap(searchRequest -> search(session.getUser(), searchRequest))
            .flatMap(searchResponse -> response.status(200).header("Content-Type", "application/json")
                .sendByteArray(Mono.fromCallable(searchResponse::serialize)).then())
            .onErrorResume(DavClientException.class, error -> {
                LOGGER.error("Contact search DAV request failed", error);
                return ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_GATEWAY, "Contact search failed");
            });
    }

    private Mono<SearchResponse> search(Username requester, SearchRequest request) {
        int fetchLimit = request.fetchLimit();
        return Flux.fromIterable(request.addressBookURLs())
            .flatMapSequential(addressBookURL -> cardDavClient.searchContacts(requester, addressBookURL, request.query(), fetchLimit)
                .map(ContactSearchResponse::items)
                .onErrorResume(CardDavSearchException.class, error -> switch (error.statusCode()) {
                    case SC_FORBIDDEN, SC_NOT_FOUND -> Mono.empty();
                    default -> Mono.error(error);
                }), MAX_CONCURRENT_BOOK_SEARCHES, 1)
            .collectList()
            .map(results -> mergeAndPaginate(results, request))
            .map(SearchResponse::from);
    }

    private static List<JsonNode> mergeAndPaginate(List<List<JsonNode>> results, SearchRequest request) {
        return results.stream()
            .flatMap(List::stream)
            .skip(request.offset())
            .limit(request.limit())
            .toList();
    }
}
