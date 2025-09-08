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

package com.linagora.calendar.webadmin;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.ResourceNotFoundException;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.ResourceUpdateRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spark.Request;
import spark.Response;
import spark.Service;

public class ResourceRoutes implements Routes {

    public static final String BASE_PATH = "resources";

    public record AdministratorDTO(String email) {
        @JsonCreator
        public AdministratorDTO(@JsonProperty("email") String email) {
            this.email = email;
        }
    }

    public record ResourceDTO(String name, boolean deleted, String description, String id, String icon, String domain, List<AdministratorDTO> administrators,
                              String creator) {
        public static ResourceDTO fromDomainObject(Resource domainObject, Domain domain, List<AdministratorDTO> administrators, String creator) {
            return new ResourceDTO(domainObject.name(), domainObject.deleted(), domainObject.description(), domainObject.id().value(),
                domainObject.icon(), domain.asString(), administrators, creator);
        }
    }

    public record ResourceCreationDTO(String name, String description, String icon, String domain, List<AdministratorDTO> administrators, String creator) {
        @JsonCreator
        public ResourceCreationDTO(@JsonProperty("name") String name,
                                   @JsonProperty("description") String description,
                                   @JsonProperty("icon") String icon,
                                   @JsonProperty("domain") String domain,
                                   @JsonProperty("administrators") List<AdministratorDTO> administrators,
                                   @JsonProperty("creator") String creator) {
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.domain = domain;
            this.administrators = administrators;
            this.creator = creator;
        }
    }

    public record ResourceUpdateDTO(String id, Optional<String> name, Optional<String> description, Optional<String> icon,
                                    Optional<String> domain, Optional<List<AdministratorDTO>> administrators, Optional<String> creator) {
        @JsonCreator
        public ResourceUpdateDTO(@JsonProperty("id") String id,
                                 @JsonProperty("name") Optional<String> name,
                                 @JsonProperty("description") Optional<String> description,
                                 @JsonProperty("icon") Optional<String> icon,
                                 @JsonProperty("domain") Optional<String> domain,
                                 @JsonProperty("administrators") Optional<List<AdministratorDTO>> administrators,
                                 @JsonProperty("creator") Optional<String> creator) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.domain = domain;
            this.administrators = administrators;
            this.creator = creator;
        }
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final OpenPaaSUserDAO userDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<ResourceCreationDTO> creationDTOJsonExtractor;
    private final JsonExtractor<ResourceUpdateDTO> updateDTOJsonExtractor;

    @Inject
    public ResourceRoutes(ResourceDAO resourceDAO, OpenPaaSDomainDAO domainDAO, OpenPaaSUserDAO userDAO, JsonTransformer jsonTransformer) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
        creationDTOJsonExtractor = new JsonExtractor<>(ResourceCreationDTO.class);
        updateDTOJsonExtractor = new JsonExtractor<>(ResourceUpdateDTO.class);
    }

    @Override
    public void define(Service service) {
        service.get(getBasePath(), (req, res) -> listResources(req), jsonTransformer);
        service.get(getBasePath() + "/:id", (req, res) -> getResource(req), jsonTransformer);
        service.delete(getBasePath() + "/:id", this::deleteResource);
        service.post(getBasePath(), this::createResource);
        service.patch(getBasePath() + "/:id", this::updateResource);
    }

    private List<ResourceDTO> listResources(Request req) {
        Optional<Domain> maybeDomain = retrieveDomain(req);

        return maybeDomain.map(this::findResourceByDomain)
            .orElseGet(resourceDAO::findAll)
            .flatMap(this::toDto, ReactorUtils.LOW_CONCURRENCY)
            .collectList()
            .block();
    }

    private ResourceDTO getResource(Request req) {
        ResourceId id = new ResourceId(req.params("id"));

        return resourceDAO.findById(id)
            .flatMap(this::toDto)
            .blockOptional()
            .orElseGet(() -> {
                throw ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("Resource do not exist")
                    .cause(new RuntimeException())
                    .haltError();
            });
    }

    private String deleteResource(Request req, Response res) {
        ResourceId id = new ResourceId(req.params("id"));

        try {
            resourceDAO.softDelete(id).block();
            res.status(204);
            return "";
        } catch (ResourceNotFoundException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Resource do not exist")
                .cause(new RuntimeException())
                .haltError();
        }
    }

    private String createResource(Request req, Response res) throws JsonExtractException {
        ResourceCreationDTO dto = creationDTOJsonExtractor.parse(req.body());

        OpenPaaSId creatorId = userDAO.retrieve(Username.of(dto.creator))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("creator '" + dto.creator + "' must exist")))
            .block().id();
        OpenPaaSId domainId = domainDAO.retrieve(Domain.of(dto.domain))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("domain '" + dto.domain + "' must exist")))
            .block().id();
        List<ResourceAdministrator> administrators = retrieveResourceAdministrators(dto.administrators);

        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(administrators,
            creatorId, dto.description, domainId, dto.icon, dto.name))
            .block();

        res.header("Location", getBasePath() + "/" + resourceId.value());
        res.status(201);
        return "";
    }

    private String updateResource(Request req, Response res) throws JsonExtractException {
        ResourceUpdateDTO dto = updateDTOJsonExtractor.parse(req.body());

        ResourceId id = new ResourceId(req.params("id"));
        Optional<List<ResourceAdministrator>> administrators = dto.administrators.map(this::retrieveResourceAdministrators);

        try {
            resourceDAO.update(id, new ResourceUpdateRequest(dto.name, dto.description, dto.icon, administrators))
                .block();

            res.status(204);
            return "";
        } catch (ResourceNotFoundException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Resource do not exist")
                .cause(new RuntimeException())
                .haltError();
        }
    }

    private List<ResourceAdministrator> retrieveResourceAdministrators(List<AdministratorDTO> dtos) {
        return Flux.fromIterable(dtos)
            .map(AdministratorDTO::email)
            .map(Username::of)
            .flatMap(user -> userDAO.retrieve(user)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("administrator '" + user.asString() + "' must exist"))))
            .map(user -> new ResourceAdministrator(user.id(), "user"))
            .collectList()
            .block();
    }

    private Flux<Resource> findResourceByDomain(Domain domain) {
        return domainDAO.retrieve(domain)
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("domain does not exist")))
            .map(OpenPaaSDomain::id)
            .flatMapMany(resourceDAO::findByDomain);
    }

    private Mono<ResourceDTO> toDto(Resource resource) {
        return Mono.zip(
                domainDAO.retrieve(resource.domain()),
                userDAO.retrieve(resource.creator()),
                Flux.fromIterable(resource.administrators())
                    .map(ResourceAdministrator::refId)
                    .flatMap(userDAO::retrieve, ReactorUtils.LOW_CONCURRENCY)
                    .map(u -> new AdministratorDTO(u.username().asString()))
                    .collectList())
            .map(tuple -> ResourceDTO.fromDomainObject(resource, tuple.getT1().domain(), tuple.getT3(), tuple.getT2().username().asString()));
    }

    private Optional<Domain> retrieveDomain(Request request) {
        return Optional.ofNullable(request.queryParams("domain"))
            .map(Domain::of);
    }
}
