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

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * The authenticated user attends the event: their identity being known, they are added as having accepted.
 */
public class EventTokenAddMyselfRoute extends CalendarRoute {

    private final EventTokenAttendees eventTokenAttendees;
    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public EventTokenAddMyselfRoute(Authenticator authenticator,
                                    MetricFactory metricFactory,
                                    EventTokenAttendees eventTokenAttendees,
                                    OpenPaaSUserDAO openPaaSUserDAO) {
        super(authenticator, metricFactory);
        this.eventTokenAttendees = eventTokenAttendees;
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/events/addMyself");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return Mono.fromCallable(() -> EventTokenOwnership.eventTokenFromQuery(request))
            .flatMap(eventTokenId -> displayName(session)
                .flatMap(displayName -> eventTokenAttendees.add(eventTokenId, mailAddress(session.getUser()), displayName, PartStat.ACCEPTED)))
            .then(response.status(HttpResponseStatus.NO_CONTENT).send());
    }

    private static MailAddress mailAddress(Username username) {
        try {
            return username.asMailAddress();
        } catch (AddressException e) {
            throw new IllegalArgumentException("'" + username.asString() + "' is not a mail address", e);
        }
    }

    private Mono<Optional<String>> displayName(MailboxSession session) {
        return openPaaSUserDAO.retrieve(session.getUser())
            .map(OpenPaaSUser::fullName)
            .filter(StringUtils::isNotBlank)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }
}
