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
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class TeamCalendarRoute extends CalendarRoute {
    private static final String TEAM_CALENDAR_ID_PATH_PARAM = "teamCalendarId";

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
    }

    private final TeamCalendarRepository teamCalendarRepository;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;
    private final OpenPaaSDomainAdminDAO domainAdminDAO;
    private final CrossDomainAccessControl crossDomainAccessControl;

    @Inject
    public TeamCalendarRoute(Authenticator authenticator,
                             MetricFactory metricFactory,
                             TeamCalendarRepository teamCalendarRepository,
                             OpenPaaSDomainDAO openPaaSDomainDAO,
                             OpenPaaSDomainAdminDAO domainAdminDAO,
                             CrossDomainAccessControl crossDomainAccessControl) {
        super(authenticator, metricFactory);
        this.teamCalendarRepository = teamCalendarRepository;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.domainAdminDAO = domainAdminDAO;
        this.crossDomainAccessControl = crossDomainAccessControl;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, String.format("/api/team-calendars/{%s}", TEAM_CALENDAR_ID_PATH_PARAM));
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest req, HttpServerResponse res, MailboxSession session) {
        TeamCalendarId teamCalendarId = new TeamCalendarId(req.param(TEAM_CALENDAR_ID_PATH_PARAM));
        return teamCalendarRepository.retrieve(teamCalendarId)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(teamCalendar -> retrieveAuthorizedDomainResponse(teamCalendar.domainId(), session)
                .map(domainResponse -> buildResponseDTO(teamCalendar, domainResponse)))
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> res.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<DomainRoute.ResponseDTO> retrieveAuthorizedDomainResponse(OpenPaaSId domainId, MailboxSession session) {
        return openPaaSDomainDAO.retrieve(domainId)
            .filter(domain -> !crossDomainAccessControl.denies(session, domain.domain()))
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(domain -> domainAdminDAO.listAdmins(domainId)
                .collectList()
                .map(adminList -> new DomainRoute.ResponseDTO(domain, adminList)));
    }

    private TeamCalendarResponseDTO buildResponseDTO(TeamCalendar teamCalendar, DomainRoute.ResponseDTO domainResponseDTO) {
        TeamCalendarResponseDTO.TimestampsDTO timestampsDTO =
            new TeamCalendarResponseDTO.TimestampsDTO(teamCalendar.creation(), teamCalendar.updated());

        return new TeamCalendarResponseDTO(timestampsDTO, teamCalendar.id().value(),
            teamCalendar.name(), teamCalendar.displayName(), domainResponseDTO);
    }
}
