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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Named;

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
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.DomainAdministrator;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Aggregates the {@code /api/resources/{id}}, {@code /api/users/{id}}, {@code /api/domains/{id}}
 * and {@code /api/team-calendars/{id}} lookups behind a single {@code /api/entity/{id}} endpoint.
 * The returned object wraps the matched entity under a discriminating key: {@code user}, {@code resource},
 * {@code domain} or {@code teamCalendar}. A missing or cross-domain-forbidden id yields a 404.
 */
public class EntityRoute extends CalendarRoute {
    private static final String ENTITY_ID_PATH_PARAM = "id";

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new Jdk8Module())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntityResponseDTO(@JsonProperty("user") UserRoute.ResponseDTO user,
                             @JsonProperty("resource") ResourceResponseDTO resource,
                             @JsonProperty("domain") DomainResponseDTO domain,
                             @JsonProperty("teamCalendar") TeamCalendarResponseDTO teamCalendar) {

        static EntityResponseDTO forUser(UserRoute.ResponseDTO user) {
            return new EntityResponseDTO(user, null, null, null);
        }

        static EntityResponseDTO forResource(ResourceResponseDTO resource) {
            return new EntityResponseDTO(null, resource, null, null);
        }

        static EntityResponseDTO forDomain(DomainResponseDTO domain) {
            return new EntityResponseDTO(null, null, domain, null);
        }

        static EntityResponseDTO forTeamCalendar(TeamCalendarResponseDTO teamCalendar) {
            return new EntityResponseDTO(null, null, null, teamCalendar);
        }
    }

    record ResourceResponseDTO(TimestampsDTO timestamps,
                               boolean deleted,
                               @JsonProperty("_id") String id,
                               String name,
                               String description,
                               String type,
                               String icon,
                               List<AdministratorDTO> administrators,
                               String creator,
                               DomainDTO domain) {

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

        static ResourceResponseDTO from(Resource resource, DomainDTO domain) {
            List<AdministratorDTO> administrators = CollectionUtils.emptyIfNull(resource.administrators())
                .stream()
                .map(AdministratorDTO::from)
                .toList();

            return new ResourceResponseDTO(new TimestampsDTO(resource.creation(), resource.updated()),
                resource.deleted(), resource.id().value(), resource.name(), resource.description(),
                resource.type(), resource.icon(), administrators, resource.creator().value(), domain);
        }
    }

    record TeamCalendarResponseDTO(TimestampsDTO timestamps,
                                   @JsonProperty("_id") String id,
                                   String name,
                                   String displayName,
                                   DomainDTO domain) {

        static TeamCalendarResponseDTO from(TeamCalendar teamCalendar, DomainDTO domain) {
            return new TeamCalendarResponseDTO(new TimestampsDTO(teamCalendar.creation(), teamCalendar.updated()),
                teamCalendar.id().value(), teamCalendar.name(), teamCalendar.displayName(), domain);
        }
    }

    record TimestampsDTO(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant creation,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant updatedAt) {
    }

    public static class DomainDTO {
        private final OpenPaaSDomain domain;

        DomainDTO(OpenPaaSDomain domain) {
            this.domain = domain;
        }

        @JsonProperty("timestamps")
        public Timestamp getTimestamps() {
            return new Timestamp();
        }

        @JsonProperty("hostnames")
        public ImmutableList<String> getHostnames() {
            return ImmutableList.of(domain.domain().asString());
        }

        @JsonProperty("_id")
        public String getId() {
            return domain.id().value();
        }

        @JsonProperty("name")
        public String getName() {
            return domain.domain().asString();
        }

        @JsonProperty("company_name")
        public String getCompanyName() {
            return domain.domain().asString();
        }
    }

    public static class DomainResponseDTO extends DomainDTO {
        private final ImmutableList<AdministratorDTO> admins;

        public record AdministratorDTO(@JsonProperty("user_id") String userId) {

            static AdministratorDTO from(DomainAdministrator admin) {
                return new AdministratorDTO(admin.userId().value());
            }

            @JsonProperty("timestamps")
            public Timestamp getTimestamps() {
                return new Timestamp();
            }
        }

        DomainResponseDTO(OpenPaaSDomain domain, List<DomainAdministrator> adminList) {
            super(domain);
            this.admins = adminList.stream()
                .map(AdministratorDTO::from)
                .collect(ImmutableList.toImmutableList());
        }

        @JsonProperty("administrators")
        public ImmutableList<AdministratorDTO> getAdministrators() {
            return admins;
        }
    }

    public static class Timestamp {
        @JsonProperty("creation")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
        public ZonedDateTime getCreation() {
            return ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    private final ResourceDAO resourceDAO;
    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final OpenPaaSDomainAdminDAO domainAdminDAO;
    private final TeamCalendarRepository teamCalendarRepository;
    private final SettingsBasedResolver settingsResolver;
    private final CrossDomainAccessControl crossDomainAccessControl;

    @Inject
    public EntityRoute(Authenticator authenticator,
                       MetricFactory metricFactory,
                       ResourceDAO resourceDAO,
                       OpenPaaSUserDAO userDAO,
                       OpenPaaSDomainDAO domainDAO,
                       OpenPaaSDomainAdminDAO domainAdminDAO,
                       TeamCalendarRepository teamCalendarRepository,
                       @Named("language_timezone") SettingsBasedResolver settingsResolver,
                       CrossDomainAccessControl crossDomainAccessControl) {
        super(authenticator, metricFactory);
        this.resourceDAO = resourceDAO;
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.domainAdminDAO = domainAdminDAO;
        this.teamCalendarRepository = teamCalendarRepository;
        this.settingsResolver = settingsResolver;
        this.crossDomainAccessControl = crossDomainAccessControl;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, String.format("/api/entity/{%s}", ENTITY_ID_PATH_PARAM));
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res, MailboxSession session) {
        String id = req.param(ENTITY_ID_PATH_PARAM);
        return asUserEntity(id, session)
            .switchIfEmpty(asTeamCalendarEntity(id, session))
            .switchIfEmpty(asResourceEntity(id, session))
            .switchIfEmpty(asDomainEntity(id, session))
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> res.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<EntityResponseDTO> asResourceEntity(String id, MailboxSession session) {
        return resourceDAO.findById(new ResourceId(id))
            .flatMap(resource -> retrieveAuthorizedDomainResponse(resource.domain(), session)
                .map(domain -> EntityResponseDTO.forResource(ResourceResponseDTO.from(resource, domain))));
    }

    private Mono<EntityResponseDTO> asUserEntity(String id, MailboxSession session) {
        return userDAO.retrieve(new OpenPaaSId(id))
            .flatMap(user -> {
                if (crossDomainAccessControl.denies(session, user.username().getDomainPart().get())) {
                    return Mono.error(NotFoundException::new);
                }
                return buildUserResponse(user).map(EntityResponseDTO::forUser);
            });
    }

    private Mono<EntityResponseDTO> asTeamCalendarEntity(String id, MailboxSession session) {
        return teamCalendarRepository.retrieve(new TeamCalendarId(id))
            .flatMap(teamCalendar -> retrieveAuthorizedDomainResponse(teamCalendar.domainId(), session)
                .map(domain -> EntityResponseDTO.forTeamCalendar(TeamCalendarResponseDTO.from(teamCalendar, domain))));
    }

    private Mono<EntityResponseDTO> asDomainEntity(String id, MailboxSession session) {
        return retrieveAuthorizedDomainResponse(new OpenPaaSId(id), session)
            .map(EntityResponseDTO::forDomain);
    }

    private Mono<UserRoute.ResponseDTO> buildUserResponse(OpenPaaSUser user) {
        return Mono.zip(domainDAO.retrieve(user.username().getDomainPart().get()),
                settingsResolver.resolveOrDefault(user.username()))
            .map(tuple -> new UserRoute.ResponseDTO(user, tuple.getT1().id(), tuple.getT2().zoneId().toString()));
    }

    private Mono<OpenPaaSDomain> retrieveAuthorizedDomain(OpenPaaSId domainId, MailboxSession session) {
        return domainDAO.retrieve(domainId)
            .filter(domain -> !crossDomainAccessControl.denies(session, domain.domain()))
            .switchIfEmpty(Mono.error(NotFoundException::new));
    }

    private Mono<DomainResponseDTO> retrieveAuthorizedDomainResponse(OpenPaaSId domainId, MailboxSession session) {
        return retrieveAuthorizedDomain(domainId, session)
            .flatMap(domain -> domainAdminDAO.listAdmins(domain.id())
                .collectList()
                .map(adminList -> new DomainResponseDTO(domain, adminList)));
    }
}
