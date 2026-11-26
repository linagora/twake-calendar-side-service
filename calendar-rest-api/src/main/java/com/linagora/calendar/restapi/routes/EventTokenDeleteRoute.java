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

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;
import com.linagora.calendar.storage.eventtoken.EventTokenId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class EventTokenDeleteRoute extends CalendarRoute {

    private final EventTokenDAO eventTokenDAO;
    private final EventTokenOwnership ownership;

    @Inject
    public EventTokenDeleteRoute(Authenticator authenticator,
                                 MetricFactory metricFactory,
                                 EventTokenDAO eventTokenDAO,
                                 EventTokenOwnership ownership) {
        super(authenticator, metricFactory);
        this.eventTokenDAO = eventTokenDAO;
        this.ownership = ownership;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.DELETE, "/api/eventTokens/{" + EventTokenOwnership.EVENT_TOKEN_PARAM + "}");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        EventTokenId id = new EventTokenId(request.param(EventTokenOwnership.EVENT_TOKEN_PARAM));

        return eventTokenDAO.findById(id)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .flatMap(eventToken -> ownership.assertCalendarHomeOwner(session, eventToken.calendarURL()))
            .then(eventTokenDAO.delete(id))
            .then(response.status(HttpResponseStatus.NO_CONTENT).send());
    }
}
