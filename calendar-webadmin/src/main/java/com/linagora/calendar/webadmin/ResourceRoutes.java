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
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.ResourceNotFoundException;
import com.linagora.calendar.storage.ResourceUpdateRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

public class ResourceRoutes implements Routes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceRoutes.class);

    public static final String BASE_PATH = "resources";

    public record AdministratorDTO(String email) {
        @JsonCreator
        public AdministratorDTO(@JsonProperty("email") String email) {
            this.email = email;
        }
    }

    public record ResourceDTO(String name, boolean deleted, String description, String id, String icon, String domain,
                              List<AdministratorDTO> administrators,
                              String creator) {
        public static ResourceDTO fromDomainObject(Resource domainObject, Domain domain, List<AdministratorDTO> administrators, String creator) {
            return new ResourceDTO(domainObject.name(), domainObject.deleted(), domainObject.description(), domainObject.id().value(),
                domainObject.icon(), domain.asString(), administrators, creator);
        }
    }

    public record ResourceCreationDTO(String name, String description, String icon, String domain,
                                      List<AdministratorDTO> administrators, String creator) {
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

    public record ResourceUpdateDTO(String id, Optional<String> name, Optional<String> description,
                                    Optional<String> icon,
                                    Optional<String> domain, Optional<List<AdministratorDTO>> administrators,
                                    Optional<String> creator) {
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
    private final ResourceAdministratorService  resourceAdministratorService;
    private final JsonExtractor<ResourceCreationDTO> creationDTOJsonExtractor;
    private final JsonExtractor<ResourceUpdateDTO> updateDTOJsonExtractor;

    @Inject
    public ResourceRoutes(ResourceDAO resourceDAO,
                          OpenPaaSDomainDAO domainDAO,
                          OpenPaaSUserDAO userDAO,
                          JsonTransformer jsonTransformer,
                          ResourceAdministratorService resourceAdministratorService) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
        this.resourceAdministratorService = resourceAdministratorService;
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
            .orElseThrow(() -> resourceNotFound(id));
    }

    private String deleteResource(Request req, Response res) {
        ResourceId resourceId = new ResourceId(req.params("id"));

        return findNotDeletedResource(resourceId)
            .flatMap(currentResource -> resourceAdministratorService.revokeAdmins(currentResource)
                .then(resourceDAO.softDelete(resourceId)))
            .doOnSuccess(any -> res.status(HttpStatus.NO_CONTENT_204))
            .thenReturn(StringUtils.EMPTY)
            .onErrorMap(ResourceNotFoundException.class, exception -> resourceNotFound(resourceId))
            .block();
    }

    private String createResource(Request req, Response res) throws JsonExtractException {
        ResourceCreationDTO creationDTO = creationDTOJsonExtractor.parse(req.body());

        Mono<OpenPaaSId> creatorIdMono = retrieveExistingUser(Username.of(creationDTO.creator))
            .map(OpenPaaSUser::id);

        Mono<OpenPaaSId> domainIdMono = domainDAO.retrieve(Domain.of(creationDTO.domain))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(String.format("domain '%s' must exist", creationDTO.domain))))
            .map(OpenPaaSDomain::id);

        Mono<Map<Username, ResourceAdministrator>> adminMapMono = resolveAdministratorsFromDTO(creationDTO.administrators);

        return Mono.zip(creatorIdMono, domainIdMono, adminMapMono)
            .flatMap(tuple -> {
                OpenPaaSId creatorId = tuple.getT1();
                OpenPaaSId domainId = tuple.getT2();
                Map<Username, ResourceAdministrator> adminMap = tuple.getT3();
                return resourceDAO.insert(buildInsertRequest(adminMap, creatorId, creationDTO, domainId))
                    .flatMap(resourceId -> resourceAdministratorService.setAdmins(domainId, resourceId, adminMap.keySet())
                        .thenReturn(resourceId));
            })
            .map(resourceId -> {
                res.header(HttpHeader.LOCATION.asString(), getBasePath() + "/" + resourceId.value());
                res.status(HttpStatus.CREATED_201);
                return StringUtils.EMPTY;
            })
            .block();
    }

    private ResourceInsertRequest buildInsertRequest(Map<Username, ResourceAdministrator> adminMap, OpenPaaSId creatorId,
                                                     ResourceCreationDTO creationDTO, OpenPaaSId domainId) {
        return new ResourceInsertRequest(adminMap.values().stream().toList(), creatorId, creationDTO.description, domainId, creationDTO.icon, creationDTO.name);
    }

    private String updateResource(Request req, Response res) throws JsonExtractException {
        ResourceUpdateDTO dto = updateDTOJsonExtractor.parse(req.body());
        ResourceId resourceId = new ResourceId(req.params("id"));

        return findNotDeletedResource(resourceId)
            .flatMap(resource -> dto.administrators()
                .map(admins -> resourceAdministratorService.updateAdmins(resource, admins).map(Optional::of))
                .orElse(Mono.just(Optional.empty()))
                .map(adminsOpt -> buildUpdateRequest(dto, adminsOpt))
                .flatMap(updateRequest -> resourceDAO.update(resourceId, updateRequest)))
            .doOnSuccess(any -> res.status(HttpStatus.NO_CONTENT_204))
            .thenReturn(StringUtils.EMPTY)
            .onErrorMap(ResourceNotFoundException.class, exception -> resourceNotFound(resourceId))
            .block();
    }

    private Mono<Map<Username, ResourceAdministrator>> resolveAdministratorsFromDTO(List<AdministratorDTO> dtos) {
        return Flux.fromIterable(dtos)
            .map(dto -> Username.of(dto.email()))
            .flatMap(this::retrieveExistingUser)
            .collectMap(OpenPaaSUser::username, user -> new ResourceAdministrator(user.id(), "user"));
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

    private Mono<Resource> findNotDeletedResource(ResourceId resourceId) {
        return resourceDAO.findById(resourceId)
            .filter(resource -> !resource.deleted())
            .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException(resourceId)));
    }

    private Mono<OpenPaaSUser> retrieveExistingUser(Username username) {
        return userDAO.retrieve(username)
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Username '%s' must exist".formatted(username.asString()))));
    }

    private HaltException resourceNotFound(ResourceId id) {
        LOGGER.warn("Resource {} not found or invalid.", id.value());
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message("Resource does not exist")
            .haltError();
    }

    private ResourceUpdateRequest buildUpdateRequest(ResourceUpdateDTO dto, Optional<List<ResourceAdministrator>> adminsOpt) {
        return new ResourceUpdateRequest(dto.name(), dto.description(), dto.icon(), adminsOpt);
    }
}
