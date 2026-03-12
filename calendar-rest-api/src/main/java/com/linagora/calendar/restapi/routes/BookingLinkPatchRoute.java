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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.lang3.stream.Streams;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.ValuePatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.DayOfWeekUtil;
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
    private static final ZoneId UTC = ZoneId.of("UTC");

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
            ZoneId timeZone = parseTimeZone(node);
            return new BookingLinkPatchRequest(
                parseCalendarUrl(node),
                parseDuration(node),
                parseActive(node),
                parseAvailabilityRules(node, timeZone));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private ValuePatch<CalendarURL> parseCalendarUrl(JsonNode node) {
        if (!node.has("calendarUrl")) {
            return ValuePatch.keep();
        }
        if (node.get("calendarUrl").isNull()) {
            return ValuePatch.remove();
        }
        Preconditions.checkArgument(node.get("calendarUrl").isTextual(), "'calendarUrl' must be a string");
        return ValuePatch.modifyTo(CalendarURL.parse(node.get("calendarUrl").asText()));
    }

    private ValuePatch<Duration> parseDuration(JsonNode node) {
        if (!node.has("durationMinutes")) {
            return ValuePatch.keep();
        }
        if (node.get("durationMinutes").isNull()) {
            return ValuePatch.remove();
        }
        Preconditions.checkArgument(node.get("durationMinutes").isInt(), "'durationMinutes' must be an integer");
        int minutes = node.get("durationMinutes").intValue();
        Preconditions.checkArgument(minutes > 0, "'durationMinutes' must be positive");
        return ValuePatch.modifyTo(Duration.ofMinutes(minutes));
    }

    private ValuePatch<Boolean> parseActive(JsonNode node) {
        if (!node.has("active")) {
            return ValuePatch.keep();
        }
        if (node.get("active").isNull()) {
            return ValuePatch.remove();
        }
        Preconditions.checkArgument(node.get("active").isBoolean(), "'active' must be a boolean");
        return ValuePatch.modifyTo(node.get("active").booleanValue());
    }

    private ZoneId parseTimeZone(JsonNode node) {
        if (!node.has("timeZone") || node.get("timeZone").isNull()) {
            return UTC;
        }
        Preconditions.checkArgument(node.get("timeZone").isTextual(), "'timeZone' must be a string if present");
        try {
            return ZoneId.of(node.get("timeZone").asText());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid 'timeZone' format", e);
        }
    }

    private ValuePatch<AvailabilityRules> parseAvailabilityRules(JsonNode node, ZoneId timeZone) {
        if (!node.has("availabilityRules")) {
            return ValuePatch.keep();
        }
        if (node.get("availabilityRules").isNull()) {
            return ValuePatch.remove();
        }
        Preconditions.checkArgument(node.get("availabilityRules").isArray(), "'availabilityRules' must be an array if present");
        List<AvailabilityRule> rules = Streams.of(node.withArray("availabilityRules").elements()).map(ruleNode -> parseAvailabilityRule(ruleNode, timeZone)).toList();
        return ValuePatch.modifyTo(new AvailabilityRules(rules));
    }

    private AvailabilityRule parseAvailabilityRule(JsonNode node, ZoneId timeZone) {
        String type = getFieldValueFromJson(node, "type");
        String start = getFieldValueFromJson(node, "start");
        String end = getFieldValueFromJson(node, "end");
        return switch (type) {
            case "weekly" -> {
                String dayOfWeek = getDayOfWeek(node);
                try {
                    yield new WeeklyAvailabilityRule(
                        DayOfWeekUtil.fromAbbreviation(dayOfWeek),
                        LocalTime.parse(start),
                        LocalTime.parse(end),
                        timeZone);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm", e);
                }
            }
            case "fixed" -> {
                try {
                    yield new FixedAvailabilityRule(
                        LocalDateTime.parse(start).atZone(timeZone),
                        LocalDateTime.parse(end).atZone(timeZone));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm:ss", e);
                }
            }
            default -> throw new IllegalArgumentException("Unknown availability rule type: " + type);
        };
    }

    private String getFieldValueFromJson(JsonNode node, String field) {
        return Optional.ofNullable(node.get(field))
            .filter(n -> !n.isNull())
            .map(JsonNode::asText)
            .orElseThrow(() -> new IllegalArgumentException("'" + field + "' is required in availability rule"));
    }

    private String getDayOfWeek(JsonNode node) {
        return Optional.ofNullable(node.get("dayOfWeek"))
            .filter(n -> !n.isNull())
            .map(JsonNode::asText)
            .orElseThrow(() -> new IllegalArgumentException("'dayOfWeek' is required in weekly availability rule"));
    }
}
