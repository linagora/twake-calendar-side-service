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

package com.linagora.calendar.restapi.routes.people.search;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute;
import com.linagora.calendar.restapi.routes.ResourcePhotoUrlFactory;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.Resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ResourceSearchProvider implements PeopleSearchProvider {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceResponseDTO(@JsonIgnore Resource resource,
                                      @JsonIgnore Domain domain,
                                      @JsonIgnore URI photoUrl) implements PeopleSearchRoute.ResponseDTO {

        @Override
        public String getId() {
            return resource.id().value();
        }

        @Override
        public String getObjectType() {
            return PeopleSearchRoute.ObjectType.RESOURCE.name().toLowerCase();
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

    private final OpenPaaSDomainDAO domainDAO;
    private final ResourcePhotoUrlFactory resourcePhotoUrlFactory;
    private final ResourceDAO resourceDAO;

    @Inject
    public ResourceSearchProvider(OpenPaaSDomainDAO domainDAO, ResourcePhotoUrlFactory resourcePhotoUrlFactory, ResourceDAO resourceDAO) {
        this.domainDAO = domainDAO;
        this.resourcePhotoUrlFactory = resourcePhotoUrlFactory;
        this.resourceDAO = resourceDAO;
    }

    @Override
    public Set<PeopleSearchRoute.ObjectType> supportedTypes() {
        return ImmutableSet.of(PeopleSearchRoute.ObjectType.RESOURCE);
    }

    @Override
    public Flux<PeopleSearchRoute.ResponseDTO> search(Username username, String query, Set<PeopleSearchRoute.ObjectType> objectTypesFilter, int limit) {
            return Mono.justOrEmpty(username.getDomainPart())
                .flatMap(domainDAO::retrieve)
                .flatMapMany(openPaaSDomain -> resourceDAO.search(openPaaSDomain.id(), query, limit)
                    .map(resource -> resourceAsResponseDTO(resource, openPaaSDomain.domain())));
    }

    private PeopleSearchRoute.ResponseDTO resourceAsResponseDTO(Resource resource, Domain domain) {
        URI photoUrl = Optional.ofNullable(resource.icon())
            .map(resourcePhotoUrlFactory::resolveURL)
            .orElse(null);
        return new ResourceResponseDTO(resource, domain, photoUrl);
    }
}
