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
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;
import com.linagora.calendar.storage.eventtoken.EventTokenId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventTokenGetRoute extends PublicRoute {

    static final String EVENT_TOKEN_PARAM = "eventToken";

    private final EventTokenDAO eventTokenDAO;

    @Inject
    public EventTokenGetRoute(MetricFactory metricFactory, EventTokenDAO eventTokenDAO) {
        super(metricFactory);
        this.eventTokenDAO = eventTokenDAO;
    }

    @Override
    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/eventTokens/{" + EVENT_TOKEN_PARAM + "}");
    }

    @Override
    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return Mono.fromCallable(() -> new EventTokenId(request.param(EVENT_TOKEN_PARAM)))
            .flatMap(eventTokenDAO::findById)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(eventToken -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(Map.of("right", eventToken.right().name()))))
                .then());
    }
}
