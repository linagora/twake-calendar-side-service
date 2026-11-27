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

import java.util.Map;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventTokenListRoute extends CalendarRoute {

    private final EventTokenDAO eventTokenDAO;
    private final EventTokenOwnership ownership;

    @Inject
    public EventTokenListRoute(Authenticator authenticator,
                               MetricFactory metricFactory,
                               EventTokenDAO eventTokenDAO,
                               EventTokenOwnership ownership) {
        super(authenticator, metricFactory);
        this.eventTokenDAO = eventTokenDAO;
        this.ownership = ownership;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, EventTokenCreateRoute.EVENT_TOKENS_PATH);
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        CalendarURL calendarURL = EventTokenCreateRoute.calendarURL(request);
        String eventId = EventTokenCreateRoute.eventId(request);

        return ownership.assertCalendarHomeOwner(session, calendarURL)
            .thenMany(eventTokenDAO.listByEvent(calendarURL, eventId))
            .map(eventToken -> Map.of(
                "eventToken", eventToken.id().asString(),
                "right", eventToken.right().name(),
                "expiration", eventToken.expiration().toString()))
            .collectList()
            .flatMap(eventTokens -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(eventTokens)))
                .then());
    }
}
