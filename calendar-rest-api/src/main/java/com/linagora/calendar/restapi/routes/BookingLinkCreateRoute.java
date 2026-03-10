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

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.DayOfWeekUtil;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkCreateRoute extends CalendarRoute {

    public record AvailabilityRuleDTO(@JsonProperty("type") String type,
                                      @JsonProperty("dayOfWeek") Optional<String> dayOfWeek,
                                      @JsonProperty("start") String start,
                                      @JsonProperty("end") String end) {

        public AvailabilityRule toAvailabilityRule(ZoneId timeZone) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(type), "'type' is required in availability rule");
            Preconditions.checkArgument(!Strings.isNullOrEmpty(start), "'start' is required in availability rule");
            Preconditions.checkArgument(!Strings.isNullOrEmpty(end), "'end' is required in availability rule");
            return switch (type) {
                case "weekly" -> {
                    DayOfWeek dayOfWeekObject = getDayOfWeek();
                    try {
                        yield new WeeklyAvailabilityRule(dayOfWeekObject, LocalTime.parse(start), LocalTime.parse(end), timeZone);
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm", e);
                    }
                }
                case "fixed" -> {
                    try {
                        yield new FixedAvailabilityRule(LocalDateTime.parse(start).atZone(timeZone),
                            LocalDateTime.parse(end).atZone(timeZone));
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm", e);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown availability rule type: " + type);
            };
        }

        private DayOfWeek getDayOfWeek() {
            return DayOfWeekUtil.fromAbbreviation(dayOfWeek.orElseThrow(() -> new IllegalArgumentException("'dayOfWeek' must be provided for weekly rule")));
        }
    }

    public record CreateBookingLinkRequestDTO(@JsonProperty("calendarUrl") String calendarUrl,
                                              @JsonProperty("durationMinutes") Integer durationMinutes,
                                              @JsonProperty("active") Boolean active,
                                              @JsonProperty("timeZone") Optional<String> timeZone,
                                              @JsonProperty("availabilityRules") Optional<List<AvailabilityRuleDTO>> availabilityRules) {

        public static BookingLinkInsertRequest toBookingLinkInsertRequest(CreateBookingLinkRequestDTO request) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(request.calendarUrl), "'calendarUrl' is required");
            Preconditions.checkArgument(!Objects.isNull(request.durationMinutes), "'durationMinutes' is required");
            Preconditions.checkArgument(request.durationMinutes > 0, "'durationMinutes' must be positive");
            Preconditions.checkArgument(!Objects.isNull(request.active), "'active' is required");

            CalendarURL calendarURL = CalendarURL.parse(request.calendarUrl);
            Duration duration = Duration.ofMinutes(request.durationMinutes);
            ZoneId timeZone = getTimeZone(request);

            Optional<AvailabilityRules> availabilityRules = getAvailabilityRules(request, timeZone);

            return new BookingLinkInsertRequest(calendarURL, duration, request.active, availabilityRules);
        }

        private static ZoneId getTimeZone(CreateBookingLinkRequestDTO request) {
            try {
                return request.timeZone()
                    .filter(tz -> !tz.isEmpty())
                    .map(ZoneId::of)
                    .orElse(UTC);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Invalid 'timeZone' format", e);
            }
        }

        private static Optional<AvailabilityRules> getAvailabilityRules(CreateBookingLinkRequestDTO request, ZoneId timeZone) {
            return request.availabilityRules()
                .filter(rules -> !rules.isEmpty())
                .map(rules -> rules.stream()
                    .map(dto -> dto.toAvailabilityRule(timeZone))
                    .toList())
                .map(AvailabilityRules::new);
        }
    }

    public static final ZoneId UTC = ZoneId.of("UTC");
    private static final String BOOKING_LINK_PUBLIC_ID = "bookingLinkPublicId";

    private final BookingLinkDAO bookingLinkDAO;
    private final CalDavClient calDavClient;

    @Inject
    public BookingLinkCreateRoute(Authenticator authenticator,
                                  MetricFactory metricFactory,
                                  BookingLinkDAO bookingLinkDAO,
                                  CalDavClient calDavClient) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/booking-links");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body must not be empty")))
            .map(this::parseRequest)
            .map(CreateBookingLinkRequestDTO::toBookingLinkInsertRequest)
            .flatMap(insertRequest ->
                validateCalendarAccess(insertRequest.calendarUrl(), session)
                    .thenReturn(insertRequest))
            .flatMap(insertRequest -> bookingLinkDAO.insert(session.getUser(), insertRequest))
            .flatMap(bookingLink -> response.status(HttpResponseStatus.CREATED)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(
                    Map.of(BOOKING_LINK_PUBLIC_ID, bookingLink.publicId().value()))))
                .then());
    }

    private Mono<Void> validateCalendarAccess(CalendarURL calendarURL, MailboxSession session) {
        return calDavClient.calendarExists(session.getUser(), calendarURL)
            .flatMap(exists -> {
                if (exists) {
                    return Mono.empty();
                }
                return Mono.error(new IllegalArgumentException("Calendar not found or access denied: " + calendarURL.asUri()));
            });
    }

    private CreateBookingLinkRequestDTO parseRequest(byte[] body) {
        try {
            return OBJECT_MAPPER_DEFAULT.readValue(body, CreateBookingLinkRequestDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }
}
