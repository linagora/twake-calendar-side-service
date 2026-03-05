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

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;
import com.linagora.calendar.restapi.routes.BookingLinkReservationException.SlotNotAvailableException;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkReservationRoute implements JMAPRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkReservationRoute.class);
    private static final String BOOKING_LINK_PUBLIC_ID_PARAM = "bookingLinkPublicId";

    private final MetricFactory metricFactory;
    private final BookingLinkReservationService bookingLinkReservationService;

    @Inject
    public BookingLinkReservationRoute(MetricFactory metricFactory,
                                       BookingLinkReservationService bookingLinkReservationService) {
        this.metricFactory = metricFactory;
        this.bookingLinkReservationService = bookingLinkReservationService;
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/booking-links/{%s}/book".formatted(BOOKING_LINK_PUBLIC_ID_PARAM));
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(JMAPRoute.builder()
            .endpoint(endpoint())
            .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), handleRequest(req, res))))
            .corsHeaders());
    }

    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        BookingLinkPublicId publicId = new BookingLinkPublicId(request.param(BOOKING_LINK_PUBLIC_ID_PARAM));

        return request.receive().aggregate().asByteArray()
            .flatMap(bytes -> Mono.fromCallable(() -> ReservationRequestDTO.parse(bytes).toBookingRequest()))
            .flatMap(bookingRequest -> bookingLinkReservationService.book(publicId, bookingRequest))
            .then(response.status(HttpResponseStatus.CREATED).send())
            .onErrorResume(Exception.class, exception -> handleError(request, response, exception));
    }

    private Mono<Void> handleError(HttpServerRequest request, HttpServerResponse response, Exception exception) {
        return switch (exception) {
            case BookingLinkNotFoundException notFound -> {
                LOGGER.warn("Booking link not found for [{}]: {}", request.uri(), notFound.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.NOT_FOUND, notFound);
            }
            case SlotNotAvailableException slotNotAvailableException -> {
                LOGGER.warn("Requested slot is unavailable (likely busy) for [{}]: {}", request.uri(), slotNotAvailableException.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_REQUEST, slotNotAvailableException);
            }
            case BookingLinkReservationException bookingLinkReservationException -> {
                LOGGER.error("Booking operation failed for [{}]", request.uri(), bookingLinkReservationException);
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.INTERNAL_SERVER_ERROR, bookingLinkReservationException);
            }
            case IllegalArgumentException illegalArgumentException -> {
                LOGGER.warn("Bad request for [{}]: {}", request.uri(), illegalArgumentException.getMessage());
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_REQUEST, illegalArgumentException);
            }
            default -> {
                LOGGER.error("Unexpected error for [{}]", request.uri(), exception);
                yield ErrorResponseHandler.handle(response, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception);
            }
        };
    }

    public record ReservationRequestDTO(@JsonProperty(value = "startUtc", required = true)
                                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startUtc,
                                        @JsonProperty(value = "creator", required = true) AttendeeDTO creator,
                                        @JsonProperty("additional_attendees") Set<AttendeeDTO> additionalAttendees,
                                        @JsonProperty(value = "eventTitle", required = true) String title,
                                        @JsonProperty("visioLink") Boolean visioLink,
                                        @JsonProperty("notes") String notes) {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

        public record AttendeeDTO(@JsonProperty(value = "name") String name,
                                  @JsonProperty(value = "email", required = true) String email) {
            BookingAttendee toAttendee() {
                return BookingAttendee.from(name, email);
            }

            static List<BookingAttendee> toAttendees(Set<AttendeeDTO> attendees) {
                return Optional.ofNullable(attendees)
                    .orElse(Set.of())
                    .stream()
                    .map(AttendeeDTO::toAttendee)
                    .toList();
            }
        }

        private static final int MAX_ADDITIONAL_ATTENDEES = 20;
        private static final int MAX_TITLE_LENGTH = 255;
        private static final int MAX_NOTES_LENGTH = 2000;

        public static ReservationRequestDTO parse(byte[] bytes) {
            try {
                return OBJECT_MAPPER.readValue(bytes, ReservationRequestDTO.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Missing or invalid request body", e);
            }
        }

        void validate() {
            Preconditions.checkNotNull(startUtc, "'startUtc' must not be null");
            Preconditions.checkNotNull(creator, "'creator' must not be null");
            Preconditions.checkArgument(StringUtils.isNotBlank(title), "'eventTitle' must not be blank");
            Preconditions.checkArgument(title.length() <= MAX_TITLE_LENGTH, "'eventTitle' must not exceed " + MAX_TITLE_LENGTH + " characters");
            Preconditions.checkArgument(additionalAttendees == null || additionalAttendees.size() <= MAX_ADDITIONAL_ATTENDEES,
                "'additional_attendees' must not exceed " + MAX_ADDITIONAL_ATTENDEES + " items");
            Preconditions.checkArgument(notes == null || notes.length() <= MAX_NOTES_LENGTH,
                "'eventNote' must not exceed " + MAX_NOTES_LENGTH + " characters");
            validateAdditionalAttendees();
        }

        private void validateAdditionalAttendees() {
            Set<String> set = new HashSet<>();
            for (AttendeeDTO attendeeDTO : Optional.ofNullable(additionalAttendees).orElse(Set.of())) {
                Preconditions.checkArgument(!Strings.CI.equals(attendeeDTO.email(), creator.email()),
                    "'additional_attendees' must not contain creator email");
                Preconditions.checkArgument(set.add(attendeeDTO.email().toLowerCase(Locale.US)),
                    "'additional_attendees' contains duplicate email: " + attendeeDTO.email());
            }
        }

        BookingRequest toBookingRequest() {
            validate();
            List<BookingAttendee> validatedAdditionalAttendees = AttendeeDTO.toAttendees(additionalAttendees);
            return new BookingRequest(startUtc, creator.toAttendee(), validatedAdditionalAttendees,
                title, Boolean.TRUE.equals(visioLink), notes);
        }
    }
}
