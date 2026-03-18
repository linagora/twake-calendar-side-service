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
import static com.linagora.calendar.storage.configuration.resolver.BusinessHoursSettingReader.BUSINESS_HOURS_IDENTIFIER;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.routes.dto.AvailabilityRuleDTO;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.configuration.resolver.BusinessHoursSettingReader;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkCreateRoute extends CalendarRoute {

    public record CreateBookingLinkRequestDTO(@JsonProperty("calendarUrl") String calendarUrl,
                                              @JsonProperty("durationMinutes") Integer durationMinutes,
                                              @JsonProperty("active") Boolean active,
                                              @JsonProperty("timeZone") Optional<String> timeZone,
                                              @JsonProperty("availabilityRules") Optional<List<AvailabilityRuleDTO>> availabilityRules) {

        private static final boolean ABSENT = true;

        public static BookingLinkInsertRequest toBookingLinkInsertRequest(CreateBookingLinkRequestDTO request,
                                                                          ZoneId defaultZone,
                                                                          Optional<AvailabilityRules> defaultAvailabilityRules) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(request.calendarUrl), "'calendarUrl' is required");
            Preconditions.checkArgument(!Objects.isNull(request.durationMinutes), "'durationMinutes' is required");
            Preconditions.checkArgument(request.durationMinutes > 0, "'durationMinutes' must be positive");
            Preconditions.checkArgument(!Objects.isNull(request.active), "'active' is required");

            if (request.timeZone.isPresent() && request.availabilityRules.map(List::isEmpty).orElse(ABSENT)) {
                throw new IllegalArgumentException("'timeZone' cannot be provided when 'availabilityRules' is null or empty");
            }

            CalendarURL calendarURL = CalendarURL.parse(request.calendarUrl);
            Duration duration = Duration.ofMinutes(request.durationMinutes);
            ZoneId timeZone = getTimeZone(request, defaultZone);

            Optional<AvailabilityRules> availabilityRules = getAvailabilityRules(request, timeZone).or(() -> defaultAvailabilityRules);

            return new BookingLinkInsertRequest(calendarURL, duration, request.active, availabilityRules);
        }

        private static ZoneId getTimeZone(CreateBookingLinkRequestDTO request, ZoneId defaultZone) {
            try {
                return request.timeZone()
                    .map(ZoneId::of)
                    .orElse(defaultZone);
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

    private static final DateTimeFormatter BUSINESS_HOURS_TIME_FORMATTER = DateTimeFormatter.ofPattern("H:m");

    private final BookingLinkDAO bookingLinkDAO;
    private final CalDavClient calDavClient;
    private final SettingsBasedResolver settingsResolver;

    @Inject
    public BookingLinkCreateRoute(Authenticator authenticator,
                                  MetricFactory metricFactory,
                                  BookingLinkDAO bookingLinkDAO,
                                  CalDavClient calDavClient,
                                  @Named("businessHours") SettingsBasedResolver settingsResolver) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
        this.settingsResolver = settingsResolver;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/booking-links");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body must not be empty")))
            .map(this::parseRequest)
            .flatMap(dto -> settingsResolver.resolveOrDefault(session.getUser())
                .map(resolvedSettings ->
                    CreateBookingLinkRequestDTO.toBookingLinkInsertRequest(dto, resolvedSettings.zoneId(), getDefaultAvailabilityRules(resolvedSettings))))
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
            if (e.getCause() instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e.getCause();
            }
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private Optional<AvailabilityRules> getDefaultAvailabilityRules(SettingsBasedResolver.ResolvedSettings resolvedSettings) {
        return resolvedSettings.get(BUSINESS_HOURS_IDENTIFIER, List.class)
            .map(list -> ((List<BusinessHoursSettingReader.BusinessHoursDto>) list).stream()
                .flatMap(dto -> toAvailabilityRuleList(dto, resolvedSettings.zoneId()).stream())
                .toList())
            .map(AvailabilityRules::new);
    }

    private List<AvailabilityRule> toAvailabilityRuleList(BusinessHoursSettingReader.BusinessHoursDto dto, ZoneId zoneId) {
        LocalTime start = LocalTime.parse(dto.start(), BUSINESS_HOURS_TIME_FORMATTER);
        LocalTime end = LocalTime.parse(dto.end(), BUSINESS_HOURS_TIME_FORMATTER);
        return dto.daysOfWeek().stream()
            .map(DayOfWeek::of)
            .map(day -> (AvailabilityRule) new AvailabilityRule.WeeklyAvailabilityRule(day, start, end, zoneId))
            .toList();
    }
}
