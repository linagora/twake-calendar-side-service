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

import static com.linagora.calendar.restapi.routes.JsonRequestBodyParser.INVALID_BODY_PREFIX;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import jakarta.inject.Inject;

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
import com.linagora.calendar.restapi.routes.people.search.PeopleSearchService;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class PeopleSearchRoute extends CalendarRoute {
    public static final int MAX_RESULTS_LIMIT = 256;
    public static final int MAX_QUERY_LENGTH = 256;

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonRequestBodyParser BODY_PARSER = new JsonRequestBodyParser(OBJECT_MAPPER);

    public enum ObjectType {
        USER, CONTACT, RESOURCE, TEAM_CALENDAR;

        public String serialize() {
            return name().toLowerCase().replace('_', '-');
        }

        public static Optional<ObjectType> parse(String s) {
            return Stream.of(ObjectType.values())
                .filter(t -> t.serialize().equalsIgnoreCase(s))
                .findAny();
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchRequestDTO(@JsonProperty("q") String query,
                            @JsonProperty("objectTypes") List<String> objectTypes,
                            @JsonProperty("limit") Integer limit) {
        @JsonIgnore
        public String queryOrEmpty() {
            return Optional.ofNullable(query).orElse("");
        }

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

        @JsonIgnore
        default String getDisplayName() {
            return getNames().stream()
                .map(node -> node.path("displayName").asText(""))
                .findFirst()
                .orElse("");
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

    private final PeopleSearchService peopleSearchService;

    @Inject
    public PeopleSearchRoute(Authenticator authenticator,
                             MetricFactory metricFactory, PeopleSearchService peopleSearchService) {
        super(authenticator, metricFactory);
        this.peopleSearchService = peopleSearchService;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/people/search");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res, MailboxSession session) {
        return req.receive().aggregate().asString()
            .defaultIfEmpty("")
            .flatMap(payload -> BODY_PARSER.parse(payload, SearchRequestDTO.class))
            .map(validateRequest())
            .flatMapMany(requestDTO -> peopleSearchService.search(session, requestDTO.queryOrEmpty(), requestDTO.parsedObjectTypes(), requestDTO.limit))
            .collectList()
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> res.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private UnaryOperator<SearchRequestDTO> validateRequest() {
        return requestDTO -> {
            Preconditions.checkArgument(requestDTO.objectTypes != null, INVALID_BODY_PREFIX + "'objectTypes' is required");
            Preconditions.checkArgument(requestDTO.limit != null, INVALID_BODY_PREFIX + "'limit' is required");
            Preconditions.checkArgument(requestDTO.limit > 0, "Limit must be greater than 0");
            Preconditions.checkArgument(requestDTO.limit <= MAX_RESULTS_LIMIT, "Maximum limit allowed: " + MAX_RESULTS_LIMIT + ", but got: " + requestDTO.limit);
            Preconditions.checkArgument(requestDTO.query == null || requestDTO.query.length() <= MAX_QUERY_LENGTH,
                "Maximum query length allowed: " + MAX_QUERY_LENGTH);
            return requestDTO;
        };
    }
}
