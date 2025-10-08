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

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarNotFoundException;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.SimpleSessionProvider;
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
    private static final CharMatcher SAFE_TOKEN_MATCHER = CharMatcher.inRange('a', 'z')
        .or(CharMatcher.inRange('A', 'Z'))
        .or(CharMatcher.inRange('0', '9'))
        .or(CharMatcher.anyOf("_-"));

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
        CalendarURL requestCalendarURL = extractCalendarURL(request);
        return Mono.defer(() -> Mono.justOrEmpty(extractToken(request)))
            .flatMap(token -> secretLinkStore.checkSecretLink(requestCalendarURL, token))
            .map(sessionProvider::createSession)
            .switchIfEmpty(Mono.error(new ForbiddenException("Token validation failed")))
            .flatMap(mailboxSession -> downloadCalendar(mailboxSession, requestCalendarURL, response))
            .onErrorResume(exception -> switch (exception) {
                case IllegalArgumentException illegalArgumentException -> {
                    LOGGER.warn("Bad request for [{}]: {}", request.uri(), illegalArgumentException.getMessage());
                    yield ErrorResponseHandler.handle(response, HttpResponseStatus.BAD_REQUEST, illegalArgumentException);
                }
                case ForbiddenException forbidden -> {
                    LOGGER.warn("Forbidden for [{}]: {}", request.uri(), forbidden.getMessage());
                    yield ErrorResponseHandler.handle(response, HttpResponseStatus.FORBIDDEN, forbidden);
                }
                case CalendarNotFoundException notfound -> {
                    LOGGER.warn("Not found for [{}]", request.uri());
                    yield ErrorResponseHandler.handle(response, HttpResponseStatus.NOT_FOUND, notfound);
                }
                default -> {
                    LOGGER.error("Unexpected error for [{}]", request.uri(), exception);
                    yield doOnError(response);
                }
            });
    }

    Mono<Void> downloadCalendar(MailboxSession session, CalendarURL calendarURL, HttpServerResponse response) {
        return resolveDownloadCalendarURI(session.getUser(), calendarURL)
            .flatMap(exportUri -> calDavClient.export(session.getUser(), exportUri))
            .flatMap(data -> response.status(HttpResponseStatus.OK)
                .header("Content-Type", CONTENT_TYPE_ICS)
                .header("Content-Disposition", CONTENT_DISPOSITION)
                .sendByteArray(Mono.just(data))
                .then());
    }

    Mono<URI> resolveDownloadCalendarURI(Username username, CalendarURL requestCalendarURL) {
        return calDavClient.fetchCalendarMetadata(username, requestCalendarURL)
            .map(node -> {
                if (!isSubscribedCalendar(node)) {
                    return requestCalendarURL.asUri();
                }
                String href = extractSourceHref(node);
                Preconditions.checkArgument(StringUtils.isNotBlank(href),
                    "Missing source href for subscribed calendar: " + node.toPrettyString());
                return URI.create(href);
            });
    }

    private boolean isSubscribedCalendar(JsonNode node) {
        return node.hasNonNull("calendarserver:source");
    }

    private String extractSourceHref(JsonNode node) {
        return node.path("calendarserver:source")
            .path("_links")
            .path("self")
            .path("href")
            .asText(null);
    }

    Optional<SecretLinkToken> extractToken(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(TOKEN_PARAM, List.of())
            .stream()
            .map(tokenValue -> {
                Preconditions.checkArgument(SAFE_TOKEN_MATCHER.matchesAllOf(StringUtils.trim(tokenValue)),
                    "Invalid token: only letters, digits, hyphen, and underscore are allowed.");
                return new SecretLinkToken(tokenValue);
            })
            .findAny();
    }

    CalendarURL extractCalendarURL(HttpServerRequest request) {
        OpenPaaSId calendarHomeId = new OpenPaaSId(request.param(CALENDAR_HOME_ID_PARAM));
        OpenPaaSId calendarId = new OpenPaaSId(request.param(CALENDAR_ID_PARAM));
        return new CalendarURL(calendarHomeId, calendarId);
    }

    Mono<Void> doOnError(HttpServerResponse response) {
        return response.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
            .header("Content-Type", CONTENT_TYPE_JSON)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(503, "Service Unavailable", "Service Unavailable").serializeAsBytes()))
            .then();
    }
}
