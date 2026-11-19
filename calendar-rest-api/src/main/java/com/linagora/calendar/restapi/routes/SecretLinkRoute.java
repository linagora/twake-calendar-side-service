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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.secretlink.SecretLinkStore;
import com.linagora.calendar.storage.secretlink.SecretLinkToken;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

public class SecretLinkRoute extends AbstractSecretLinkRoute {

    public static final boolean SHOULD_RESET_LINK_DEFAULT = false;
    /**
     * @deprecated resetting the secret link is a state change: use {@link SecretLinkResetRoute} (POST) instead.
     * Kept until clients stop calling GET with this parameter.
     */
    @Deprecated
    public static final String SHOULD_RESET_LINK_PARAM = "shouldResetLink";

    @Inject
    public SecretLinkRoute(Authenticator authenticator,
                           MetricFactory metricFactory,
                           SecretLinkStore secretLinkStore,
                           RestApiConfiguration configuration) {
        super(authenticator, metricFactory, secretLinkStore, configuration);
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, SECRET_LINK_PATH);
    }

    @Override
    Mono<SecretLinkToken> secretLinkToken(HttpServerRequest request, CalendarURL calendarURL, MailboxSession session) {
        if (extractShouldResetLink(request)) {
            return secretLinkStore.generateSecretLink(calendarURL, session);
        }
        return secretLinkStore.getSecretLink(calendarURL, session);
    }

    private boolean extractShouldResetLink(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters().getOrDefault(SHOULD_RESET_LINK_PARAM, List.of())
            .stream()
            .findAny()
            .map("true"::equalsIgnoreCase)
            .orElse(SHOULD_RESET_LINK_DEFAULT);
    }
}
