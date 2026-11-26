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

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.DavPathSegment;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.eventtoken.EventToken;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;
import com.linagora.calendar.storage.eventtoken.EventTokenId;
import com.linagora.calendar.storage.eventtoken.EventTokenRight;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventTokenCreateRoute extends CalendarRoute {

    public record CreateEventTokenRequestDTO(@JsonProperty("right") String right,
                                             @JsonProperty("expirationInDays") Optional<Integer> expirationInDays) {

        CreationRequest toCreationRequest() {
            Preconditions.checkArgument(right != null, "'right' is required");
            Optional<Integer> days = Optional.ofNullable(expirationInDays).flatMap(value -> value);
            days.ifPresent(value -> Preconditions.checkArgument(value > 0, "'expirationInDays' must be positive"));
            return new CreationRequest(EventTokenRight.fromString(right), days.map(Duration::ofDays).orElse(DEFAULT_VALIDITY));
        }
    }

    record CreationRequest(EventTokenRight right, Duration validity) {
    }

    public static final Duration DEFAULT_VALIDITY = Duration.ofDays(30);

    static final String CALENDAR_HOME_ID_PARAM = "calendarHomeId";
    static final String CALENDAR_ID_PARAM = "calendarId";
    static final String EVENT_ID_PARAM = "eventId";
    static final String EVENT_TOKENS_PATH = "/api/events/{" + CALENDAR_HOME_ID_PARAM + "}/{" + CALENDAR_ID_PARAM + "}/{" + EVENT_ID_PARAM + "}/eventTokens";

    private final EventTokenDAO eventTokenDAO;
    private final EventTokenOwnership ownership;
    private final CalDavClient calDavClient;
    private final Clock clock;

    @Inject
    public EventTokenCreateRoute(Authenticator authenticator,
                                 MetricFactory metricFactory,
                                 EventTokenDAO eventTokenDAO,
                                 EventTokenOwnership ownership,
                                 CalDavClient calDavClient,
                                 Clock clock) {
        super(authenticator, metricFactory);
        this.eventTokenDAO = eventTokenDAO;
        this.ownership = ownership;
        this.calDavClient = calDavClient;
        this.clock = clock;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, EVENT_TOKENS_PATH);
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        CalendarURL calendarURL = calendarURL(request);
        String eventId = eventId(request);

        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Request body must not be empty")))
            .map(this::parseRequest)
            .flatMap(creationRequest -> ownership.assertCalendarHomeOwner(session, calendarURL)
                .flatMap(owner -> assertOrganizedEventExists(owner, EventTokenOwnership.eventHref(calendarURL, eventId)))
                .thenReturn(EventToken.builder()
                    .id(EventTokenId.generate())
                    .calendarURL(calendarURL)
                    .eventId(eventId)
                    .right(creationRequest.right())
                    .expiration(clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(creationRequest.validity()))
                    .build()))
            .flatMap(eventToken -> eventTokenDAO.insert(eventToken).thenReturn(eventToken))
            .flatMap(eventToken -> response.status(HttpResponseStatus.CREATED)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(Map.of(
                    "eventToken", eventToken.id().value(),
                    "expiration", eventToken.expiration().toString()))))
                .then());
    }

    /**
     * Only a meeting the owner organizes can be shared: on another copy, the DAV server would not schedule the attendees
     * added through the token.
     */
    private Mono<Void> assertOrganizedEventExists(OpenPaaSUser owner, URI eventHref) {
        return calDavClient.fetchCalendarEvent(owner.username(), eventHref)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .filter(event -> EventTokenOwnership.isOrganizedBy(event.calendarData(), owner))
            .switchIfEmpty(Mono.error(() -> new ForbiddenException("Only a meeting organized by '%s' can be shared"
                .formatted(owner.username().asString()))))
            .then();
    }

    static CalendarURL calendarURL(HttpServerRequest request) {
        return new CalendarURL(new OpenPaaSId(request.param(CALENDAR_HOME_ID_PARAM)), new OpenPaaSId(request.param(CALENDAR_ID_PARAM)));
    }

    static String eventId(HttpServerRequest request) {
        return DavPathSegment.validate(request.param(EVENT_ID_PARAM), EVENT_ID_PARAM);
    }

    private CreationRequest parseRequest(byte[] body) {
        CreateEventTokenRequestDTO dto;
        try {
            dto = OBJECT_MAPPER_DEFAULT.readValue(body, CreateEventTokenRequestDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
        Preconditions.checkArgument(dto != null, "Invalid request body");
        return dto.toCreationRequest();
    }
}
