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
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.BookedEventTokenSigner;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEvent;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEventTokenClaimException;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookedEventCancelRoute implements JMAPRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookedEventCancelRoute.class);
    private static final String BOOKING_CONFIRMATION_TOKEN_PARAM = "bookingConfirmationToken";

    private final MetricFactory metricFactory;
    private final BookedEventTokenSigner bookedEventTokenSigner;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;

    @Inject
    public BookedEventCancelRoute(MetricFactory metricFactory,
                                  BookedEventTokenSigner bookedEventTokenSigner,
                                  OpenPaaSUserDAO openPaaSUserDAO,
                                  CalDavClient calDavClient) {
        this.metricFactory = metricFactory;
        this.bookedEventTokenSigner = bookedEventTokenSigner;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.DELETE, "/api/booked-event");
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(JMAPRoute.builder()
            .endpoint(endpoint())
            .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), handleRequest(req, res))))
            .corsHeaders());
    }

    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return extractToken(request)
            .flatMap(bookedEventTokenSigner::validateAndExtract)
            .flatMap(this::deleteBookedEvent)
            .then(response.status(HttpResponseStatus.NO_CONTENT).send())
            .onErrorResume(Exception.class, exception -> handleError(response, exception));
    }

    private Mono<Void> deleteBookedEvent(BookedEvent bookedEvent) {
        CalendarURL calendarURL = new CalendarURL(new OpenPaaSId(bookedEvent.ownerId()), new OpenPaaSId(bookedEvent.calendarId()));
        return openPaaSUserDAO.retrieve(new OpenPaaSId(bookedEvent.ownerId()))
            .flatMap(owner -> calDavClient.deleteCalendarEvent(owner.username(), calendarURL, bookedEvent.eventId()));
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
                LOGGER.warn("Invalid booked event token", e);
                yield doUnauthorized(response);
            }
            case IllegalArgumentException e -> {
                LOGGER.warn("Bad request for booked event cancellation: {}", e.getMessage());
                yield doUnauthorized(response);
            }
            default -> {
                LOGGER.error("Unexpected error processing booked event cancellation", exception);
                yield doServerError(response, exception);
            }
        };
    }

    private Mono<Void> doUnauthorized(HttpServerResponse response) {
        return response.status(HttpResponseStatus.UNAUTHORIZED)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(401, "Unauthorized", "JWT is missing or invalid").serializeAsBytes()))
            .then();
    }

    private Mono<Void> doServerError(HttpServerResponse response, Exception exception) {
        return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(500, "Server Error", exception.getMessage()).serializeAsBytes()))
            .then();
    }
}
