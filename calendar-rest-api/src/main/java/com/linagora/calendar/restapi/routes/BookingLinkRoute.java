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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkRoute extends CalendarRoute {

    public static class AvailabilityRuleDTO {
        @JsonProperty("type")
        public String type;

        @JsonProperty("dayOfWeek")
        public Integer dayOfWeek;

        @JsonProperty("start")
        public String start;

        @JsonProperty("end")
        public String end;

        public AvailabilityRule toAvailabilityRule(ZoneId timeZone) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(type), "'type' is required in availability rule");
            Preconditions.checkArgument(!Strings.isNullOrEmpty(start), "'start' is required in availability rule");
            Preconditions.checkArgument(!Strings.isNullOrEmpty(end), "'end' is required in availability rule");
            return switch (type) {
                case "weekly" -> {
                    Preconditions.checkArgument(dayOfWeek != null, "'dayOfWeek' is required for weekly rule");
                    DayOfWeek dayOfWeekObject = getDayOfWeek();
                    try {
                        yield new WeeklyAvailabilityRule(dayOfWeekObject, LocalTime.parse(start), LocalTime.parse(end), timeZone);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm", e);
                    }
                }
                case "fixed" -> {
                    try {
                        yield new FixedAvailabilityRule(LocalDateTime.parse(start).atZone(timeZone),
                            LocalDateTime.parse(end).atZone(timeZone));
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm", e);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown availability rule type: " + type);
            };
        }

        private DayOfWeek getDayOfWeek() {
            try {
                return DayOfWeek.of(dayOfWeek);
            } catch (Exception e) {
                throw new IllegalArgumentException("'dayOfWeek' must be an integer between 1 (Monday) and 7 (Sunday)", e);
            }
        }
    }

    public static class CreateBookingLinkRequest {
        @JsonProperty("calendarUrl")
        public String calendarUrl;

        @JsonProperty("durationMinutes")
        public Integer durationMinutes;

        @JsonProperty("active")
        public Boolean active;

        @JsonProperty("timeZone")
        public String timeZone;

        @JsonProperty("availabilityRules")
        public List<AvailabilityRuleDTO> availabilityRules;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkRoute.class);
    private static final String BOOKING_LINK_PUBLIC_ID = "bookingLinkPublicId";

    private final BookingLinkDAO bookingLinkDAO;

    @Inject
    public BookingLinkRoute(Authenticator authenticator,
                            MetricFactory metricFactory,
                            BookingLinkDAO bookingLinkDAO) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/booking-links");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body must not be empty")))
            .flatMap(body -> Mono.fromCallable(() -> parseRequest(body)))
            .flatMap(this::buildInsertRequest)
            .flatMap(insertRequest -> bookingLinkDAO.insert(session.getUser(), insertRequest))
            .flatMap(bookingLink -> response.status(HttpResponseStatus.CREATED)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(
                    Map.of(BOOKING_LINK_PUBLIC_ID, bookingLink.publicId().value()))))
                .then());
    }

    private CreateBookingLinkRequest parseRequest(byte[] body) {
        try {
            return OBJECT_MAPPER_DEFAULT.readValue(body, CreateBookingLinkRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private Mono<BookingLinkInsertRequest> buildInsertRequest(CreateBookingLinkRequest request) {
        return Mono.fromCallable(() -> {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(request.calendarUrl), "'calendarUrl' is required");
            Preconditions.checkArgument(request.durationMinutes != null, "'durationMinutes' is required");
            Preconditions.checkArgument(request.durationMinutes > 0, "'durationMinutes' must be positive");
            Preconditions.checkArgument(request.active != null, "'active' is required");

            CalendarURL calendarURL = CalendarURL.parse(request.calendarUrl);
            Duration duration = Duration.ofMinutes(request.durationMinutes);
            ZoneId timeZone = Optional.ofNullable(request.timeZone)
                .filter(tz -> !tz.isEmpty())
                .map(ZoneId::of)
                .orElse(ZoneId.of("UTC"));

            Optional<AvailabilityRules> availabilityRules = Optional.empty();
            if (request.availabilityRules != null && !request.availabilityRules.isEmpty()) {
                List<AvailabilityRule> rules = request.availabilityRules.stream()
                    .map(dto -> dto.toAvailabilityRule(timeZone))
                    .toList();
                availabilityRules = Optional.of(new AvailabilityRules(rules));
            }

            return new BookingLinkInsertRequest(calendarURL, duration, request.active, availabilityRules);
        });
    }
}
