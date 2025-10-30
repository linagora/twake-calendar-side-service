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
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.CalendarURL;
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

    private record AdminChanges(Set<OpenPaaSId> toAdd, Set<OpenPaaSId> toRemove) {

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
    private final CalDavClient calDavClient;
    private final JsonExtractor<ResourceCreationDTO> creationDTOJsonExtractor;
    private final JsonExtractor<ResourceUpdateDTO> updateDTOJsonExtractor;

    @Inject
    public ResourceRoutes(ResourceDAO resourceDAO,
                          OpenPaaSDomainDAO domainDAO,
                          OpenPaaSUserDAO userDAO,
                          JsonTransformer jsonTransformer,
                          CalDavClient calDavClient) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
        this.calDavClient = calDavClient;
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
        ResourceId id = new ResourceId(req.params("id"));

        return findNotDeletedResource(id)
            .flatMap(currentResource -> resolveAdminUsernames(currentResource)
                .flatMap(adminUsers -> {
                    if (!adminUsers.isEmpty()) {
                        LOGGER.debug("Revoking write rights for resource {} from admins: {}", id.value(), adminUsers);
                        return calDavClient.revokeWriteRights(currentResource.domain(), currentResource.id(), adminUsers)
                            .doOnError(error -> LOGGER.error("Error revoking write rights for resource {}", id.value(), error))
                            .then(resourceDAO.softDelete(id));
                    }
                    LOGGER.debug("No admin users found for resource {}, proceeding with soft delete.", id.value());
                    return resourceDAO.softDelete(id);
                }))
            .doOnSuccess(v -> res.status(204))
            .thenReturn("")
            .onErrorResume(e -> {
                if (e instanceof ResourceNotFoundException || e instanceof IllegalArgumentException) {
                    LOGGER.warn("Failed to delete resource {}: not found or invalid.", id.value());
                    return Mono.error(resourceNotFound(id));
                }
                return Mono.error(e);
            })
            .block();
    }

    private String createResource(Request req, Response res) throws JsonExtractException {
        ResourceCreationDTO creationDTO = creationDTOJsonExtractor.parse(req.body());

        Mono<OpenPaaSId> creatorIdMono = userDAO.retrieve(Username.of(creationDTO.creator))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(String.format("creator '%s' must exist", creationDTO.creator))))
            .map(OpenPaaSUser::id);

        Mono<OpenPaaSId> domainIdMono = domainDAO.retrieve(Domain.of(creationDTO.domain))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(String.format("domain '%s' must exist", creationDTO.domain))))
            .map(OpenPaaSDomain::id);

        Mono<Map<Username, ResourceAdministrator>> adminsMono = resolveAdministratorsFromDTO(creationDTO.administrators);

        return Mono.zip(creatorIdMono, domainIdMono, adminsMono)
            .flatMap(tuple ->
                insertAndGrantAdminRights(tuple.getT1(), tuple.getT2(), tuple.getT3(), creationDTO)
                    .map(resourceId -> {
                        res.header("Location", getBasePath() + "/" + resourceId.value());
                        res.status(201);
                        return "";
                    }))
            .onErrorResume(IllegalArgumentException.class, e -> Mono.error(
                ErrorResponder.builder()
                    .statusCode(HttpStatus.BAD_REQUEST_400)
                    .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                    .message(e.getMessage())
                    .cause(e)
                    .haltError()))
            .block();
    }

    private Mono<ResourceId> insertAndGrantAdminRights(OpenPaaSId creatorId,
                                                       OpenPaaSId domainId,
                                                       Map<Username, ResourceAdministrator> admins,
                                                       ResourceCreationDTO dto) {
        ResourceInsertRequest insertRequest = new ResourceInsertRequest(ImmutableList.copyOf(admins.values()),
            creatorId, dto.description, domainId, dto.icon, dto.name);

        return resourceDAO.insert(insertRequest)
            .flatMap(resourceId ->
                calDavClient.grantReadWriteRights(domainId, resourceId, ImmutableList.copyOf(admins.keySet()))
                    .doOnError(err -> LOGGER.error("Error granting rights for resource {}", resourceId.value(), err))
                    .thenReturn(resourceId));
    }

    private String updateResource(Request req, Response res) throws JsonExtractException {
        ResourceUpdateDTO dto = updateDTOJsonExtractor.parse(req.body());
        ResourceId id = new ResourceId(req.params("id"));

        return findNotDeletedResource(id)
            .flatMap(resource ->
                updateAdminDelegationsIfNeeded(resource, dto, id)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(newAdminOps -> resourceDAO.update(id,
                        new ResourceUpdateRequest(dto.name(), dto.description(), dto.icon(), newAdminOps))))
            .doOnSuccess(v -> res.status(204))
            .thenReturn("")
            .onErrorResume(e -> {
                if (e instanceof ResourceNotFoundException || e instanceof IllegalArgumentException) {
                    LOGGER.warn("Failed to update resource {}: not found or invalid.", id.value());
                    return Mono.error(resourceNotFound(id));
                }
                return Mono.error(e);
            })
            .block();
    }

    private Mono<List<ResourceAdministrator>> updateAdminDelegationsIfNeeded(Resource currentResource, ResourceUpdateDTO dto, ResourceId id) {
        if (dto.administrators().isEmpty()) {
            return Mono.empty();
        }

        return resolveAdministratorsFromDTO(dto.administrators().get())
            .map(map -> ImmutableList.copyOf(map.values()))
            .flatMap(newAdmins -> {
                AdminChanges changes = detectUserAdminChanges(currentResource, newAdmins, id);
                Mono<List<Username>> addUsernames = resolveUsernamesFromIds(changes.toAdd());
                Mono<List<Username>> revokeUsernames = resolveUsernamesFromIds(changes.toRemove());

                return Mono.zip(addUsernames, revokeUsernames)
                    .flatMap(tuple ->
                        calDavClient.patchReadWriteDelegations(currentResource.domain(), CalendarURL.from(id.asOpenPaaSId()),
                                tuple.getT1(), tuple.getT2())
                            .doOnError(err -> LOGGER.error("Failed to patch CalDAV delegation for resource {}", id.value(), err)))
                    .thenReturn(newAdmins);
            });
    }

    private AdminChanges detectUserAdminChanges(Resource currentResource, List<ResourceAdministrator> newAdmins, ResourceId id) {
        List<ResourceAdministrator> currentAdmins = filterUserAdministrators(currentResource);

        Set<OpenPaaSId> currentIds = currentAdmins.stream()
            .map(ResourceAdministrator::refId)
            .collect(Collectors.toSet());
        Set<OpenPaaSId> newIds = newAdmins.stream()
            .map(ResourceAdministrator::refId)
            .collect(Collectors.toSet());

        Set<OpenPaaSId> toAdd = Sets.difference(newIds, currentIds);
        Set<OpenPaaSId> toRemove = Sets.difference(currentIds, newIds);

        LOGGER.info("Resource {} admin changes detected — add: {}, remove: {}", id.value(), toAdd, toRemove);
        return new AdminChanges(toAdd, toRemove);
    }

    private Mono<Map<Username, ResourceAdministrator>> resolveAdministratorsFromDTO(List<AdministratorDTO> dtos) {
        return Flux.fromIterable(dtos)
            .map(AdministratorDTO::email)
            .map(Username::of)
            .flatMap(user -> userDAO.retrieve(user)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("administrator '" + user.asString() + "' must exist"))))
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

    private List<ResourceAdministrator> filterUserAdministrators(Resource currentResource) {
        return Optional.ofNullable(currentResource.administrators()).orElse(List.of())
            .stream()
            .filter(admin -> Strings.CI.equals(admin.objectType(), "user"))
            .toList();
    }

    private Mono<List<Username>> resolveAdminUsernames(Resource resource) {
        return Flux.fromIterable(filterUserAdministrators(resource))
            .flatMap(resourceAdmin -> userDAO.retrieve(resourceAdmin.refId()), ReactorUtils.LOW_CONCURRENCY)
            .map(OpenPaaSUser::username)
            .collectList();
    }

    private Mono<Resource> findNotDeletedResource(ResourceId resourceId) {
        return resourceDAO.findById(resourceId)
            .filter(resource -> !resource.deleted())
            .switchIfEmpty(Mono.error(new ResourceNotFoundException(resourceId)))
            .doOnError(ResourceNotFoundException.class,
                e -> LOGGER.warn("Resource {} not found or already deleted.", resourceId.value()));
    }

    private Mono<List<Username>> resolveUsernamesFromIds(Set<OpenPaaSId> userIds) {
        return Flux.fromIterable(userIds)
            .flatMap(userId -> userDAO.retrieve(userId).map(OpenPaaSUser::username)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("User id '" + userId + "' does not exist"))))
            .collectList();
    }

    private RuntimeException resourceNotFound(ResourceId id) {
        LOGGER.warn("Resource {} not found or invalid.", id.value());
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message("Resource does not exist")
            .cause(new RuntimeException())
            .haltError();
    }
}
