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

import static com.linagora.calendar.restapi.RestApiConstants.JSON_HEADER;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventNotFoundException;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceNotFoundException;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ResourceParticipationRoute extends CalendarRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceParticipationRoute.class);

    private static final String RESOURCE_ID_PATH_PARAM = "resourceId";
    private static final String EVENT_PATH_ID_PATH_PARAM = "eventPathId";
    private static final String PART_STAT_QUERY_PARAM = "status";
    private static final String REFERRER_QUERY_PARAM = "referrer";
    private static final String EXPECTED_REFERRER = "email";

    private final CalDavEventRepository calDavEventRepository;
    private final ResourceDAO resourceDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;

    @Inject
    public ResourceParticipationRoute(Authenticator authenticator, MetricFactory metricFactory,
                                      CalDavEventRepository calDavEventRepository, ResourceDAO resourceDAO,
                                      OpenPaaSUserDAO openPaaSUserDAO, OpenPaaSDomainDAO openPaaSDomainDAO) {
        super(authenticator, metricFactory);
        this.calDavEventRepository = calDavEventRepository;
        this.resourceDAO = resourceDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET,
            String.format("/calendar/api/resources/{%s}/{%s}/participation",
                RESOURCE_ID_PATH_PARAM, EVENT_PATH_ID_PATH_PARAM));
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        ResourceId resourceId = extractResourceId(request);
        String eventPathId = extractEventPathId(request);
        PartStat partStat = validateAndExtractPartStatParam(request);
        validateReferrer(request);

        return resourceDAO.findById(resourceId)
            .switchIfEmpty(Mono.error(new ResourceNotFoundException(resourceId)))
            .flatMap(resource -> authorizeResourceAdmin(session, resource.administrators())
                .then(updateResourceParticipation(resource, eventPathId, partStat)))
            .then(sendRedirectResponse(response))
            .onErrorResume(Exception.class, exception -> {
                if (exception instanceof ForbiddenException forbiddenException) {
                    return handleForbidden(response, forbiddenException);
                }
                if (exception instanceof ResourceNotFoundException
                    || exception instanceof CalendarEventNotFoundException) {
                    return handleNotFound(response, exception);
                }
                return handleServerError(response, exception);
            });
    }

    private Mono<Void> updateResourceParticipation(Resource resource,
                                                   String eventPathId,
                                                   PartStat partStat) {
        return openPaaSDomainDAO.retrieve(resource.domain())
            .flatMap(domain -> calDavEventRepository.updatePartStat(domain, resource.id(), eventPathId, partStat));
    }

    private Mono<Void> authorizeResourceAdmin(MailboxSession session,
                                              List<ResourceAdministrator> administrators) {
        if (administrators == null || administrators.isEmpty()) {
            return Mono.error(new ForbiddenException("Resource has no administrators defined"));
        }

        return openPaaSUserDAO.retrieve(session.getUser())
            .filter(openPaaSUser -> administrators.stream()
                .anyMatch(admin -> admin.refId().equals(openPaaSUser.id())))
            .switchIfEmpty(Mono.error(new ForbiddenException("User does not have admin permission for this resource")))
            .then();
    }

    private ResourceId extractResourceId(HttpServerRequest request) {
        return new ResourceId(request.param(RESOURCE_ID_PATH_PARAM));
    }

    private String extractEventPathId(HttpServerRequest request) {
        return request.param(EVENT_PATH_ID_PATH_PARAM);
    }

    private PartStat validateAndExtractPartStatParam(HttpServerRequest request) {
        return getParameter(request, PART_STAT_QUERY_PARAM)
            .map(PartStat::new)
            .filter(partStat -> PartStat.ACCEPTED.equals(partStat) || PartStat.DECLINED.equals(partStat))
            .orElseThrow(() -> new IllegalArgumentException("Missing or invalid partStat parameter: " + PART_STAT_QUERY_PARAM));
    }

    private void validateReferrer(HttpServerRequest request) {
        String referrer = getParameter(request, REFERRER_QUERY_PARAM)
            .orElseThrow(() -> new IllegalArgumentException("Missing referrer parameter: " + REFERRER_QUERY_PARAM));
        if (!EXPECTED_REFERRER.equalsIgnoreCase(referrer)) {
            throw new IllegalArgumentException("Invalid referrer parameter, expected: " + EXPECTED_REFERRER);
        }
    }

    private Optional<String> getParameter(HttpServerRequest request, String parameterName) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(parameterName, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny();
    }

    private Mono<Void> sendRedirectResponse(HttpServerResponse response) {
        return response.status(204)
            .send()
            .then();
    }

    private Mono<Void> handleNotFound(HttpServerResponse response, Exception exception) {
        LOGGER.warn("Resource not found: {}", exception.getMessage());
        return response.status(HttpResponseStatus.NOT_FOUND)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() ->
                ErrorResponse.of(404, "Not Found", exception.getMessage()).serializeAsBytes()))
            .then();
    }

    private Mono<Void> handleForbidden(HttpServerResponse response, ForbiddenException exception) {
        LOGGER.warn("Access denied: {}", exception.getMessage());
        return response.status(HttpResponseStatus.FORBIDDEN)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() ->
                ErrorResponse.of(403, "Forbidden", exception.getMessage()).serializeAsBytes()))
            .then();
    }

    private Mono<Void> handleServerError(HttpServerResponse response, Throwable exception) {
        LOGGER.error("Unexpected error while updating resource participation", exception);
        return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() ->
                ErrorResponse.of(500, "Internal Server Error", "Unexpected server error").serializeAsBytes()))
            .then();
    }

}
