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

import java.time.Instant;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ResourceRoute extends CalendarRoute {
    private static final String RESOURCE_ID_PATH_PARAM = "resourceId";

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    record ResourceResponseDTO(TimestampsDTO timestamps,
                               boolean deleted,
                               @JsonProperty("_id") String id,
                               String name,
                               String description,
                               String type,
                               String icon,
                               List<AdministratorDTO> administrators,
                               String creator,
                               DomainRoute.ResponseDTO domain) {

        @JsonProperty("__v")
        public int getVersion() {
            return 0;
        }

        record TimestampsDTO(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant creation,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant updatedAt) {
        }

        record AdministratorDTO(@JsonProperty("id") String idRef,
                                @JsonProperty("objectType") String objectType) {

            static AdministratorDTO from(ResourceAdministrator entity) {
                return new AdministratorDTO(entity.refId().value(), entity.objectType());
            }

            @JsonProperty("_id")
            public String getId() {
                return idRef;
            }
        }
    }

    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;
    private final OpenPaaSDomainAdminDAO domainAdminDAO;

    @Inject
    public ResourceRoute(Authenticator authenticator,
                         MetricFactory metricFactory, ResourceDAO resourceDAO,
                         OpenPaaSDomainDAO openPaaSDomainDAO,
                         OpenPaaSDomainAdminDAO domainAdminDAO) {
        super(authenticator, metricFactory);
        this.resourceDAO = resourceDAO;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.domainAdminDAO = domainAdminDAO;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, String.format("/linagora.esn.resource/api/resources/{%s}", RESOURCE_ID_PATH_PARAM));
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res, MailboxSession session) {
        ResourceId resourceId = new ResourceId(req.param(RESOURCE_ID_PATH_PARAM));
        return resourceDAO.findById(resourceId)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(resource -> retrieveAuthorizedDomainResponse(resource.domain(), session)
                .map(domainResponse -> buildResponseDTO(resource, domainResponse)))
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> res.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<DomainRoute.ResponseDTO> retrieveAuthorizedDomainResponse(OpenPaaSId domainId, MailboxSession session) {
        return openPaaSDomainDAO.retrieve(domainId)
            .filter(resource -> !crossDomainAccess(session, resource.domain()))
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(domain -> buildDomainResponse(domainId, domain));
    }

    private Mono<DomainRoute.ResponseDTO> buildDomainResponse(OpenPaaSId domainId, OpenPaaSDomain domain) {
        return domainAdminDAO.listAdmins(domainId)
            .collectList()
            .map(adminList -> new DomainRoute.ResponseDTO(domain, adminList));
    }

    private ResourceResponseDTO buildResponseDTO(Resource resource,  DomainRoute.ResponseDTO domainResponseDTO) {
        List<ResourceResponseDTO.AdministratorDTO> administrators = CollectionUtils.emptyIfNull(resource.administrators())
            .stream()
            .map(ResourceResponseDTO.AdministratorDTO::from)
            .toList();

        ResourceResponseDTO.TimestampsDTO timestampsDTO = new ResourceResponseDTO.TimestampsDTO(resource.creation(), resource.updated());

        return new ResourceResponseDTO(timestampsDTO, resource.deleted(), resource.id().value(),
            resource.name(), resource.description(), resource.type(), resource.icon(),
            administrators, resource.creator().value(),
            domainResponseDTO);
    }
}