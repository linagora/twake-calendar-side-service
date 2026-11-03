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
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.restapi.routes.dto.AvailabilityRuleDTO;
import com.linagora.calendar.restapi.routes.dto.BookingLinkAlarmDTO;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.booking.BookingLinkColorUtil;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkExtraAttendeeUtil;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;
import com.linagora.calendar.storage.booking.BookingLinkResourceUtil;
import com.linagora.calendar.storage.booking.EventTransparency;
import com.linagora.calendar.storage.booking.EventVisibility;
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
                                              @JsonProperty("autoAccept") Optional<Boolean> autoAccept,
                                              @JsonProperty("availabilityRules") Optional<List<AvailabilityRuleDTO>> availabilityRules,
                                              @JsonProperty("extraAttendees") Optional<JsonNode> extraAttendees,
                                              @JsonProperty("name") Optional<String> name,
                                              @JsonProperty("description") Optional<String> description,
                                              @JsonProperty("color") Optional<String> color,
                                              @JsonProperty("location") Optional<String> location,
                                              @JsonProperty("visibility") Optional<String> visibility,
                                              @JsonProperty("transparency") Optional<String> transparency,
                                              @JsonProperty("resources") Optional<List<String>> resources,
                                              @JsonProperty("alarm") Optional<List<BookingLinkAlarmDTO>> alarm) {

        public static BookingLinkInsertRequest toBookingLinkInsertRequest(CreateBookingLinkRequestDTO request,
                                                                          ZoneId defaultZone,
                                                                          Optional<AvailabilityRules> defaultAvailabilityRules) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(request.calendarUrl), "'calendarUrl' is required");
            Preconditions.checkArgument(!Objects.isNull(request.durationMinutes), "'durationMinutes' is required");
            Preconditions.checkArgument(request.durationMinutes > 0, "'durationMinutes' must be positive");
            Preconditions.checkArgument(!Objects.isNull(request.active), "'active' is required");

            CalendarURL calendarURL = CalendarURL.parse(request.calendarUrl);
            Duration duration = Duration.ofMinutes(request.durationMinutes);

            Optional<AvailabilityRules> availabilityRules = getAvailabilityRules(request, defaultZone).or(() -> defaultAvailabilityRules);

            return new BookingLinkInsertRequest(calendarURL, duration, request.active,
                request.autoAccept.orElse(BookingLinkInsertRequest.AUTO_ACCEPT), availabilityRules,
                BookingLinkExtraAttendeeUtil.parse(request.extraAttendees),
                normalize(request.name), normalize(request.description), BookingLinkColorUtil.sanitize(request.color),
                normalize(request.location),
                normalize(request.visibility).map(EventVisibility::fromString),
                normalize(request.transparency).map(EventTransparency::fromString),
                BookingLinkResourceUtil.parse(request.resources),
                BookingLinkAlarmDTO.toBookingLinkAlarms(request.alarm));
        }

        private static Optional<String> normalize(Optional<String> value) {
            return value.map(String::trim).filter(s -> !s.isEmpty());
        }

        private static Optional<AvailabilityRules> getAvailabilityRules(CreateBookingLinkRequestDTO request, ZoneId defaultZone) {
            return request.availabilityRules()
                .filter(rules -> !rules.isEmpty())
                .map(rules -> AvailabilityRuleDTO.toAvailabilityRules(rules, defaultZone))
                .map(AvailabilityRules::new);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkCreateRoute.class);
    private static final String BOOKING_LINK_PUBLIC_ID = "bookingLinkPublicId";

    private static final DateTimeFormatter BUSINESS_HOURS_TIME_FORMATTER = DateTimeFormatter.ofPattern("H:m")
        .withResolverStyle(ResolverStyle.STRICT);

    private final BookingLinkDAO bookingLinkDAO;
    private final CalDavClient calDavClient;
    private final SettingsBasedResolver settingsResolver;
    private final BookingLinkExtraAttendeeResolver extraAttendeeResolver;
    private final BookingLinkResourceResolver resourceResolver;

    @Inject
    public BookingLinkCreateRoute(Authenticator authenticator,
                                  MetricFactory metricFactory,
                                  BookingLinkDAO bookingLinkDAO,
                                  CalDavClient calDavClient,
                                  @Named("businessHours") SettingsBasedResolver settingsResolver,
                                  BookingLinkExtraAttendeeResolver extraAttendeeResolver,
                                  BookingLinkResourceResolver resourceResolver) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
        this.settingsResolver = settingsResolver;
        this.extraAttendeeResolver = extraAttendeeResolver;
        this.resourceResolver = resourceResolver;
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
                    .then(extraAttendeeResolver.validate(session.getUser(), insertRequest.extraAttendees().participants()))
                    .then(resourceResolver.validate(session.getUser(), insertRequest.resources()))
                    .thenReturn(insertRequest))
            .flatMap(insertRequest -> bookingLinkDAO.insert(session.getUser(), insertRequest))
            .flatMap(bookingLink -> response.status(HttpResponseStatus.CREATED)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(
                    Map.of(BOOKING_LINK_PUBLIC_ID, bookingLink.publicId().value()))))
                .then());
    }

    private Mono<Void> validateCalendarAccess(CalendarURL calendarURL, MailboxSession session) {
        return calDavClient.resolveCalendarAccess(session.getUser(), calendarURL)
            .flatMap(access -> switch (access) {
                case WRITABLE -> Mono.<Void>empty();
                case READ_ONLY -> Mono.error(new ForbiddenException("User does not have write access to calendar: " + calendarURL.asUri()));
                case NOT_FOUND -> Mono.error(new IllegalArgumentException("Calendar not found or access denied: " + calendarURL.asUri()));
            });
    }

    private CreateBookingLinkRequestDTO parseRequest(byte[] body) {
        CreateBookingLinkRequestDTO dto;
        try {
            dto = OBJECT_MAPPER_DEFAULT.readValue(body, CreateBookingLinkRequestDTO.class);
        } catch (Exception e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e.getCause();
            }
            throw new IllegalArgumentException("Invalid request body", e);
        }
        Preconditions.checkArgument(dto != null, "Invalid request body");
        return dto;
    }

    static Optional<AvailabilityRules> getDefaultAvailabilityRules(SettingsBasedResolver.ResolvedSettings resolvedSettings) {
        return resolvedSettings.get(BUSINESS_HOURS_IDENTIFIER, List.class)
            .map(list -> ((List<BusinessHoursSettingReader.BusinessHoursDto>) list).stream()
                .filter(Objects::nonNull)
                .flatMap(dto -> toAvailabilityRules(dto, resolvedSettings.zoneId()))
                .toList())
            .filter(rules -> !rules.isEmpty())
            .map(AvailabilityRules::new);
    }

    private static Stream<AvailabilityRule> toAvailabilityRules(BusinessHoursSettingReader.BusinessHoursDto dto, ZoneId zoneId) {
        Optional<LocalTime> start = parseBusinessHoursTime(dto.start());
        Optional<LocalTime> end = parseBusinessHoursTime(dto.end());
        if (start.isEmpty() || end.isEmpty() || !start.get().isBefore(end.get())) {
            LOGGER.warn("Ignoring stored business hours with invalid time range: {}", dto);
            return Stream.empty();
        }
        return validDaysOfWeek(dto)
            .map(day -> new AvailabilityRule.WeeklyAvailabilityRule(day, start.get(), end.get(), zoneId));
    }

    private static Optional<LocalTime> parseBusinessHoursTime(String time) {
        try {
            return Optional.ofNullable(time)
                .map(value -> LocalTime.parse(value, BUSINESS_HOURS_TIME_FORMATTER));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Stream<DayOfWeek> validDaysOfWeek(BusinessHoursSettingReader.BusinessHoursDto dto) {
        return Optional.ofNullable(dto.daysOfWeek()).stream()
            .flatMap(List::stream)
            .filter(day -> isValidDayOfWeek(day, dto))
            .map(DayOfWeek::of);
    }

    private static boolean isValidDayOfWeek(Integer day, BusinessHoursSettingReader.BusinessHoursDto dto) {
        boolean valid = day != null && day >= DayOfWeek.MONDAY.getValue() && day <= DayOfWeek.SUNDAY.getValue();
        if (!valid) {
            LOGGER.warn("Ignoring invalid day of week {} in stored business hours: {}", day, dto);
        }
        return valid;
    }
}
