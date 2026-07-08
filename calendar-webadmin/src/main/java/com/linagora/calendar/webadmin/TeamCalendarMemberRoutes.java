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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the    *
 *  GNU Affero General Public License for more details.             *
 ********************************************************************/

package com.linagora.calendar.webadmin;

import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.AddSharee;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.storage.MailtoUri;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.TeamCalendarNotFoundException;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.TeamCalendarId;
import com.linagora.calendar.webadmin.service.TeamCalendarMemberService;
import com.linagora.calendar.webadmin.service.TeamCalendarMemberService.TeamCalendarMember;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spark.Request;
import spark.Response;
import spark.Service;

public class TeamCalendarMemberRoutes implements Routes {
    public static final String BASE_PATH = "domains";

    private static final String DOMAIN_PARAM = ":domain";
    private static final String TEAM_CALENDAR_ID_PARAM = ":teamCalendarId";
    private static final String MEMBERS_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM
        + SEPARATOR + "team-calendars"
        + SEPARATOR + TEAM_CALENDAR_ID_PARAM
        + SEPARATOR + "members";
    private static final Predicate<Optional<Boolean>> ENABLED_DAV_RIGHT = right -> right.orElse(false);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    public record TeamCalendarMemberResponse(String username, String role, String davRight) {
        static TeamCalendarMemberResponse from(TeamCalendarMember member) {
            return new TeamCalendarMemberResponse(member.username().asString(),
                member.role().value(),
                member.role().davRight());
        }
    }

    private record TeamCalendarPathParameters(Domain domain, TeamCalendarId id) {
    }

    private final TeamCalendarService teamCalendarService;
    private final TeamCalendarMemberService teamCalendarMemberService;
    private final OpenPaaSUserDAO userDAO;
    private final JsonTransformer jsonTransformer;

    @Inject
    public TeamCalendarMemberRoutes(TeamCalendarService teamCalendarService,
                                    TeamCalendarMemberService teamCalendarMemberService,
                                    OpenPaaSUserDAO userDAO,
                                    JsonTransformer jsonTransformer) {
        this.teamCalendarService = teamCalendarService;
        this.teamCalendarMemberService = teamCalendarMemberService;
        this.userDAO = userDAO;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(MEMBERS_PATH, this::list, jsonTransformer);
        service.post(MEMBERS_PATH + SEPARATOR + "invitee", this::updateInvitees);
    }

    private List<TeamCalendarMemberResponse> list(Request request, Response response) {
        List<TeamCalendarMemberResponse> members = Mono.fromCallable(() -> new TeamCalendarPathParameters(
                parseDomain(request),
                parseTeamCalendarId(request)))
            .flatMapMany(parameters -> teamCalendarService.retrieve(parameters.domain(), parameters.id())
                .flatMapMany(teamCalendar -> teamCalendarMemberService.list(teamCalendar.domainId(), teamCalendar.id())))
            .map(TeamCalendarMemberResponse::from)
            .collectList()
            .onErrorMap(this::mapExceptionsErrors)
            .block();
        response.status(HttpStatus.OK_200);
        return members;
    }

    private String updateInvitees(Request request, Response response) {
        Mono.fromCallable(() -> new TeamCalendarPathParameters(
                parseDomain(request),
                parseTeamCalendarId(request)))
            .flatMap(parameters -> {
                CalendarSharingUpdate sharingUpdate = parseBody(request, CalendarSharingUpdate.class);
                return teamCalendarService.retrieve(parameters.domain(), parameters.id())
                    .flatMap(teamCalendar -> validateAddSharees(parameters.domain(), sharingUpdate.share().set())
                        .then(teamCalendarMemberService.update(teamCalendar.domainId(), teamCalendar.id(), sharingUpdate)));
            })
            .onErrorMap(this::mapExceptionsErrors)
            .block();
        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private Mono<Void> validateAddSharees(Domain domain, List<AddSharee> sharees) {
        return Flux.fromIterable(sharees)
            .flatMap(sharee -> {
                validateSingleDavRight(sharee);
                Username username = parseMemberUsername(domain, sharee.davHref());
                return userDAO.retrieve(username)
                    .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Candidate member not found: " + username.asString())))
                    .then();
            })
            .then();
    }

    private void validateSingleDavRight(AddSharee sharee) {
        long enabledRights = Stream.of(sharee.read(), sharee.readWrite(), sharee.administration())
            .filter(ENABLED_DAV_RIGHT)
            .count();
        Preconditions.checkArgument(enabledRights == 1, "Exactly one of 'dav:read', 'dav:read-write', 'dav:administration' must be true");
    }

    private Username parseMemberUsername(Domain domain, String davHref) {
        Username username = Username.of(MailtoUri.stripMailtoPrefix(davHref));
        Optional<Domain> memberDomain = username.getDomainPart();
        Preconditions.checkArgument(memberDomain.isPresent(), "Member username must contain a domain");
        Preconditions.checkArgument(domain.equals(memberDomain.get()), "Member must belong to domain: %s", domain.asString());
        return username;
    }

    private Domain parseDomain(Request request) {
        String domainName = request.params(DOMAIN_PARAM);
        try {
            return Domain.of(domainName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid domain: " + domainName, e);
        }
    }

    private TeamCalendarId parseTeamCalendarId(Request request) {
        return new TeamCalendarId(request.params(TEAM_CALENDAR_ID_PARAM));
    }

    private <T> T parseBody(Request request, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(request.bodyAsBytes(), type);
        } catch (Exception exception) {
            String detail = Optional.ofNullable(ExceptionUtils.getRootCause(exception))
                .map(Throwable::getMessage)
                .orElse(exception.getMessage());
            throw new IllegalArgumentException("Invalid request body: " + detail, exception);
        }
    }

    private Throwable mapExceptionsErrors(Throwable error) {
        if (error instanceof IllegalArgumentException exception) {
            return ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message(exception.getMessage())
                .cause(exception)
                .haltError();
        }
        if (error instanceof DomainNotFoundException exception) {
            return ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message(exception.getMessage())
                .haltError();
        }
        if (error instanceof TeamCalendarNotFoundException exception) {
            return ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Team calendar does not exist: %s", exception.id().value())
                .haltError();
        }
        if (error instanceof DavClientException exception) {
            return ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error while calling the DAV server")
                .cause(exception)
                .haltError();
        }
        return error;
    }
}
