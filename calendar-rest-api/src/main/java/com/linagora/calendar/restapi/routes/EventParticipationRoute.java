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

import static com.linagora.calendar.api.EventParticipationActionLinkFactory.ACTION_PARAM;
import static com.linagora.calendar.api.EventParticipationActionLinkFactory.JWT_PARAM;
import static com.linagora.calendar.restapi.RestApiConstants.JSON_HEADER;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.amqp.EventEmailNotificationPublisher;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.api.EventParticipationActionLinkFactory;
import com.linagora.calendar.api.EventParticipationActionLinkFactory.ActionLinks;
import com.linagora.calendar.api.Participation;
import com.linagora.calendar.api.Participation.ParticipantAction;
import com.linagora.calendar.api.ParticipationTokenSigner;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventNotFoundException;
import com.linagora.calendar.dav.CalendarEventUpdatePatch.AttendeePartStatusUpdatePatch;
import com.linagora.calendar.dav.dto.VCalendarDto;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.ErrorType;
import com.linagora.calendar.restapi.routes.response.EventParticipationResponse;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.eventsearch.EventUid;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventParticipationRoute extends PublicRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventParticipationRoute.class);
    private static final String PATH = "/calendar/api/calendars/event/participation";
    private final ParticipationTokenSigner participationTokenSigner;
    private final CalDavEventRepository calDavEventRepository;
    private final SettingsBasedResolver settingsResolver;
    private final EventParticipationActionLinkFactory actionLinkFactory;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final EventEmailNotificationPublisher notificationPublisher;

    @Inject
    public EventParticipationRoute(MetricFactory metricFactory,
                                   ParticipationTokenSigner participationTokenSigner,
                                   CalDavEventRepository calDavEventRepository,
                                   @Named("language") SettingsBasedResolver settingsResolver,
                                   EventParticipationActionLinkFactory actionLinkFactory,
                                   OpenPaaSUserDAO openPaaSUserDAO,
                                   EventEmailNotificationPublisher notificationPublisher) {
        super(metricFactory);
        this.participationTokenSigner = participationTokenSigner;
        this.calDavEventRepository = calDavEventRepository;
        this.settingsResolver = settingsResolver;

        this.actionLinkFactory = actionLinkFactory;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.notificationPublisher = notificationPublisher;
    }

    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, PATH);
    }

    /**
     * Also served on POST, without payload, so that clients can apply the answer on an explicit user action
     * rather than on a GET that link scanners may trigger.
     */
    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.concat(super.routes(),
            Stream.of(JMAPRoute.builder()
                .endpoint(new Endpoint(HttpMethod.POST, PATH))
                .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), handleRequest(req, res))))
                .corsHeaders()));
    }

    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return validateAndExtractParticipation(request)
            .flatMap(participation -> handleValidParticipation(response, participation)
                .onErrorResume(IllegalArgumentException.class, exception -> {
                    LOGGER.warn("Participation token refers to an invalid event", exception);
                    return doBadRequest(response, exception);
                }))
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
                if (exception instanceof ParticipationTokenSigner.MissingParticipationActionException
                    || exception instanceof InvalidParticipationActionException) {
                    LOGGER.warn("Participation request without a valid answer", exception);
                    return doBadRequest(response, new IllegalArgumentException(exception.getMessage(), exception));
                }
                LOGGER.error("Unexpected error processing participation token", exception);
                return doOnError(response, exception);
            });
    }

    private Mono<Void> handleValidParticipation(HttpServerResponse response, Participation participation) {
        return updateParticipation(participation)
            .flatMap(vCalendarDto -> buildEventParticipationResponse(vCalendarDto.getLeft(), vCalendarDto.getRight(), participation))
            .map(Throwing.function(EventParticipationResponse::jsonAsBytes))
            .flatMap(bytes -> response.status(200)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<Participation> validateAndExtractParticipation(HttpServerRequest request) {
        return Mono.justOrEmpty(getJwtParameter(request))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Missing " + JWT_PARAM + " in request")))
            .flatMap(jwt -> participationTokenSigner.validateAndExtractParticipation(jwt, getActionParameter(request)));
    }

    private Mono<Pair<VCalendarDto, Boolean>> updateParticipation(Participation participationRequest) {
        Username attendeeUsername = Username.fromMailAddress(participationRequest.attendee());
        Username organizerUsername = Username.fromMailAddress(participationRequest.organizer());
        OpenPaaSId calendarId = new OpenPaaSId(participationRequest.calendarURI());
        EventUid eventUid = new EventUid(participationRequest.eventUid());
        PartStat partStat = participantActionToPartStat(participationRequest.action());
        AttendeePartStatusUpdatePatch patch = new AttendeePartStatusUpdatePatch(attendeeUsername, partStat, participationRequest.recurrenceId());

        return openPaaSUserDAO.retrieve(attendeeUsername)
            .hasElement()
            .flatMap(isAttendeeInternalUser -> {
                Username requestUser = resolveRequestUser(attendeeUsername, organizerUsername, isAttendeeInternalUser);
                return calDavEventRepository.updatePartStat(requestUser, calendarId, eventUid, patch)
                    .flatMap(partStatUpdate -> notifyOrganizer(participationRequest, partStatUpdate, patch, isAttendeeInternalUser)
                        .thenReturn(partStatUpdate.report()))
                    .map(VCalendarDto::from)
                    .map(dto -> Pair.of(dto, isAttendeeInternalUser));
            });
    }

    /**
     * Notifies the organizer of the answer of the attendee by an iTIP REPLY email.
     *
     * Only external attendees need it: answering on behalf of an internal attendee updates their own copy of the
     * event, upon which the DAV server already schedules an iTIP REPLY of its own.
     *
     * The REPLY only carries the VEVENTs the answer is for: a single occurrence answer is not one for the series.
     */
    private Mono<Void> notifyOrganizer(Participation participationRequest,
                                       CalDavEventRepository.PartStatUpdate partStatUpdate,
                                       AttendeePartStatusUpdatePatch patch,
                                       boolean isAttendeeInternalUser) {
        if (isAttendeeInternalUser) {
            return Mono.empty();
        }

        return Mono.justOrEmpty(partStatUpdate.updatedCalendar())
            .map(updatedCalendar -> answeredVEvents(updatedCalendar, patch))
            .flatMap(updatedCalendar -> notificationPublisher.publishReply(updatedCalendar,
                participationRequest.attendee(), participationRequest.organizer(),
                partStatUpdate.report().calendarHref().getPath()))
            .onErrorResume(error -> {
                LOGGER.error("Could not notify organizer {} of the participation change of {}",
                    participationRequest.organizer().asString(), participationRequest.attendee().asString(), error);
                return Mono.empty();
            });
    }

    private Calendar answeredVEvents(Calendar updatedCalendar, AttendeePartStatusUpdatePatch patch) {
        List<VEvent> answeredVEvents = updatedCalendar.<VEvent>getComponents(Component.VEVENT).stream()
            .filter(patch::targets)
            .toList();
        return CalendarUtil.withVEvents(updatedCalendar, answeredVEvents);
    }

    private Username resolveRequestUser(Username attendee, Username organizer, boolean isAttendeeInternalUser) {
        if (isAttendeeInternalUser) {
            return attendee;
        }
        return organizer;
    }

    private Mono<EventParticipationResponse> buildEventParticipationResponse(VCalendarDto eventDto,
                                                                             Boolean isAttendeeInternalUser,
                                                                             Participation participationRequest) {
        Mono<ActionLinks> generateLinks = actionLinkFactory.generateLinks(participationRequest.organizer(),
            participationRequest.attendee(), participationRequest.eventUid(), participationRequest.calendarURI(),
            participationRequest.recurrenceId());

        return Mono.zip(getLocale(participationRequest, isAttendeeInternalUser), generateLinks)
            .map(tuple -> new EventParticipationResponse(eventDto, participationRequest.attendee(),
                tuple.getT2(), tuple.getT1()));
    }

    private Mono<Locale> getLocale(Participation participationRequest, boolean isInternalAttendeeRequest) {
        if (isInternalAttendeeRequest) {
            return settingsResolver.resolveOrDefault(Username.fromMailAddress(participationRequest.attendee()),
                    Username.fromMailAddress(participationRequest.organizer()))
                .map(SettingsBasedResolver.ResolvedSettings::locale);
        }
        return settingsResolver.resolveOrDefault(Username.fromMailAddress(participationRequest.organizer()))
            .map(SettingsBasedResolver.ResolvedSettings::locale);
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
                ErrorType.UNAUTHORIZED,
                "Unauthorized",
                "JWT is missing or invalid").serializeAsBytes()))
            .then();
    }

    private Mono<Void> doNotFound(HttpServerResponse response, CalendarEventNotFoundException exception) {
        return response.status(HttpResponseStatus.NOT_FOUND)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(404, ErrorType.NOT_FOUND, "Not found", exception.getMessage())
                .serializeAsBytes()))
            .then();
    }

    private Mono<Void> doBadRequest(HttpServerResponse response, IllegalArgumentException exception) {
        return response.status(HttpResponseStatus.BAD_REQUEST)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(exception).serializeAsBytes()))
            .then();
    }

    private Mono<Void> doOnError(HttpServerResponse response, Exception exception) {
        return response.status(HttpResponseStatus.BAD_REQUEST)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(500, ErrorType.SERVER_ERROR, "Server Error", exception.getMessage())
                .serializeAsBytes()))
            .then();
    }

    private Optional<String> getJwtParameter(HttpServerRequest request) {
        return getParameter(request, JWT_PARAM);
    }

    /**
     * The answer is carried by the link, the token granting any answer. Links signed before carry it in their token.
     */
    private Optional<ParticipantAction> getActionParameter(HttpServerRequest request) {
        return getParameter(request, ACTION_PARAM)
            .map(this::parseAction);
    }

    private ParticipantAction parseAction(String action) {
        try {
            return ParticipantAction.valueOf(action);
        } catch (IllegalArgumentException e) {
            throw new InvalidParticipationActionException("Invalid " + ACTION_PARAM + ": " + action);
        }
    }

    private Optional<String> getParameter(HttpServerRequest request, String name) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(name, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny();
    }

    private static class InvalidParticipationActionException extends RuntimeException {
        InvalidParticipationActionException(String message) {
            super(message);
        }
    }
}