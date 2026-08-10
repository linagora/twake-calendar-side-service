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

import static com.linagora.calendar.dav.ResourceService.ONLY_ACTIVE;

import java.util.List;
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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.ResourceAdministratorNotFoundException;
import com.linagora.calendar.dav.ResourceService;
import com.linagora.calendar.dav.ResourceService.ResourceAdministrator;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.ResourceNotFoundException;
import com.linagora.calendar.storage.ResourceUpdateRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Mono;
import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

public class ResourceRoutes implements Routes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceRoutes.class);

    public static final String DOMAINS = "domains";
    private static final String DOMAIN_PARAM = ":domain";
    public static final String RESOURCES_PATH = DOMAINS + "/" + DOMAIN_PARAM + "/resources";
    private static final String RESOURCE_PATH = RESOURCES_PATH + "/:id";

    public record AdministratorDTO(@JsonProperty(value = "email", required = true) String email,
                                   @JsonProperty("davRight") String davRight) {
        private static final DavRight RIGHT_DEFAULT = DavRight.READ_WRITE;

        public static AdministratorDTO from(ResourceAdministrator administrator) {
            return new AdministratorDTO(administrator.username().asString(), administrator.davRight().value());
        }

        public ResourceAdministrator toResourceAdministrator() {
            if (StringUtils.isBlank(email)) {
                throw new IllegalArgumentException("Administrator must provide 'email'");
            }
            DavRight right = DavRight.fromValue(Optional.ofNullable(davRight).orElse(RIGHT_DEFAULT.value()));
            return new ResourceAdministrator(Username.of(email), right);
        }
    }

    public record ResourceDTO(String name, boolean deleted, String description, String id, String icon, String domain,
                              List<AdministratorDTO> administrators,
                              String creator) {
        public static ResourceDTO from(Resource resource, Domain domain, List<ResourceAdministrator> administrators, String creator) {
            return new ResourceDTO(resource.name(), resource.deleted(), resource.description(), resource.id().value(),
                resource.icon(), domain.asString(), administrators.stream().map(AdministratorDTO::from).toList(), creator);
        }
    }

    public record ResourceCreationDTO(@JsonProperty("name") String name,
                                      @JsonProperty("description") String description,
                                      @JsonProperty("icon") String icon,
                                      @JsonProperty("administrators") List<AdministratorDTO> administrators,
                                      @JsonProperty("creator") String creator) {
        public ResourceInsertRequest toInsertRequest(OpenPaaSId creatorId, OpenPaaSId domainId) {
            return new ResourceInsertRequest(creatorId, description, domainId, icon, name);
        }
    }

    public record ResourceUpdateDTO(@JsonProperty("id") String id,
                                    @JsonProperty("name") Optional<String> name,
                                    @JsonProperty("description") Optional<String> description,
                                    @JsonProperty("icon") Optional<String> icon,
                                    @JsonProperty("domain") Optional<String> domain,
                                    @JsonProperty("administrators") Optional<List<AdministratorDTO>> administrators,
                                    @JsonProperty("creator") Optional<String> creator) {
        public ResourceUpdateRequest toUpdateRequest() {
            return new ResourceUpdateRequest(name, description, icon);
        }
    }

    @Override
    public String getBasePath() {
        return DOMAINS;
    }

    private final OpenPaaSDomainDAO domainDAO;
    private final OpenPaaSUserDAO userDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<ResourceCreationDTO> creationDTOJsonExtractor;
    private final JsonExtractor<ResourceUpdateDTO> updateDTOJsonExtractor;
    private final ResourceService resourceService;

    @Inject
    public ResourceRoutes(OpenPaaSDomainDAO domainDAO,
                          OpenPaaSUserDAO userDAO,
                          JsonTransformer jsonTransformer,
                          ResourceService resourceService) {
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
        this.resourceService = resourceService;
        creationDTOJsonExtractor = new JsonExtractor<>(ResourceCreationDTO.class);
        updateDTOJsonExtractor = new JsonExtractor<>(ResourceUpdateDTO.class);
    }

    @Override
    public void define(Service service) {
        service.get(RESOURCES_PATH, (req, res) -> listResources(req), jsonTransformer);
        service.get(RESOURCE_PATH, (req, res) -> getResource(req), jsonTransformer);
        service.delete(RESOURCE_PATH, this::deleteResource);
        service.patch(RESOURCE_PATH, this::updateResource);
        service.post(RESOURCES_PATH, this::createResource, jsonTransformer);
    }

    private List<ResourceDTO> listResources(Request req) {
        OpenPaaSDomain domain = asDomainObject(req);
        return resourceService.listByDomain(domain.id())
            .flatMap(resource -> resolveResourceDTO(resource, domain.domain()), ReactorUtils.LOW_CONCURRENCY)
            .collectList()
            .block();
    }

    private ResourceDTO getResource(Request req) {
        OpenPaaSDomain domain = asDomainObject(req);
        ResourceId id = new ResourceId(req.params("id"));

        return resourceService.retrieve(id, domain.id(), !ONLY_ACTIVE)
            .flatMap(resource -> resolveResourceDTO(resource, domain.domain()))
            .blockOptional()
            .orElseThrow(() -> resourceNotFound(id));
    }

    private String deleteResource(Request req, Response res) {
        OpenPaaSDomain domain = asDomainObject(req);
        ResourceId resourceId = new ResourceId(req.params("id"));

        return retrieveActiveResource(resourceId, domain.id())
            .flatMap(resourceService::delete)
            .doOnSuccess(_ -> res.status(HttpStatus.NO_CONTENT_204))
            .thenReturn(StringUtils.EMPTY)
            .onErrorMap(ResourceNotFoundException.class, exception -> resourceNotFound(resourceId))
            .block();
    }

    private String createResource(Request req, Response res) throws JsonExtractException {
        OpenPaaSDomain domain = asDomainObject(req);
        ResourceCreationDTO creationDTO = creationDTOJsonExtractor.parse(req.body());
        Mono<OpenPaaSId> creatorIdMono = retrieveExistingUser(Username.of(creationDTO.creator))
            .map(OpenPaaSUser::id);
        List<ResourceAdministrator> administrators = toResourceAdministrators(creationDTO.administrators);

        return creatorIdMono
            .flatMap(creatorId -> resourceService.create(creationDTO.toInsertRequest(creatorId, domain.id()), administrators))
            .map(resourceId -> {
                res.header(HttpHeader.LOCATION.asString(), DOMAINS + "/" + domain.domain().asString() + "/resources/" + resourceId.value());
                res.status(HttpStatus.CREATED_201);
                return StringUtils.EMPTY;
            })
            .onErrorMap(ResourceAdministratorNotFoundException.class, this::badRequest)
            .onErrorMap(IllegalArgumentException.class, this::badRequest)
            .block();
    }

    private String updateResource(Request req, Response res) throws JsonExtractException {
        OpenPaaSDomain domain = asDomainObject(req);
        ResourceUpdateDTO dto = updateDTOJsonExtractor.parse(req.body());
        ResourceId resourceId = new ResourceId(req.params("id"));

        return retrieveActiveResource(resourceId, domain.id())
            .flatMap(resource -> updateResourceFromDTO(resource, dto))
            .doOnSuccess(_ -> res.status(HttpStatus.NO_CONTENT_204))
            .thenReturn(StringUtils.EMPTY)
            .onErrorMap(ResourceAdministratorNotFoundException.class, this::badRequest)
            .onErrorMap(IllegalArgumentException.class, this::badRequest)
            .onErrorMap(ResourceNotFoundException.class, exception -> resourceNotFound(resourceId))
            .block();
    }

    private Mono<Void> updateResourceFromDTO(Resource resource, ResourceUpdateDTO dto) {
        return dto.administrators()
            .map(administrators -> resourceService.updateAdmins(resource, toResourceAdministrators(administrators)))
            .orElse(Mono.empty())
            .then(resourceService.update(resource.id(), dto.toUpdateRequest()));
    }

    private List<ResourceAdministrator> toResourceAdministrators(List<AdministratorDTO> administrators) {
        try {
            return Optional.ofNullable(administrators).orElse(List.of()).stream()
                .map(AdministratorDTO::toResourceAdministrator)
                .toList();
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        }
    }

    private OpenPaaSDomain asDomainObject(Request request) {
        String domainName = request.params("domain");
        try {
            Domain domain = Domain.of(domainName);
            return domainDAO.retrieve(domain)
                .blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("Domain not found: %s", domainName)
                    .haltError());
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid domain: %s", domainName)
                .cause(e)
                .haltError();
        }
    }

    private Mono<Resource> retrieveActiveResource(ResourceId resourceId, OpenPaaSId domainId) {
        return resourceService.retrieve(resourceId, domainId, ONLY_ACTIVE)
            .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException(resourceId)));
    }

    private Mono<ResourceDTO> resolveResourceDTO(Resource resource, Domain domain) {
        return Mono.zip(
                userDAO.retrieve(resource.creator()),
                resourceService.listAdministrators(resource))
            .map(tuple -> ResourceDTO.from(resource, domain, tuple.getT2(), tuple.getT1().username().asString()));
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

    private HaltException badRequest(Exception exception) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.BAD_REQUEST_400)
            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
            .message(exception.getMessage())
            .cause(exception)
            .haltError();
    }

}
