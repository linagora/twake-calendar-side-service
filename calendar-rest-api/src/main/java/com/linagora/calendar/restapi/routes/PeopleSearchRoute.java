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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.linagora.calendar.restapi.routes.people.search.PeopleSearchProvider;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class PeopleSearchRoute extends CalendarRoute {
    public static final int MAX_RESULTS_LIMIT = 256;

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public enum ObjectType {
        USER, CONTACT, RESOURCE;

        static Optional<ObjectType> parse(String s) {
            return Stream.of(ObjectType.values())
                .filter(t -> t.name().equalsIgnoreCase(s))
                .findAny();
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchRequestDTO(@JsonProperty("q") String query,
                            @JsonProperty("objectTypes") List<String> objectTypes,
                            @JsonProperty("limit") int limit) {
        @JsonIgnore
        public Set<ObjectType> parsedObjectTypes() {
            return objectTypes.stream()
                .flatMap(s -> ObjectType.parse(s).stream())
                .collect(ImmutableSet.toImmutableSet());
        }
    }

    public interface ResponseDTO {
        @JsonProperty("id")
        String getId();

        @JsonProperty("objectType")
        String getObjectType();

        @JsonProperty("emailAddresses")
        List<JsonNode> getEmailAddresses();

        @JsonProperty("names")
        List<JsonNode> getNames();

        @JsonProperty("photos")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<JsonNode> getPhotos();

        @JsonProperty("phoneNumbers")
        default List<String> getPhoneNumbers() {
            return ImmutableList.of();
        }

        default List<JsonNode> buildEmailAddresses(String mailAddress, String type) {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("value", mailAddress);
            objectNode.put("type", type);
            return ImmutableList.of(objectNode);
        }

        default List<JsonNode> buildNames(String name) {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("displayName", name);
            objectNode.put("type", "default");
            return ImmutableList.of(objectNode);
        }

        default List<JsonNode> buildPhotos(String photoUrl) {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("url", photoUrl);
            objectNode.put("type", "default");
            return ImmutableList.of(objectNode);
        }
    }

    private final Set<PeopleSearchProvider> searchProviders;

    @Inject
    public PeopleSearchRoute(Authenticator authenticator,
                             MetricFactory metricFactory, Set<PeopleSearchProvider> searchProviders) {
        super(authenticator, metricFactory);
        this.searchProviders = searchProviders;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/people/search");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res, MailboxSession session) {
        return req.receive().aggregate().asByteArray()
            .map(Throwing.function(bytes -> OBJECT_MAPPER.readValue(bytes, SearchRequestDTO.class)))
            .map(validateRequest())
            .flatMapMany(requestDTO -> search(session.getUser(), requestDTO.query, requestDTO.parsedObjectTypes(), requestDTO.limit))
            .collectList()
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> res.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private UnaryOperator<SearchRequestDTO> validateRequest() {
        return requestDTO -> {
            Preconditions.checkArgument(requestDTO.limit > 0, "Limit must be greater than 0");
            Preconditions.checkArgument(requestDTO.limit <= MAX_RESULTS_LIMIT, "Maximum limit allowed: " + MAX_RESULTS_LIMIT + ", but got: " + requestDTO.limit);
            return requestDTO;
        };
    }

    private Flux<ResponseDTO> search(Username username, String query, Set<ObjectType> objectTypesFilter, int limit) {
        return Flux.fromIterable(searchProviders)
            .filter(provider -> objectTypesFilter.isEmpty() || !Sets.intersection(
                objectTypesFilter,
                provider.supportedTypes()).isEmpty())
            .flatMap(provder -> provder.search(username, query, objectTypesFilter, limit))
            .take(limit);
    }
}
