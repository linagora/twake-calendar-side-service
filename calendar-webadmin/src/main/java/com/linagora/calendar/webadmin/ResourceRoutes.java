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
import org.apache.james.util.ReactorUtils;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spark.Request;
import spark.Service;

public class ResourceRoutes implements Routes {

    public static final String BASE_PATH = "resources";

    public record AdministratorDTO(String email) {

    }

    public record ResourceDTO(String name, boolean deleted, String description, String id, String icon, String domain, List<AdministratorDTO> administrators,
                              String creator) {
        public static ResourceDTO fromDomainObject(Resource domainObject, Domain domain, List<AdministratorDTO> administrators, String creator) {
            return new ResourceDTO(domainObject.name(), domainObject.deleted(), domainObject.description(), domainObject.id().value(),
                domainObject.icon(), domain.asString(), administrators, creator);
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

    @Inject
    public ResourceRoutes(ResourceDAO resourceDAO, OpenPaaSDomainDAO domainDAO, OpenPaaSUserDAO userDAO, JsonTransformer jsonTransformer) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public void define(Service service) {
        service.get(getBasePath(), (req, res) -> listResources(req), jsonTransformer);
        service.get(getBasePath() + "/:id", (req, res) -> getResource(req), jsonTransformer);
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
