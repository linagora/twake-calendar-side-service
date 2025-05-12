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
import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.restapi.RestApiConfiguration;
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

public class SecretLinkRoute extends CalendarRoute {

    public static final boolean SHOULD_RESET_LINK_DEFAULT = false;
    public static final String CALENDAR_HOME_ID_PARAM = "calendarHomeId";
    public static final String CALENDAR_ID_PARAM = "calendarId";
    public static final String SHOULD_RESET_LINK_PARAM = "shouldResetLink";

    private final ObjectMapper objectMapper;

    private final SecretLinkStore secretLinkStore;
    private final URL secretLinkBaseUrl;

    @Inject
    public SecretLinkRoute(Authenticator authenticator,
                           MetricFactory metricFactory,
                           SecretLinkStore secretLinkStore,
                           RestApiConfiguration secretLinkBaseUrl) {
        super(authenticator, metricFactory);
        this.objectMapper = new ObjectMapper();
        this.secretLinkStore = secretLinkStore;
        this.secretLinkBaseUrl = secretLinkBaseUrl.getSelfUrl();
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/calendars/{calendarHomeId}/{calendarId}/secret-link");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        OpenPaaSId calendarHomeId = new OpenPaaSId(request.param(CALENDAR_HOME_ID_PARAM));
        OpenPaaSId calendarId = new OpenPaaSId(request.param(CALENDAR_ID_PARAM));
        CalendarURL calendarURL = new CalendarURL(calendarHomeId, calendarId);
        boolean shouldResetLink = extractShouldResetLink(request);

        return fetchOrGenerateSecretLink(shouldResetLink, calendarURL, session)
            .flatMap(token -> response.status(HttpResponseStatus.OK)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(buildResponseBody(token, calendarURL))
                .then());
    }

    private Mono<SecretLinkToken> fetchOrGenerateSecretLink(boolean shouldResetLink, CalendarURL calendarURL, MailboxSession session) {
        if (shouldResetLink) {
            return secretLinkStore.generateSecretLink(calendarURL, session);
        } else {
            return secretLinkStore.getSecretLink(calendarURL, session);
        }
    }

    private boolean extractShouldResetLink(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(SHOULD_RESET_LINK_PARAM, List.of())
            .stream()
            .findAny()
            .map("true"::equalsIgnoreCase)
            .orElse(SHOULD_RESET_LINK_DEFAULT);
    }

    private URI buildSecretLinkURL(SecretLinkToken token, CalendarURL calendarURL) throws URISyntaxException {
        return new URIBuilder(secretLinkBaseUrl.toURI())
            .setPath(StringUtils.removeEnd(secretLinkBaseUrl.getPath(), "/") + "/api" + calendarURL.asUri() + "/calendar.ics")
            .addParameter("token", token.value())
            .build();
    }

    private Mono<byte[]> buildResponseBody(SecretLinkToken token, CalendarURL calendarURL) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(Collections.singletonMap("secretLink", buildSecretLinkURL(token, calendarURL).toString())));
    }
}