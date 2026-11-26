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
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.dto.VCalendarDto;
import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.eventtoken.EventToken;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;
import com.linagora.calendar.storage.eventtoken.EventTokenId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Returns the event an event token points to, read on behalf of the owner of its calendar home.
 */
public class EventTokenEventGetRoute extends PublicRoute {

    private final EventTokenDAO eventTokenDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;

    @Inject
    public EventTokenEventGetRoute(MetricFactory metricFactory,
                                   EventTokenDAO eventTokenDAO,
                                   OpenPaaSUserDAO openPaaSUserDAO,
                                   CalDavClient calDavClient) {
        super(metricFactory);
        this.eventTokenDAO = eventTokenDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
    }

    @Override
    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/events");
    }

    @Override
    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return Mono.fromCallable(() -> extractToken(request))
            .flatMap(eventTokenDAO::findById)
            .flatMap(this::fetchEvent)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(event -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(Map.of("eventJSON", event.value()))))
                .then());
    }

    private Mono<VCalendarDto> fetchEvent(EventToken eventToken) {
        return openPaaSUserDAO.retrieve(eventToken.calendarURL().base())
            .flatMap(owner -> calDavClient.fetchCalendarEventAsJCal(owner.username(),
                EventTokenOwnership.eventHref(eventToken.calendarURL(), eventToken.eventId())))
            .map(VCalendarDto::new)
            .filter(event -> !event.isCancelled());
    }

    private EventTokenId extractToken(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters()
            .getOrDefault(EventTokenGetRoute.EVENT_TOKEN_PARAM, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny()
            .map(EventTokenId::new)
            .orElseThrow(() -> new IllegalArgumentException("Missing '" + EventTokenGetRoute.EVENT_TOKEN_PARAM + "' query parameter"));
    }
}
