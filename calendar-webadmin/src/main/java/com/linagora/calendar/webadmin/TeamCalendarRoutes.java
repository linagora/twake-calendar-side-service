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

import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.TeamCalendarNotFoundException;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Mono;
import spark.Request;
import spark.Response;
import spark.Service;

public class TeamCalendarRoutes implements Routes {
    public static final String BASE_PATH = "domains";

    private static final String DOMAIN_PARAM = ":domain";
    private static final String TEAM_CALENDAR_ID_PARAM = ":teamCalendarId";
    private static final String TEAM_CALENDARS = "team-calendars";
    private static final String TEAM_CALENDARS_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM + SEPARATOR + TEAM_CALENDARS;
    private static final String TEAM_CALENDAR_PATH = TEAM_CALENDARS_PATH + SEPARATOR + TEAM_CALENDAR_ID_PARAM;

    public record TeamCalendarResponse(String id,
                                       String domainId,
                                       String domainName,
                                       String name,
                                       String displayName,
                                       Instant creation,
                                       Instant updated) {
        static TeamCalendarResponse from(TeamCalendar teamCalendar) {
            return new TeamCalendarResponse(teamCalendar.id().value(),
                teamCalendar.domain().id().value(),
                teamCalendar.domain().domain().asString(),
                teamCalendar.name(),
                teamCalendar.displayName(),
                teamCalendar.creation(),
                teamCalendar.updated());
        }
    }

    public record TeamCalendarCreationRequest(@JsonProperty(value = "name", required = true) String name,
                                              @JsonProperty(value = "displayName", required = true) String displayName) {
        public TeamCalendarCreationRequest {
            Preconditions.checkArgument(StringUtils.isNotBlank(name), "Field 'name' is required");
            Preconditions.checkArgument(StringUtils.isNotBlank(displayName), "Field 'displayName' is required");
        }
    }

    public record TeamCalendarUpdateRequest(@JsonProperty(value = "displayName", required = true) String displayName) {
        public TeamCalendarUpdateRequest {
            Preconditions.checkArgument(StringUtils.isNotBlank(displayName), "Field 'displayName' is required");
        }
    }

    private final TeamCalendarService teamCalendarService;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<TeamCalendarCreationRequest> creationRequestJsonExtractor;
    private final JsonExtractor<TeamCalendarUpdateRequest> updateRequestJsonExtractor;

    @Inject
    public TeamCalendarRoutes(TeamCalendarService teamCalendarService,
                              JsonTransformer jsonTransformer) {
        this.teamCalendarService = teamCalendarService;
        this.jsonTransformer = jsonTransformer;
        this.creationRequestJsonExtractor = new JsonExtractor<>(TeamCalendarCreationRequest.class);
        this.updateRequestJsonExtractor = new JsonExtractor<>(TeamCalendarUpdateRequest.class);
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.post(TEAM_CALENDARS_PATH, this::createTeamCalendar, jsonTransformer);
        service.get(TEAM_CALENDARS_PATH, this::list, jsonTransformer);
        service.get(TEAM_CALENDAR_PATH, this::getById, jsonTransformer);
        service.patch(TEAM_CALENDAR_PATH, this::updateDisplayName, jsonTransformer);
        service.delete(TEAM_CALENDAR_PATH, this::delete);
    }

    private TeamCalendarResponse createTeamCalendar(Request request, Response response) throws JsonExtractException {
        Domain domain = parseDomain(request);
        TeamCalendarCreationRequest creationRequest = creationRequestJsonExtractor.parse(request.body());

        return mapErrors(() -> teamCalendarService.create(domain, creationRequest.name(), creationRequest.displayName())
            .map(teamCalendar -> {
                response.status(HttpStatus.CREATED_201);
                response.header(HttpHeader.LOCATION.asString(),
                    SEPARATOR + TEAM_CALENDARS_PATH.replace(DOMAIN_PARAM, domain.asString()) + SEPARATOR + teamCalendar.id().value());
                return TeamCalendarResponse.from(teamCalendar);
            }));
    }

    private List<TeamCalendarResponse> list(Request request, Response response) {
        Domain domain = parseDomain(request);

        List<TeamCalendarResponse> teamCalendars = mapErrors(() -> teamCalendarService.list(domain)
            .map(TeamCalendarResponse::from)
            .collectList());
        response.status(HttpStatus.OK_200);
        return teamCalendars;
    }

    private TeamCalendarResponse getById(Request request, Response response) {
        Domain domain = parseDomain(request);
        TeamCalendarId id = parseTeamCalendarId(request);

        TeamCalendarResponse teamCalendar = mapErrors(() -> teamCalendarService.retrieve(domain, id)
            .map(TeamCalendarResponse::from));
        response.status(HttpStatus.OK_200);
        return teamCalendar;
    }

    private TeamCalendarResponse updateDisplayName(Request request, Response response) throws JsonExtractException {
        Domain domain = parseDomain(request);
        TeamCalendarId id = parseTeamCalendarId(request);
        TeamCalendarUpdateRequest updateRequest = updateRequestJsonExtractor.parse(request.body());

        TeamCalendarResponse teamCalendar = mapErrors(() -> teamCalendarService.updateDisplayName(domain, id, updateRequest.displayName())
            .map(TeamCalendarResponse::from));
        response.status(HttpStatus.OK_200);
        return teamCalendar;
    }

    private String delete(Request request, Response response) {
        Domain domain = parseDomain(request);
        TeamCalendarId id = parseTeamCalendarId(request);

        mapErrors(() -> teamCalendarService.delete(domain, id));
        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private Domain parseDomain(Request request) {
        String domainName = request.params(DOMAIN_PARAM);
        try {
            return Domain.of(domainName);
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid domain: %s", domainName)
                .cause(e)
                .haltError();
        }
    }

    private TeamCalendarId parseTeamCalendarId(Request request) {
        return new TeamCalendarId(request.params(TEAM_CALENDAR_ID_PARAM));
    }

    private <T> T mapErrors(Supplier<Mono<T>> monoSupplier) {
        return monoSupplier.get()
            .onErrorMap(DomainNotFoundException.class, exception -> ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message(exception.getMessage())
                .haltError())
            .onErrorMap(TeamCalendarNotFoundException.class, exception -> ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Team calendar does not exist")
                .haltError())
            .block();
    }
}
