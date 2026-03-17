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

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.restapi.routes.dto.AvailabilityRuleDTO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkGetRoute extends CalendarRoute {

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record BookingLinkDTO(@JsonProperty("publicId") String publicId,
                                 @JsonProperty("calendarUrl") String calendarUrl,
                                 @JsonProperty("durationMinutes") long durationMinutes,
                                 @JsonProperty("active") boolean active,
                                 @JsonProperty("timeZone") Optional<String> timeZone,
                                 @JsonProperty("availabilityRules") Optional<List<AvailabilityRuleDTO>> availabilityRules) {

        public static final String UTC = "UTC";

        public static BookingLinkDTO from(BookingLink bookingLink) {
            Optional<List<AvailabilityRuleDTO>> ruleDTOs = bookingLink.availabilityRules()
                .map(rules -> rules.values().stream()
                    .map(AvailabilityRuleDTO::from)
                    .toList());

            Optional<String> timeZone = bookingLink.availabilityRules()
                .flatMap(rules -> rules.values().stream().findFirst())
                .map(BookingLinkDTO::extractTimeZone);

            return new BookingLinkDTO(
                bookingLink.publicId().value().toString(),
                bookingLink.calendarUrl().asUri().toString(),
                bookingLink.duration().toMinutes(),
                bookingLink.active(),
                timeZone,
                ruleDTOs);
        }

        private static String extractTimeZone(AvailabilityRule rule) {
            return switch (rule) {
                case WeeklyAvailabilityRule weekly -> weekly.timeZone().map(ZoneId::getId).orElse(UTC);
                case FixedAvailabilityRule fixed -> fixed.start().getZone().getId();
            };
        }
    }

    private static final String PUBLIC_ID_PARAM = "bookingLinkPublicId";

    private final BookingLinkDAO bookingLinkDAO;

    @Inject
    public BookingLinkGetRoute(Authenticator authenticator,
                               MetricFactory metricFactory,
                               BookingLinkDAO bookingLinkDAO) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/booking-links/{" + PUBLIC_ID_PARAM + "}");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        BookingLinkPublicId publicId = new BookingLinkPublicId(UUID.fromString(request.param(PUBLIC_ID_PARAM)));

        return bookingLinkDAO.findByPublicId(session.getUser(), publicId)
            .switchIfEmpty(Mono.error(new BookingLinkNotFoundException(publicId)))
            .flatMap(bookingLink -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() ->
                    OBJECT_MAPPER_DEFAULT.writeValueAsBytes(BookingLinkDTO.from(bookingLink))))
                .then())
            .onErrorResume(BookingLinkNotFoundException.class, e ->
                response.status(HttpResponseStatus.NOT_FOUND).send().then());
    }
}
