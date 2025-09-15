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

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.api.JwtVerifier;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventNotFoundException;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceNotFoundException;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import io.jsonwebtoken.Claims;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ResourceParticipationRoute implements JMAPRoutes {

    record UpdatePartStatRequest(ResourceId resourceId,
                                 String eventId,
                                 PartStat partStat) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceParticipationRoute.class);

    private static final String RESOURCE_ID_PATH_PARAM = "resourceId";
    private static final String EVENT_PATH_ID_PATH_PARAM = "eventPathId";
    private static final String PART_STAT_QUERY_PARAM = "status";
    private static final String REFERRER_QUERY_PARAM = "referrer";
    private static final String EXPECTED_REFERRER = "email";
    private static final String JWT_QUERY_PARAM = "jwt";
    private static final String JWT_CLAIM_RESOURCE_ID = "resourceId";
    private static final String JWT_CLAIM_EVENT_ID = "eventId";

    private final CalDavEventRepository calDavEventRepository;
    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;
    private final URI locationRedirectUri;
    private final MetricFactory metricFactory;
    private final JwtVerifier jwtVerifier;

    @Inject
    public ResourceParticipationRoute(CalDavEventRepository calDavEventRepository,
                                      ResourceDAO resourceDAO,
                                      OpenPaaSDomainDAO openPaaSDomainDAO,
                                      @Named("spaCalendarUrl") URL calendarBaseUrl,
                                      JwtVerifier jwtVerifier,
                                      MetricFactory metricFactory) {
        this.calDavEventRepository = calDavEventRepository;
        this.resourceDAO = resourceDAO;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.locationRedirectUri = URI.create(Strings.CS.removeEnd(calendarBaseUrl.toString(), "/") + "/#/calendar");
        this.jwtVerifier = jwtVerifier;
        this.metricFactory = metricFactory;
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET,
            String.format("/calendar/api/resources/{%s}/{%s}/participation",
                RESOURCE_ID_PATH_PARAM, EVENT_PATH_ID_PATH_PARAM));
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(
            JMAPRoute.builder()
                .endpoint(endpoint())
                .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), handleRequest(req, res))))
                .corsHeaders());
    }

    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return validateAndExtractUpdateRequest(request)
            .flatMap(updateReq -> resourceDAO.findById(updateReq.resourceId())
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(updateReq.resourceId())))
                .flatMap(resource -> updateResourceParticipation(resource, updateReq.eventId(), updateReq.partStat())))
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

    private Mono<UpdatePartStatRequest> validateAndExtractUpdateRequest(HttpServerRequest request) {
        ResourceId pathResourceId = extractResourceId(request);
        String pathEventId = extractEventPathId(request);
        PartStat partStat = validateAndExtractPartStatParam(request);
        validateReferrer(request);

        return Mono.justOrEmpty(getJwtParameter(request))
            .switchIfEmpty(Mono.error(new ForbiddenException("Missing " + JWT_QUERY_PARAM + " in request")))
            .map(jwtVerifier::verify)
            .map(this::claimJwtToken)
            .map(pair -> {
                if (!pathResourceId.equals(pair.getLeft()) || !pathEventId.equals(pair.getRight())) {
                    throw new ForbiddenException("JWT does not match path parameter");
                }
                return new UpdatePartStatRequest(pathResourceId, pathEventId, partStat);
            })
            .subscribeOn(Schedulers.parallel());
    }

    private Optional<String> getJwtParameter(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(JWT_QUERY_PARAM, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny();
    }

    private Pair<ResourceId, String> claimJwtToken(Claims map) {
        List<String> requiredKeys = ImmutableList.of(JWT_CLAIM_RESOURCE_ID, JWT_CLAIM_EVENT_ID);
        List<String> missingKeys = requiredKeys.stream()
            .filter(key -> !map.containsKey(key))
            .toList();

        if (!missingKeys.isEmpty()) {
            throw new IllegalArgumentException("Missing required keys in claim map: " + missingKeys);
        }

        return Pair.of(new ResourceId(String.valueOf(map.get(JWT_CLAIM_RESOURCE_ID))), String.valueOf(map.get(JWT_CLAIM_EVENT_ID)));
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
        return response.status(302)
            .header(HttpHeaderNames.LOCATION, locationRedirectUri.toASCIIString())
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
