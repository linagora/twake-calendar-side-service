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
import java.util.Locale;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.api.EventParticipationActionLinkFactory;
import com.linagora.calendar.api.EventParticipationActionLinkFactory.ActionLinks;
import com.linagora.calendar.api.Participation;
import com.linagora.calendar.api.ParticipationTokenSigner;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventNotFoundException;
import com.linagora.calendar.dav.CalendarEventUpdatePatch.AttendeePartStatusUpdatePatch;
import com.linagora.calendar.dav.dto.CalendarReportJsonResponse;
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
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventParticipationRoute extends PublicRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventParticipationRoute.class);
    private static final String JWT_PARAM = "jwt";
    private final ParticipationTokenSigner participationTokenSigner;
    private final CalDavEventRepository calDavEventRepository;
    private final SettingsBasedResolver settingsResolver;
    private final EventParticipationActionLinkFactory actionLinkFactory;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    private final PublicAgendaDeclineNotifier publicAgendaDeclineNotifier;

    @Inject
    public EventParticipationRoute(MetricFactory metricFactory,
                                   ParticipationTokenSigner participationTokenSigner,
                                   CalDavEventRepository calDavEventRepository,
                                   @Named("language") SettingsBasedResolver settingsResolver,
                                   EventParticipationActionLinkFactory actionLinkFactory,
                                   OpenPaaSUserDAO openPaaSUserDAO,
                                   CalDavClient calDavClient,
                                   PublicAgendaDeclineNotifier publicAgendaDeclineNotifier) {
        super(metricFactory);
        this.participationTokenSigner = participationTokenSigner;
        this.calDavEventRepository = calDavEventRepository;
        this.settingsResolver = settingsResolver;

        this.actionLinkFactory = actionLinkFactory;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
        this.publicAgendaDeclineNotifier = publicAgendaDeclineNotifier;
    }

    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/calendar/api/calendars/event/participation");
    }

    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
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
            .flatMap(participationTokenSigner::validateAndExtractParticipation);
    }

    private Mono<Pair<VCalendarDto, Boolean>> updateParticipation(Participation participationRequest) {
        Username attendeeUsername = Username.fromMailAddress(participationRequest.attendee());
        Username organizerUsername = Username.fromMailAddress(participationRequest.organizer());
        OpenPaaSId calendarId = new OpenPaaSId(participationRequest.calendarURI());
        EventUid eventUid = new EventUid(participationRequest.eventUid());
        PartStat partStat = participantActionToPartStat(participationRequest.action());
        AttendeePartStatusUpdatePatch patch = new AttendeePartStatusUpdatePatch(attendeeUsername, partStat);

        return openPaaSUserDAO.retrieve(attendeeUsername)
            .hasElement()
            .flatMap(isAttendeeInternalUser -> {
                Username requestUser = resolveRequestUser(attendeeUsername, organizerUsername, isAttendeeInternalUser);
                return calDavEventRepository.updatePartStat(requestUser, calendarId, eventUid, patch)
                    .doOnNext(reportResponse -> notifyPublicAgendaDecline(participationRequest, reportResponse)
                        .subscribe())
                    .map(reportResponse -> Pair.of(VCalendarDto.from(reportResponse), isAttendeeInternalUser));
            });
    }

    /**
     * A public agenda booking only reaches its booker through the regular iTIP flow once the owner accepts it:
     * declining it would otherwise leave the booker waiting, so the decline email is sent from here.
     *
     * <p>This route answers every participation link of the product, so the bookings to notify are narrowed down in
     * two steps. First on the token alone: the action must be {@code REJECTED}, and the answering attendee must be the
     * organizer - {@link PublicAgendaProposalNotifier} mints a proposal email with both set to the agenda owner, so
     * any other pair is an ordinary invitee declining their own invitation rather than the owner ruling on a booking.
     * Testing this before the fetch keeps ordinary participation clicks free of any extra DAV call.
     *
     * <p>Then on the event itself, which only the fetch can tell: {@link BookedEventDeclined#from} yields an empty
     * optional unless the event carries {@code X-PUBLICLY-CREATED} and names a booker to write to through
     * {@code X-PUBLICLY-CREATOR}. An owner declining a regular event of their own calendar therefore mails nobody.
     */
    private Mono<Void> notifyPublicAgendaDecline(Participation participationRequest, CalendarReportJsonResponse reportResponse) {
        if (participationRequest.action() != Participation.ParticipantAction.REJECTED
            || !Strings.CI.equals(participationRequest.attendee().asString(), participationRequest.organizer().asString())) {
            return Mono.empty();
        }

        Username owner = Username.fromMailAddress(participationRequest.organizer());
        return calDavClient.fetchCalendarEvent(owner, reportResponse.calendarHref())
            .flatMap(calendarObject -> Mono.justOrEmpty(BookedEventDeclined.from(owner, calendarObject.calendarData())))
            .flatMap(publicAgendaDeclineNotifier::notify)
            .onErrorResume(error -> {
                LOGGER.warn("Failed to notify the booker that public agenda owner {} declined event {}",
                    owner.asString(), participationRequest.eventUid(), error);
                return Mono.empty();
            });
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
            participationRequest.attendee(), participationRequest.eventUid(), participationRequest.calendarURI());

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

    private Mono<Void> doOnError(HttpServerResponse response, Exception exception) {
        return response.status(HttpResponseStatus.BAD_REQUEST)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(500, ErrorType.SERVER_ERROR, "Server Error", exception.getMessage())
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