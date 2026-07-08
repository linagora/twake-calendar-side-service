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

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.BookedEventTokenSigner;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEvent;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEventTokenClaimException;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavCalendarObject;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.EventParseUtils;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import net.fortuna.ical4j.model.Calendar;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookedEventCancelRoute extends PublicRoute {

    static class PastBookedEventException extends RuntimeException {
        PastBookedEventException(String eventId) {
            super("Cannot cancel a booked event that already started: " + eventId);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BookedEventCancelRoute.class);
    private static final String BOOKING_CONFIRMATION_TOKEN_PARAM = "bookingConfirmationToken";

    private final Clock clock;
    private final BookedEventTokenSigner bookedEventTokenSigner;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    private final BookingLinkCancellationAcknowledgementNotifier bookerCancellationNotifier;
    private final PublicAgendaCancellationNotifier organizerCancellationNotifier;

    @Inject
    public BookedEventCancelRoute(MetricFactory metricFactory,
                                  Clock clock,
                                  BookedEventTokenSigner bookedEventTokenSigner,
                                  OpenPaaSUserDAO openPaaSUserDAO,
                                  CalDavClient calDavClient,
                                  BookingLinkCancellationAcknowledgementNotifier bookerCancellationNotifier,
                                  PublicAgendaCancellationNotifier organizerCancellationNotifier) {
        super(metricFactory);
        this.clock = clock;
        this.bookedEventTokenSigner = bookedEventTokenSigner;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
        this.bookerCancellationNotifier = bookerCancellationNotifier;
        this.organizerCancellationNotifier = organizerCancellationNotifier;
    }

    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.DELETE, "/api/booked-event");
    }

    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return extractToken(request)
            .flatMap(bookedEventTokenSigner::validateAndExtract)
            .flatMap(this::deleteBookedEvent)
            .then(response.status(HttpResponseStatus.NO_CONTENT).send())
            .onErrorResume(Exception.class, exception -> handleError(response, exception));
    }

    private Mono<Void> deleteBookedEvent(BookedEvent bookedEvent) {
        OpenPaaSId ownerId = new OpenPaaSId(bookedEvent.ownerId());
        CalendarURL calendarURL = new CalendarURL(ownerId, new OpenPaaSId(bookedEvent.calendarId()));
        return openPaaSUserDAO.retrieve(ownerId)
            .flatMap(owner -> cancelBookedEvent(owner, calendarURL, bookedEvent.eventId()));
    }

    private Mono<Void> cancelBookedEvent(OpenPaaSUser owner, CalendarURL calendarURL, String eventId) {
        Mono<Void> deleteEvent = calDavClient.deleteCalendarEvent(owner.username(), calendarURL, eventId);

        return fetchCalendarEvent(owner.username(), calendarURL, eventId)
            .flatMap(davCalendarObject -> rejectIfAlreadyStarted(davCalendarObject.calendarData(), eventId)
                .then(deleteEvent)
                .then(notifyCancellation(owner, davCalendarObject.calendarData()))
                .thenReturn(Boolean.TRUE))
            // A missing event yields an empty Mono and stays a no-op delete, keeping cancellation idempotent.
            .switchIfEmpty(deleteEvent.thenReturn(Boolean.TRUE))
            .then();
    }

    private Mono<DavCalendarObject> fetchCalendarEvent(Username username, CalendarURL calendarURL, String eventId) {
        URI eventHref = URI.create(calendarURL.asUri() + "/" + eventId + CalDavClient.ICS_EXTENSION);
        return calDavClient.fetchCalendarEvent(username, eventHref);
    }

    private Mono<Void> rejectIfAlreadyStarted(Calendar calendarData, String eventId) {
        // Prevent time travel: a booked event whose start time is in the past can no longer be cancelled.
        return Mono.fromCallable(() -> EventParseUtils.getStartTime(EventParseUtils.getFirstEvent(calendarData)).toInstant())
            .filter(startTime -> startTime.isBefore(clock.instant()))
            .flatMap(pastStartTime -> Mono.<Void>error(new PastBookedEventException(eventId)));
    }

    private Mono<Void> notifyCancellation(OpenPaaSUser owner, Calendar calendarData) {
        return Mono.fromCallable(() -> BookedEventCancelled.from(owner, calendarData))
            .flatMap(cancelled -> Mono.when(notifyBooker(cancelled), notifyOrganizer(cancelled)))
            .onErrorResume(error -> {
                LOGGER.warn("Failed to send booked event cancellation notifications for owner {}: {}",
                    owner.username().asString(), error.getMessage(), error);
                return Mono.empty();
            });
    }

    private Mono<Void> notifyBooker(BookedEventCancelled cancelled) {
        return bookerCancellationNotifier.notify(cancelled)
            .onErrorResume(error -> {
                LOGGER.warn("Failed to send booking cancellation acknowledgement to {}: {}",
                    cancelled.bookerEmail().asString(), error.getMessage(), error);
                return Mono.empty();
            });
    }

    private Mono<Void> notifyOrganizer(BookedEventCancelled cancelled) {
        return organizerCancellationNotifier.notify(cancelled)
            .onErrorResume(error -> {
                LOGGER.warn("Failed to send booked event cancellation notification to organizer {}: {}",
                    cancelled.organizer().username().asString(), error.getMessage(), error);
                return Mono.empty();
            });
    }

    private Mono<String> extractToken(HttpServerRequest request) {
        return Mono.justOrEmpty(getTokenParameter(request))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Missing " + BOOKING_CONFIRMATION_TOKEN_PARAM + " in request")));
    }

    private Optional<String> getTokenParameter(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters()
            .getOrDefault(BOOKING_CONFIRMATION_TOKEN_PARAM, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny();
    }

    private Mono<Void> handleError(HttpServerResponse response, Exception exception) {
        return switch (exception) {
            case BookedEventTokenClaimException e -> {
                LOGGER.info("Invalid booked event token: {}", e.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.UNAUTHORIZED, "bookingConfirmationToken is invalid");
            }
            case PastBookedEventException e -> {
                LOGGER.info("Attempt to cancel a past booked event: {}", e.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.UNPROCESSABLE_ENTITY, "Cannot cancel a booked event that already started");
            }
            case IllegalArgumentException e -> {
                LOGGER.info("Bad request for booked event cancellation: {}", e.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_REQUEST, e.getMessage());
            }
            default -> {
                LOGGER.error("Unexpected error processing booked event cancellation", exception);
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception);
            }
        };
    }
}
