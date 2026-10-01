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

package com.linagora.calendar.commoncontacts.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ObjectType;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ResponseDTO;
import com.linagora.calendar.restapi.routes.people.search.PeopleSearchService;
import com.linagora.calendar.storage.SimpleSessionProvider;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class CommonContactsSearchRoute {
    public static final Endpoint ENDPOINT = new Endpoint(HttpMethod.POST, "/api/people/search");
    public static final int MAX_RESULTS_LIMIT = 256;
    public static final int MAX_RESULTS_WINDOW = 1000;
    public static final Set<ObjectType> SUPPORTED_OBJECT_TYPES = ImmutableSet.of(ObjectType.CONTACT);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchRequestDTO(@JsonProperty("user") String user,
                            @JsonProperty("q") String query,
                            @JsonProperty("objectTypes") List<String> objectTypes,
                            @JsonProperty("limit") int limit,
                            @JsonProperty("offset") int offset) {

        SearchRequestDTO validate() {
            Preconditions.checkArgument(user != null, "'user' is required");
            Preconditions.checkArgument(limit > 0, "Limit must be greater than 0");
            Preconditions.checkArgument(limit <= MAX_RESULTS_LIMIT, "Maximum limit allowed: %s, but got: %s", MAX_RESULTS_LIMIT, limit);
            Preconditions.checkArgument(offset >= 0, "Offset must be positive");
            Preconditions.checkArgument(offset + limit <= MAX_RESULTS_WINDOW, "offset + limit must not exceed %s", MAX_RESULTS_WINDOW);
            effectiveObjectTypes();
            return this;
        }

        Username username() {
            Username username = Username.of(user);
            Preconditions.checkArgument(username.hasDomainPart(), "'user' must be a mail address");
            return username;
        }

        String queryOrEmpty() {
            return Optional.ofNullable(query).orElse("");
        }

        Set<ObjectType> effectiveObjectTypes() {
            if (objectTypes == null || objectTypes.isEmpty()) {
                return SUPPORTED_OBJECT_TYPES;
            }
            return objectTypes.stream()
                .map(SearchRequestDTO::parseSupportedObjectType)
                .collect(ImmutableSet.toImmutableSet());
        }

        private static ObjectType parseSupportedObjectType(String objectType) {
            return ObjectType.parse(objectType)
                .filter(SUPPORTED_OBJECT_TYPES::contains)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported object type: '%s'. Supported: %s"
                    .formatted(objectType, SUPPORTED_OBJECT_TYPES.stream().map(ObjectType::serialize).toList())));
        }
    }

    record ContactDTO(@JsonProperty("id") String id,
                      @JsonProperty("objectType") String objectType,
                      @JsonProperty("names") List<JsonNode> names,
                      @JsonProperty("emailAddresses") List<JsonNode> emailAddresses) {

        static ContactDTO from(ResponseDTO responseDTO) {
            return new ContactDTO(responseDTO.getId(), responseDTO.getObjectType(),
                responseDTO.getNames(), responseDTO.getEmailAddresses());
        }
    }

    private final PeopleSearchService peopleSearchService;
    private final SimpleSessionProvider sessionProvider;
    private final MetricFactory metricFactory;

    @Inject
    public CommonContactsSearchRoute(PeopleSearchService peopleSearchService,
                                     SimpleSessionProvider sessionProvider,
                                     MetricFactory metricFactory) {
        this.peopleSearchService = peopleSearchService;
        this.sessionProvider = sessionProvider;
        this.metricFactory = metricFactory;
    }

    public boolean matches(HttpServerRequest request) {
        return ENDPOINT.matches(request);
    }

    public Mono<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric(getClass().getSimpleName(), doHandle(request, response)));
    }

    private Mono<Void> doHandle(HttpServerRequest request, HttpServerResponse response) {
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Missing request body")))
            .map(this::parseRequest)
            .map(SearchRequestDTO::validate)
            .flatMapMany(this::search)
            .map(ContactDTO::from)
            .collectList()
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> response.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private SearchRequestDTO parseRequest(byte[] bytes) {
        try {
            return OBJECT_MAPPER.readValue(bytes, SearchRequestDTO.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private Flux<ResponseDTO> search(SearchRequestDTO request) {
        return peopleSearchService.search(sessionProvider.createSession(request.username()),
                request.queryOrEmpty(), request.effectiveObjectTypes(), request.offset() + request.limit())
            .skip(request.offset());
    }
}
