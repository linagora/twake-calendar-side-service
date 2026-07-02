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
import static com.linagora.calendar.restapi.RestApiConstants.OBJECT_MAPPER_DEFAULT;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.api.BookedEventTokenSigner;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEvent;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEventTokenClaimException;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.dto.VCalendarDto;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookedEventGetRoute extends PublicRoute {

    static class EventNotFoundException extends RuntimeException {
        EventNotFoundException(String eventId) {
            super("Booked event not found: " + eventId);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record BookedEventResponse(@JsonProperty("eventJSON") JsonNode eventJSON,
                                      @JsonProperty("owner") String owner,
                                      @JsonProperty("bookingLinkMail") String bookingLinkMail,
                                      @JsonProperty("bookingLinkDescription") Optional<String> bookingLinkDescription) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BookedEventGetRoute.class);
    private static final String BOOKING_CONFIRMATION_TOKEN_PARAM = "bookingConfirmationToken";

    private final BookedEventTokenSigner bookedEventTokenSigner;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    private final BookingLinkDAO bookingLinkDAO;

    @Inject
    public BookedEventGetRoute(MetricFactory metricFactory,
                               BookedEventTokenSigner bookedEventTokenSigner,
                               OpenPaaSUserDAO openPaaSUserDAO,
                               CalDavClient calDavClient,
                               BookingLinkDAO bookingLinkDAO) {
        super(metricFactory);
        this.bookedEventTokenSigner = bookedEventTokenSigner;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
        this.bookingLinkDAO = bookingLinkDAO;
    }

    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/booked-event");
    }

    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return extractToken(request)
            .flatMap(bookedEventTokenSigner::validateAndExtract)
            .flatMap(this::fetchBookedEvent)
            .flatMap(dto -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(dto)))
                .then())
            .onErrorResume(Exception.class, exception -> handleError(response, exception));
    }

    private Mono<BookedEventResponse> fetchBookedEvent(BookedEvent bookedEvent) {
        return openPaaSUserDAO.retrieve(new OpenPaaSId(bookedEvent.ownerId()))
            .flatMap(owner -> fetchEvent(owner, bookedEvent)
                .flatMap(vCalendar -> toResponse(owner, bookedEvent, vCalendar)));
    }

    private Mono<VCalendarDto> fetchEvent(OpenPaaSUser owner, BookedEvent bookedEvent) {
        return calDavClient.calendarReportByUid(
                owner.username(),
                new OpenPaaSId(bookedEvent.calendarId()),
                bookedEvent.eventId())
            .map(VCalendarDto::from)
            .switchIfEmpty(Mono.error(new EventNotFoundException(bookedEvent.eventId())));
    }

    private Mono<BookedEventResponse> toResponse(OpenPaaSUser owner, BookedEvent bookedEvent, VCalendarDto vCalendar) {
        return bookingLinkDAO.findByPublicId(new BookingLinkPublicId(bookedEvent.publicBookingLinkId()))
            .map(BookingLink::description)
            .defaultIfEmpty(Optional.empty())
            .map(description -> new BookedEventResponse(vCalendar.value(),
                owner.fullName(),
                owner.username().asString(),
                description));
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
            case IllegalArgumentException e -> {
                LOGGER.info("Bad request for booked event retrieval: {}", e.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_REQUEST, e.getMessage());
            }
            case EventNotFoundException e -> {
                LOGGER.warn("Booked event not found: {}", e.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.NOT_FOUND, "Booked event not found");
            }
            default -> {
                LOGGER.error("Unexpected error processing booked event retrieval", exception);
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception);
            }
        };
    }
}
