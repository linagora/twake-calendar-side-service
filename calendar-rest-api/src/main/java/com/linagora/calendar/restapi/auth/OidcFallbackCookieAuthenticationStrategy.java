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

package com.linagora.calendar.restapi.auth;

import org.apache.james.jmap.http.AuthenticationChallenge;
import org.apache.james.jmap.http.AuthenticationScheme;
import org.apache.james.jmap.http.AuthenticationStrategy;
import org.apache.james.mailbox.MailboxSession;

import com.google.common.collect.ImmutableMap;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

public class OidcFallbackCookieAuthenticationStrategy implements AuthenticationStrategy {

    private final OidcAuthenticationStrategy oidcAuthenticationStrategy;
    private final LemonCookieAuthenticationStrategy lemonCookieAuthenticationStrategy;

    public OidcFallbackCookieAuthenticationStrategy(OidcAuthenticationStrategy oidcAuthenticationStrategy, LemonCookieAuthenticationStrategy lemonCookieAuthenticationStrategy) {
        this.oidcAuthenticationStrategy = oidcAuthenticationStrategy;
        this.lemonCookieAuthenticationStrategy = lemonCookieAuthenticationStrategy;
    }

    @Override
    public Mono<MailboxSession> createMailboxSession(HttpServerRequest httpRequest) {
        return oidcAuthenticationStrategy.createMailboxSession(httpRequest)
            .switchIfEmpty(Mono.defer(() -> lemonCookieAuthenticationStrategy.createMailboxSession(httpRequest)));
    }

    @Override
    public AuthenticationChallenge correspondingChallenge() {
        return AuthenticationChallenge.of(
            AuthenticationScheme.of("BearerFallBackCookie"),
            ImmutableMap.of("realm", "twake_calendar",
                "error", "invalid_token",
                "scope", "openid profile email"));
    }
}
