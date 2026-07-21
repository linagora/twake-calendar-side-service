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

import jakarta.inject.Inject;
import jakarta.inject.Named;

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
import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
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
                             @JsonProperty("resource") ResourceRoute.ResourceResponseDTO resource,
                             @JsonProperty("domain") DomainRoute.ResponseDTO domain,
                             @JsonProperty("teamCalendar") TeamCalendarResponseDTO teamCalendar) {

        static EntityResponseDTO forUser(UserRoute.ResponseDTO user) {
            return new EntityResponseDTO(user, null, null, null);
        }

        static EntityResponseDTO forResource(ResourceRoute.ResourceResponseDTO resource) {
            return new EntityResponseDTO(null, resource, null, null);
        }

        static EntityResponseDTO forDomain(DomainRoute.ResponseDTO domain) {
            return new EntityResponseDTO(null, null, domain, null);
        }

        static EntityResponseDTO forTeamCalendar(TeamCalendarResponseDTO teamCalendar) {
            return new EntityResponseDTO(null, null, null, teamCalendar);
        }
    }

    record TeamCalendarResponseDTO(TimestampsDTO timestamps,
                                   @JsonProperty("_id") String id,
                                   String name,
                                   String displayName,
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

        static TeamCalendarResponseDTO from(TeamCalendar teamCalendar, DomainRoute.ResponseDTO domain) {
            return new TeamCalendarResponseDTO(new TimestampsDTO(teamCalendar.creation(), teamCalendar.updated()),
                teamCalendar.id().value(), teamCalendar.name(), teamCalendar.displayName(), domain);
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
                .map(domain -> EntityResponseDTO.forResource(ResourceRoute.ResourceResponseDTO.from(resource, domain))));
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

    private Mono<DomainRoute.ResponseDTO> retrieveAuthorizedDomainResponse(OpenPaaSId domainId, MailboxSession session) {
        return domainDAO.retrieve(domainId)
            .filter(domain -> !crossDomainAccessControl.denies(session, domain.domain()))
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(domain -> domainAdminDAO.listAdmins(domainId)
                .collectList()
                .map(adminList -> new DomainRoute.ResponseDTO(domain, adminList)));
    }
}
