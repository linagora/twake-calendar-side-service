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

import static com.linagora.calendar.restapi.RestApiConstants.OBJECT_MAPPER_DEFAULT;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.ValuePatch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.routes.dto.AvailabilityRuleDTO;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPatchRequest;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkPatchRoute extends CalendarRoute {

    private static final String PUBLIC_ID_PARAM = "bookingLinkPublicId";
    private static final String FIELD_CALENDAR_URL = "calendarUrl";
    private static final String FIELD_DURATION_MINUTES = "durationMinutes";
    private static final String FIELD_ACTIVE = "active";
    private static final String FIELD_AVAILABILITY_RULES = "availabilityRules";
    private static final String FIELD_TIME_ZONE = "timeZone";

    public record PatchDto(@JsonProperty(FIELD_CALENDAR_URL) Optional<String> calendarUrl,
                           @JsonProperty(FIELD_DURATION_MINUTES) Optional<Integer> durationMinutes,
                           @JsonProperty(FIELD_ACTIVE) Optional<Boolean> active,
                           @JsonProperty(FIELD_TIME_ZONE) Optional<String> timeZone,
                           @JsonProperty(FIELD_AVAILABILITY_RULES) Optional<List<AvailabilityRuleDTO>> availabilityRules) {
    }

    private final BookingLinkDAO bookingLinkDAO;
    private final CalDavClient calDavClient;

    @Inject
    public BookingLinkPatchRoute(Authenticator authenticator,
                                 MetricFactory metricFactory,
                                 BookingLinkDAO bookingLinkDAO, CalDavClient calDavClient) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.PATCH, "/booking-links/{" + PUBLIC_ID_PARAM + "}");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        BookingLinkPublicId publicId = new BookingLinkPublicId(UUID.fromString(request.param(PUBLIC_ID_PARAM)));

        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body must not be empty")))
            .map(this::parsePatchRequest)
            .flatMap(patchRequest ->
                patchRequest.calendarUrl().toOptional().map(calendarURL ->
                    validateCalendarAccess(calendarURL, session).thenReturn(patchRequest)).orElse(Mono.just(patchRequest)))
            .flatMap(patchRequest -> bookingLinkDAO.update(session.getUser(), publicId, patchRequest))
            .then(response.status(HttpResponseStatus.NO_CONTENT).send().then())
            .onErrorResume(BookingLinkNotFoundException.class, e ->
                response.status(HttpResponseStatus.NOT_FOUND).send().then());
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

    private BookingLinkPatchRequest parsePatchRequest(byte[] body) {
        try {
            JsonNode node = OBJECT_MAPPER_DEFAULT.readTree(body);
            PatchDto dto = parseJSONtoPatchDto(body);
            return new BookingLinkPatchRequest(
                parseCalendarUrl(node, dto),
                parseDuration(node, dto),
                parseActive(node, dto),
                parseAvailabilityRules(node, dto));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private PatchDto parseJSONtoPatchDto(byte[] body) {
        try {
            return OBJECT_MAPPER_DEFAULT.readValue(body, PatchDto.class);
        } catch (Exception e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e.getCause();
            }
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private ValuePatch<CalendarURL> parseCalendarUrl(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_CALENDAR_URL)) {
            return ValuePatch.keep();
        }
        return dto.calendarUrl.map(CalendarURL::parse)
            .map(ValuePatch::modifyTo)
            .orElseThrow(() -> new IllegalArgumentException("'calendarUrl' cannot be removed"));
    }

    private ValuePatch<Duration> parseDuration(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_DURATION_MINUTES)) {
            return ValuePatch.keep();
        }
        return dto.durationMinutes.map(durationMinutes -> {
                Preconditions.checkArgument(durationMinutes > 0, "'durationMinutes' must be positive");
                return Duration.ofMinutes(durationMinutes);
            }).map(ValuePatch::modifyTo)
            .orElseThrow(() -> new IllegalArgumentException("'durationMinutes' cannot be removed"));
    }

    private ValuePatch<Boolean> parseActive(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_ACTIVE)) {
            return ValuePatch.keep();
        }
        return dto.active.map(ValuePatch::modifyTo).orElseThrow(() -> new IllegalArgumentException("'active' cannot be removed"));
    }

    private ValuePatch<AvailabilityRules> parseAvailabilityRules(JsonNode node, PatchDto dto) {
        if (!node.has(FIELD_AVAILABILITY_RULES)) {
            Preconditions.checkArgument(dto.timeZone().isEmpty(), "'timeZone' cannot be provided if 'availabilityRules' is not being updated");
            return ValuePatch.keep();
        }
        return dto.availabilityRules.map(rules -> rules.stream()
            .map(availabilityRuleDTO -> availabilityRuleDTO.toAvailabilityRule(
                dto.timeZone().map(this::toZoneId).orElseThrow(() -> new IllegalArgumentException("'timeZone' must be provided when updating 'availabilityRules'"))))
            .toList())
            .map(ruleList -> {
                Preconditions.checkArgument(!ruleList.isEmpty(), "'availabilityRules' cannot be empty if provided");
                return new AvailabilityRules(ruleList);
            })
            .map(ValuePatch::modifyTo)
            .orElseGet(() -> {
                Preconditions.checkArgument(dto.timeZone().isEmpty(), "'timeZone' cannot be provided if 'availabilityRules' is being removed");
                return ValuePatch.remove();
            });
    }

    private ZoneId toZoneId(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid 'timeZone' format", e);
        }
    }
}
