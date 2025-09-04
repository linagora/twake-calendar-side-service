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

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.api.model.AccountId;
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
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.Resource;
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
        USER, CONTACT, RESOURCE
    }

    record UserLookupResult(ObjectType objectType, String id) {

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchRequestDTO(@JsonProperty("q") String query,
                            @JsonProperty("objectTypes") List<String> objectTypes,
                            @JsonProperty("limit") int limit) {
    }

    interface ResponseDTO {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContactResponseDTO(@JsonIgnore String id,
                                     @JsonIgnore String emailAddress,
                                     @JsonIgnore String displayName,
                                     @JsonIgnore String photoUrl,
                                     String objectType) implements ResponseDTO {

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getObjectType() {
            return objectType;
        }

        @Override
        public List<JsonNode> getEmailAddresses() {
            return buildEmailAddresses(emailAddress, "Work");
        }

        @Override
        public List<JsonNode> getNames() {
            return buildNames(displayName);
        }

        @Override
        public List<JsonNode> getPhotos() {
            return buildPhotos(photoUrl);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceResponseDTO(@JsonIgnore Resource resource,
                                      @JsonIgnore Domain domain,
                                      @JsonIgnore URI photoUrl) implements ResponseDTO {

        @Override
        public String getId() {
            return resource.id().value();
        }

        @Override
        public String getObjectType() {
            return ObjectType.RESOURCE.name().toLowerCase();
        }

        @Override
        public List<JsonNode> getEmailAddresses() {
            String mailAddress = resource.id().value() + "@" + domain.asString();
            return buildEmailAddresses(mailAddress, "default");
        }

        @Override
        public List<JsonNode> getNames() {
            return buildNames(resource.name());
        }

        @Override
        public List<JsonNode> getPhotos() {
            return Optional.ofNullable(photoUrl)
                .map(photoUrlValue -> buildPhotos(photoUrlValue.toASCIIString()))
                .orElse(List.of());
        }
    }


    private final RestApiConfiguration configuration;
    private final EmailAddressContactSearchEngine contactSearchEngine;
    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final ResourcePhotoUrlFactory resourcePhotoUrlFactory;
    private final ResourceDAO resourceDAO;

    @Inject
    public PeopleSearchRoute(Authenticator authenticator,
                             MetricFactory metricFactory,
                             RestApiConfiguration configuration,
                             EmailAddressContactSearchEngine contactSearchEngine,
                             OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO,
                             ResourcePhotoUrlFactory resourcePhotoUrlFactory, ResourceDAO resourceDAO) {
        super(authenticator, metricFactory);
        this.configuration = configuration;
        this.contactSearchEngine = contactSearchEngine;
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.resourcePhotoUrlFactory = resourcePhotoUrlFactory;
        this.resourceDAO = resourceDAO;
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
            .flatMapMany(requestDTO -> search(session.getUser(), requestDTO.query, requestDTO.objectTypes, requestDTO.limit))
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

    private Flux<ResponseDTO> search(Username username, String query, List<String> objectTypesFilter, int limit) {
        return Flux.concat(searchContact(username, query, objectTypesFilter, limit),
                searchResource(username, query, objectTypesFilter, limit))
            .take(limit);
    }

    private Flux<ResponseDTO> searchContact(Username username, String query, List<String> objectTypesFilter, int limit) {
        if (CollectionUtils.isEmpty(objectTypesFilter) || objectTypesFilter.contains(ObjectType.USER.name().toLowerCase())
            || objectTypesFilter.contains(ObjectType.CONTACT.name().toLowerCase())) {
            return Flux.from(contactSearchEngine.autoComplete(AccountId.fromString(username.asString()), query, limit))
                .flatMap(contact -> resolveUserOrContactType(contact, objectTypesFilter)
                    .map(lookupResult -> contactToResponseDTO(lookupResult).apply(contact)));
        }
        return Flux.empty();
    }

    private Flux<ResponseDTO> searchResource(Username username, String query, List<String> objectTypesFilter, int limit) {
        if (CollectionUtils.isEmpty(objectTypesFilter) || objectTypesFilter.contains(ObjectType.RESOURCE.name().toLowerCase())) {
            return Mono.justOrEmpty(username.getDomainPart())
                .flatMap(domainDAO::retrieve)
                .flatMapMany(openPaaSDomain -> resourceDAO.search(openPaaSDomain.id(), query, limit)
                    .map(resource -> resourceAsResponseDTO(resource, openPaaSDomain.domain())));
        }
        return Flux.empty();
    }

    private ResponseDTO resourceAsResponseDTO(Resource resource, Domain domain) {
        URI photoUrl = Optional.ofNullable(resource.icon())
            .map(resourcePhotoUrlFactory::resolveURL)
            .orElse(null);
        return new ResourceResponseDTO(resource, domain, photoUrl);
    }

    private Mono<UserLookupResult> resolveUserOrContactType(EmailAddressContact contact, List<String> objectTypesFilter) {
        if (CollectionUtils.isEmpty(objectTypesFilter) || objectTypesFilter.contains(ObjectType.USER.name().toLowerCase())) {
            return resolveUserOrContactType(contact);
        }
        return Mono.just(new UserLookupResult(ObjectType.CONTACT, contact.id().toString()));
    }

    private Mono<UserLookupResult> resolveUserOrContactType(EmailAddressContact contact) {
        return userDAO.retrieve(Username.fromMailAddress(contact.fields().address()))
            .map(user -> new UserLookupResult(ObjectType.USER, user.id().value()))
            .switchIfEmpty(Mono.just(new UserLookupResult(ObjectType.CONTACT, contact.id().toString())));
    }

    private Function<EmailAddressContact, ResponseDTO> contactToResponseDTO(UserLookupResult userLookupResult) {
        return contact -> new ContactResponseDTO(userLookupResult.id(),
            contact.fields().address().asString(),
            contact.fields().fullName(),
            configuration.getSelfUrl().toString() + "/api/avatars?email=" + contact.fields().address().asString(),
            userLookupResult.objectType().name().toLowerCase());
    }
}
