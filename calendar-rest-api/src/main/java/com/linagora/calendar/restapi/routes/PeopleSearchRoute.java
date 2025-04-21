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
import java.util.function.Function;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.api.model.AccountId;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.james.jmap.contact.EmailAddressContact;
import com.linagora.tmail.james.jmap.contact.EmailAddressContactSearchEngine;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class PeopleSearchRoute extends CalendarRoute {
    public static final int MAX_RESULTS_LIMIT = 256;

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public enum ObjectType {
        USER, CONTACT
    }

    record UserLookupResult(ObjectType objectType, String id) {

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchRequestDTO(@JsonProperty("q") String query,
                            @JsonProperty("objectTypes") List<String> objectTypes,
                            @JsonProperty("limit") int limit) {
    }

    public record ResponseDTO(String id,
                              @JsonIgnore String emailAddress,
                              @JsonIgnore String displayName,
                              @JsonIgnore String photoUrl,
                              String objectType) {

        @JsonProperty("emailAddresses")
        public List<JsonNode> getEmailAddresses() {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("value", emailAddress);
            objectNode.put("type", "Work");
            return ImmutableList.of(objectNode);
        }

        @JsonProperty("phoneNumbers")
        public List<String> getPhoneNumbers() {
            return ImmutableList.of();
        }

        @JsonProperty("names")
        public List<JsonNode> getNames() {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("displayName", displayName);
            objectNode.put("type", "default");
            return ImmutableList.of(objectNode);
        }

        @JsonProperty("photos")
        public List<JsonNode> getPhotos() {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("url", photoUrl);
            objectNode.put("type", "default");
            return ImmutableList.of(objectNode);
        }
    }

    private final RestApiConfiguration configuration;
    private final EmailAddressContactSearchEngine contactSearchEngine;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    public PeopleSearchRoute(Authenticator authenticator, MetricFactory metricFactory,
                             RestApiConfiguration configuration,
                             EmailAddressContactSearchEngine contactSearchEngine,
                             OpenPaaSUserDAO userDAO) {
        super(authenticator, metricFactory);
        this.configuration = configuration;
        this.contactSearchEngine = contactSearchEngine;
        this.userDAO = userDAO;
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
            .flatMapMany(requestDTO -> searchPeople(session.getUser(), requestDTO.query, requestDTO.objectTypes, requestDTO.limit))
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

    public Flux<ResponseDTO> searchPeople(Username username, String query, List<String> objectTypesFilter, int limit) {
        return Flux.from(contactSearchEngine.autoComplete(AccountId.fromString(username.asString()), query, limit))
            .flatMap(contact -> getObjectType(contact, objectTypesFilter)
                .map(objectType -> mapToResponseDTO(objectType).apply(contact)));
    }

    private Mono<UserLookupResult> getObjectType(EmailAddressContact contact, List<String> objectTypesFilter) {
        if (CollectionUtils.isEmpty(objectTypesFilter) || objectTypesFilter.contains(ObjectType.USER.name().toLowerCase())) {
            return getObjectType(contact);
        }
        return Mono.just(new UserLookupResult(ObjectType.CONTACT, contact.id().toString()));
    }

    private Mono<UserLookupResult> getObjectType(EmailAddressContact contact) {
        return userDAO.retrieve(Username.fromMailAddress(contact.fields().address()))
            .map(user -> new UserLookupResult(ObjectType.USER, user.id().value()))
            .switchIfEmpty(Mono.just(new UserLookupResult(ObjectType.CONTACT, contact.id().toString())));
    }

    private Function<EmailAddressContact, ResponseDTO> mapToResponseDTO(UserLookupResult userLookupResult) {
        return contact -> new ResponseDTO(userLookupResult.id(),
            contact.fields().address().asString(),
            contact.fields().fullName(),
            configuration.getSelfUrl().toString() + "/api/avatars?email=" + contact.fields().address().asString(),
            userLookupResult.objectType().name().toLowerCase());
    }
}
