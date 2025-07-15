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
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.api.Participation;
import com.linagora.calendar.api.Participation.ParticipantAction;
import com.linagora.calendar.api.ParticipationTokenSigner;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventNotFoundException;
import com.linagora.calendar.dav.dto.VCalendarDto;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.routes.response.EventParticipationResponse;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.EventUid;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventParticipationRoute implements JMAPRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventParticipationRoute.class);
    private static final String JWT_PARAM = "jwt";
    private final MetricFactory metricFactory;
    private final ParticipationTokenSigner participationTokenSigner;
    private final CalDavEventRepository calDavEventRepository;
    private final Function<String, URI> buildParticipationActionLinkFunction;
    private final UserSettingBasedLocator userSettingBasedLocator;

    @Inject
    public EventParticipationRoute(MetricFactory metricFactory,
                                   ParticipationTokenSigner participationTokenSigner,
                                   CalDavEventRepository calDavEventRepository,
                                   @Named("spaExcalUrl") URL spaCalendarUrl,
                                   UserSettingBasedLocator userSettingBasedLocator) {
        this.metricFactory = metricFactory;
        this.participationTokenSigner = participationTokenSigner;
        this.calDavEventRepository = calDavEventRepository;
        this.userSettingBasedLocator = userSettingBasedLocator;

        String baseUrl = spaCalendarUrl.toString();
        this.buildParticipationActionLinkFunction = jwt -> URI.create(StringUtils.removeEnd(baseUrl, "/") + "/calendar/#/calendar/participation/?jwt=" + jwt);
    }

    private Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/calendar/api/calendars/event/participation");
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(
            JMAPRoute.builder()
                .endpoint(endpoint())
                .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), handleRequest(req, res))))
                .corsHeaders());
    }

    private Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return validateAndExtractParticipation(request)
            .flatMap(participation -> handleValidParticipation(response, participation))
            .onErrorResume(Exception.class, exception -> {
                if (exception instanceof ParticipationTokenSigner.ParticipationTokenClaimException
                    || exception instanceof IllegalArgumentException) {
                    LOGGER.warn("Invalid participation token", exception);
                    return doUnauthorized(response);
                }
                if (exception instanceof CalendarEventNotFoundException notFoundException) {
                    LOGGER.warn("Participation token refers to a non-existing event", exception);
                    return doNotFound(response, notFoundException);
                }
                LOGGER.error("Unexpected error processing participation token", exception);
                return doOnError(response, exception);
            });
    }

    private Mono<Void> handleValidParticipation(HttpServerResponse response, Participation participation) {
        return updateParticipation(participation)
            .flatMap(vCalendarDto -> buildEventParticipationResponse(vCalendarDto, participation))
            .map(Throwing.function(EventParticipationResponse::jsonAsBytes))
            .flatMap(bytes -> response.status(200)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<Participation> validateAndExtractParticipation(HttpServerRequest request) {
        return Mono.justOrEmpty(getJwtParameter(request))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Missing " + JWT_PARAM + " in request")))
            .flatMap(participationTokenSigner::validateAndExtractParticipation);
    }

    private Mono<VCalendarDto> updateParticipation(Participation participationRequest) {
        Username attendeeUsername = Username.fromMailAddress(participationRequest.attendee());
        OpenPaaSId calendarId = new OpenPaaSId(participationRequest.calendarURI());

        return calDavEventRepository.updatePartStat(attendeeUsername, calendarId,
                new EventUid(participationRequest.eventUid()),
                participantActionToPartStat(participationRequest.action()))
            .map(VCalendarDto::from);
    }

    private Mono<EventParticipationResponse> buildEventParticipationResponse(VCalendarDto eventDto,
                                                                             Participation participationRequest) {
        return Mono.zip(userSettingBasedLocator.getLanguage(Username.fromMailAddress(participationRequest.attendee()),
                    Username.fromMailAddress(participationRequest.organizer()))
                .map(Language::locale), generateLinks(participationRequest))
            .map(tuple -> new EventParticipationResponse(eventDto, participationRequest.attendee(),
                tuple.getT2(), tuple.getT1()));
    }

    private Mono<EventParticipationResponse.Links> generateLinks(Participation participationRequest) {
        return Mono.zip(participationTokenSigner
                    .signAsJwt(participationRequest.withAction(ParticipantAction.ACCEPTED)),
                participationTokenSigner
                    .signAsJwt(participationRequest.withAction(ParticipantAction.REJECTED)),
                participationTokenSigner
                    .signAsJwt(participationRequest.withAction(ParticipantAction.TENTATIVE)))
            .map(tokens -> {
                URI yesLink = buildParticipationActionLinkFunction.apply(tokens.getT1());
                URI noLink = buildParticipationActionLinkFunction.apply(tokens.getT2());
                URI maybeLink = buildParticipationActionLinkFunction.apply(tokens.getT3());
                return new EventParticipationResponse.Links(yesLink, noLink, maybeLink);
            });
    }

    private PartStat participantActionToPartStat(Participation.ParticipantAction action) {
        return switch (action) {
            case ACCEPTED -> PartStat.ACCEPTED;
            case REJECTED -> PartStat.DECLINED;
            case TENTATIVE -> PartStat.TENTATIVE;
        };
    }

    private Mono<Void> doUnauthorized(HttpServerResponse response) {
        return response.status(HttpResponseStatus.UNAUTHORIZED)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(
                401,
                "Unauthorized",
                "JWT is missing or invalid").serializeAsBytes()))
            .then();
    }

    private Mono<Void> doNotFound(HttpServerResponse response, CalendarEventNotFoundException exception) {
        return response.status(HttpResponseStatus.NOT_FOUND)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(404, "Not found", exception.getMessage())
                .serializeAsBytes()))
            .then();
    }

    private Mono<Void> doOnError(HttpServerResponse response, Exception exception) {
        return response.status(HttpResponseStatus.BAD_REQUEST)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(500, "Server Error", exception.getMessage())
                .serializeAsBytes()))
            .then();
    }

    private Optional<String> getJwtParameter(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(JWT_PARAM, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny();
    }
}