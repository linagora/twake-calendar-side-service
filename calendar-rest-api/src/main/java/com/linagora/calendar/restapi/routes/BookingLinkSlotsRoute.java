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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.restapi.routes.response.BookingLinkSlotsResponse;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkSlotsRoute implements JMAPRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkSlotsRoute.class);

    private static final String BOOKING_LINK_PUBLIC_ID_PARAM = "bookingLinkPublicId";
    private static final String FROM_QUERY_PARAM = "from";
    private static final String TO_QUERY_PARAM = "to";
    private static final Duration MAX_QUERY_RANGE = Duration.ofDays(60);

    private final MetricFactory metricFactory;
    private final BookingLinkSlotsService bookingLinkSlotsService;

    @Inject
    public BookingLinkSlotsRoute(MetricFactory metricFactory,
                                 BookingLinkSlotsService bookingLinkSlotsService) {
        this.metricFactory = metricFactory;
        this.bookingLinkSlotsService = bookingLinkSlotsService;
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/booking-links/{%s}/slots".formatted(BOOKING_LINK_PUBLIC_ID_PARAM));
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(JMAPRoute.builder()
            .endpoint(endpoint())
            .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), handleRequest(req, res))))
            .corsHeaders());
    }

    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return Mono.fromCallable(() -> new QueryStringDecoder(request.uri()))
            .flatMap(queryStringDecoder -> {
                Instant queryStart = parseRequiredInstant(queryStringDecoder, FROM_QUERY_PARAM);
                Instant queryEnd = parseRequiredInstant(queryStringDecoder, TO_QUERY_PARAM);
                validateRange(queryStart, queryEnd);
                BookingLinkPublicId bookingLinkPublicId = new BookingLinkPublicId(UUID.fromString(request.param(BOOKING_LINK_PUBLIC_ID_PARAM)));

                return bookingLinkSlotsService.computeSlots(bookingLinkPublicId, queryStart, queryEnd)
                    .flatMap(result -> doResponse(response, queryStart, queryEnd, result.getLeft(), result.getRight()));
            })
            .onErrorResume(Exception.class, exception -> switch (exception) {
                case BookingLinkNotFoundException notFound -> {
                    LOGGER.warn("Booking link not found for [{}]: {}", request.uri(), notFound.getMessage());
                    yield ErrorResponseHandler.handle(response, HttpResponseStatus.NOT_FOUND, notFound);
                }
                case IllegalArgumentException illegalArgumentException -> {
                    LOGGER.warn("Bad request for [{}]: {}", request.uri(), illegalArgumentException.getMessage());
                    yield ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_REQUEST, illegalArgumentException);
                }
                default -> {
                    LOGGER.error("Unexpected error for [{}]", request.uri(), exception);
                    yield ErrorResponseHandler.handle(response, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception);
                }
            });
    }

    private void validateRange(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
        if (Duration.between(from, to).compareTo(MAX_QUERY_RANGE) > 0) {
            throw new IllegalArgumentException("Requested range is too large, max is " + MAX_QUERY_RANGE.toDays() + " days");
        }
    }

    private Instant parseRequiredInstant(QueryStringDecoder queryStringDecoder, String parameterName) {
        try {
            return queryStringDecoder.parameters().getOrDefault(parameterName, List.of())
                .stream()
                .findFirst()
                .map(Instant::parse)
                .orElseThrow();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Missing or invalid query parameter '%s'. Expected ISO-8601 instant format, e.g. 2007-12-03T10:15:30.00Z"
                .formatted(parameterName), e);
        }
    }

    private Mono<Void> doResponse(HttpServerResponse response,
                                  Instant queryStart,
                                  Instant queryEnd,
                                  BookingLink bookingLink,
                                  Set<AvailabilitySlot> slots) {
        return Mono.fromCallable(() -> BookingLinkSlotsResponse.of(bookingLink, queryStart, queryEnd, slots).jsonAsBytes())
            .flatMap(bytes -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.just(bytes))
                .then());
    }
}
