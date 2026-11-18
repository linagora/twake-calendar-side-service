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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;

import org.apache.commons.lang3.Strings;
import org.apache.http.client.utils.URIBuilder;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.secretlink.SecretLinkPermissionException;
import com.linagora.calendar.storage.secretlink.SecretLinkStore;
import com.linagora.calendar.storage.secretlink.SecretLinkToken;

import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public abstract class AbstractSecretLinkRoute extends CalendarRoute {

    public static final String SECRET_LINK_PATH = "/calendar/api/calendars/{calendarHomeId}/{calendarId}/secret-link";
    public static final String CALENDAR_HOME_ID_PARAM = "calendarHomeId";
    public static final String CALENDAR_ID_PARAM = "calendarId";

    private final ObjectMapper objectMapper;
    private final URL secretLinkBaseUrl;
    protected final SecretLinkStore secretLinkStore;

    protected AbstractSecretLinkRoute(Authenticator authenticator,
                                      MetricFactory metricFactory,
                                      SecretLinkStore secretLinkStore,
                                      RestApiConfiguration configuration) {
        super(authenticator, metricFactory);
        this.objectMapper = new ObjectMapper();
        this.secretLinkStore = secretLinkStore;
        this.secretLinkBaseUrl = configuration.getSelfUrl();
    }

    abstract Mono<SecretLinkToken> secretLinkToken(HttpServerRequest request, CalendarURL calendarURL, MailboxSession session);

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        CalendarURL calendarURL = extractCalendarURL(request);

        return secretLinkToken(request, calendarURL, session)
            .onErrorMap(SecretLinkPermissionException.class, e -> new ForbiddenException(e.getMessage()))
            .flatMap(token -> response.status(HttpResponseStatus.OK)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(buildResponseBody(token, calendarURL))
                .then());
    }

    private CalendarURL extractCalendarURL(HttpServerRequest request) {
        return new CalendarURL(new OpenPaaSId(request.param(CALENDAR_HOME_ID_PARAM)),
            new OpenPaaSId(request.param(CALENDAR_ID_PARAM)));
    }

    private URI buildSecretLinkURL(SecretLinkToken token, CalendarURL calendarURL) throws URISyntaxException {
        return new URIBuilder(secretLinkBaseUrl.toURI())
            .setPath(Strings.CS.removeEnd(secretLinkBaseUrl.getPath(), "/") + "/api" + calendarURL.asUri() + "/calendar.ics")
            .addParameter("token", token.value())
            .build();
    }

    private Mono<byte[]> buildResponseBody(SecretLinkToken token, CalendarURL calendarURL) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(Collections.singletonMap("secretLink", buildSecretLinkURL(token, calendarURL).toString())));
    }
}
