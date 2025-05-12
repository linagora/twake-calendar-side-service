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

import static com.linagora.calendar.restapi.routes.SecretLinkRoute.CALENDAR_HOME_ID_PARAM;
import static com.linagora.calendar.restapi.routes.SecretLinkRoute.CALENDAR_ID_PARAM;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.jmap.routes.ForbiddenException;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.auth.SimpleSessionProvider;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.secretlink.SecretLinkStore;
import com.linagora.calendar.storage.secretlink.SecretLinkToken;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class DownloadCalendarRoute implements JMAPRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadCalendarRoute.class);

    private static final String TOKEN_PARAM = "token";
    private static final String CONTENT_TYPE_ICS = "text/calendar; charset=utf-8";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final String CONTENT_DISPOSITION = "attachment; filename=calendar.ics";
    private static final String SAFE_PATTERN = "^[a-zA-Z0-9_-]+$";

    private final MetricFactory metricFactory;
    private final SecretLinkStore secretLinkStore;
    private final CalDavClient calDavClient;
    private final SimpleSessionProvider sessionProvider;

    @Inject
    public DownloadCalendarRoute(MetricFactory metricFactory,
                                 SecretLinkStore secretLinkStore,
                                 CalDavClient calDavClient, SimpleSessionProvider sessionProvider) {
        this.metricFactory = metricFactory;
        this.secretLinkStore = secretLinkStore;
        this.calDavClient = calDavClient;
        this.sessionProvider = sessionProvider;
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/calendars/{" + CALENDAR_HOME_ID_PARAM + "}/{" + CALENDAR_ID_PARAM + "}/calendar.ics");
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(
            JMAPRoute.builder()
                .endpoint(endpoint())
                .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), generateCalendarData(req, res))))
                .corsHeaders());
    }

    Mono<Void> generateCalendarData(HttpServerRequest request, HttpServerResponse response) {
        CalendarURL calendarURL = extractCalendarURL(request);
        return Mono.defer(() -> Mono.justOrEmpty(extractToken(request)))
            .flatMap(token -> secretLinkStore.checkSecretLink(calendarURL, token))
            .map(sessionProvider::createSession)
            .switchIfEmpty(Mono.error(new ForbiddenException()))
            .flatMap(mailboxSession -> downloadCalendar(mailboxSession, calendarURL, response))
            .onErrorResume(Exception.class, exception -> {
                if (exception instanceof IllegalArgumentException illegalArgumentException) {
                    return response.status(BAD_REQUEST)
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(illegalArgumentException).serializeAsBytes()))
                        .then();
                }

                if (exception instanceof ForbiddenException) {
                    LOGGER.warn("Forbidden access attempt: {}", exception.getMessage());
                    return doOnForbidden(response);
                }
                return doOnError(response);
            });
    }

    Mono<Void> downloadCalendar(MailboxSession session, CalendarURL calendarURL, HttpServerResponse response) {
        return calDavClient.export(calendarURL, session)
            .flatMap(data -> response.status(HttpResponseStatus.OK)
                .header("Content-Type", CONTENT_TYPE_ICS)
                .header("Content-Disposition", CONTENT_DISPOSITION)
                .sendByteArray(Mono.just(data))
                .then());
    }

    Optional<SecretLinkToken> extractToken(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(TOKEN_PARAM, List.of())
            .stream()
            .map(tokenValue -> {
                Preconditions.checkArgument(StringUtils.trim(tokenValue).matches(SAFE_PATTERN), "Invalid token: only letters, digits, hyphen, and underscore are allowed.");
                return new SecretLinkToken(tokenValue);
            })
            .findAny();
    }

    CalendarURL extractCalendarURL(HttpServerRequest request) {
        OpenPaaSId calendarHomeId = new OpenPaaSId(request.param(CALENDAR_HOME_ID_PARAM));
        OpenPaaSId calendarId = new OpenPaaSId(request.param(CALENDAR_ID_PARAM));
        return new CalendarURL(calendarHomeId, calendarId);
    }

    Mono<Void> doOnForbidden(HttpServerResponse response) {
        return response.status(HttpResponseStatus.FORBIDDEN)
            .header("Content-Type", CONTENT_TYPE_JSON)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(403, "Forbidden", "Forbidden").serializeAsBytes()))
            .then();
    }

    Mono<Void> doOnError(HttpServerResponse response) {
        return response.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
            .header("Content-Type", CONTENT_TYPE_JSON)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(503, "Service Unavailable", "Service Unavailable").serializeAsBytes()))
            .then();
    }
}
