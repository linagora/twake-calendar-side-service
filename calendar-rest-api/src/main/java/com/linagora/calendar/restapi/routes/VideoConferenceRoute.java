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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.amqp.meet.MeetApplicationClient;
import com.linagora.calendar.amqp.meet.MeetConfiguration;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Mint a video conference room for the authenticated user and return its URL.
 *
 * <p>The frontend used to invent the room code itself
 * ({@code generateMeetingId}, three then four then three random letters) and
 * write it straight into {@code X-OPENPAAS-VIDEOCONFERENCE}. Nothing ever told
 * Meet about it, so on any deployment that does not accept unregistered rooms
 * every generated link was dead — for every client, web included.
 *
 * <p>Asking Meet for the room fixes that at the source and buys two things a
 * pre-minted code could never give: the room exists before the invitation is
 * sent, and the organiser is its {@code OWNER}, so lobby admission, recording
 * and the rest of the host controls actually work.
 *
 * <p>The Meet application credentials stay server-side, which is why this is a
 * route here rather than a call from the browser.
 */
public class VideoConferenceRoute extends CalendarRoute {
    private static final Logger LOGGER = LoggerFactory.getLogger(VideoConferenceRoute.class);
    private static final String URL_FIELD = "url";

    private final MeetConfiguration meetConfiguration;
    private final MeetApplicationClient meetClient;

    @Inject
    public VideoConferenceRoute(Authenticator authenticator,
                                MetricFactory metricFactory,
                                MeetConfiguration meetConfiguration,
                                MeetApplicationClient meetClient) {
        super(authenticator, metricFactory);
        this.meetConfiguration = meetConfiguration;
        this.meetClient = meetClient;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/videoconference");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        // Deployments without Meet credentials answer 404 rather than 500, so
        // the caller can tell "this instance does not mint rooms" from "minting
        // failed" — and fall back to its own link generation, which is what
        // Meet expects when it runs with ALLOW_UNREGISTERED_ROOMS enabled.
        if (!meetConfiguration.enabled()) {
            return response.status(HttpResponseStatus.NOT_FOUND).send().then();
        }

        String organizer = session.getUser().asString();

        return meetClient.fetchApplicationToken(organizer)
            .flatMap(meetClient::createRoom)
            .flatMap(url -> response.status(HttpResponseStatus.CREATED)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(Map.of(URL_FIELD, url))))
                .then())
            // Meet being down is not the caller's fault, and it is not this
            // service failing either: 502 says which side to look at.
            .onErrorResume(MeetApplicationClient.MeetApiException.class, error -> {
                LOGGER.warn("Meet refused to create a room for {}", organizer, error);
                return response.status(HttpResponseStatus.BAD_GATEWAY).send().then();
            });
    }
}
