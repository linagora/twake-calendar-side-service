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

import static org.apache.james.jmap.http.JWTAuthenticationStrategy.AUTHORIZATION_HEADER_PREFIX;

import java.time.Clock;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.apache.james.jmap.http.AuthenticationChallenge;
import org.apache.james.jmap.http.AuthenticationScheme;
import org.apache.james.jmap.http.AuthenticationStrategy;
import org.apache.james.jwt.introspection.TokenIntrospectionException;
import org.apache.james.jwt.userinfo.UserInfoCheckException;
import org.apache.james.mailbox.MailboxSession;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.OIDCTokenCache;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.model.Aud;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

public class OidcAuthenticationStrategy implements AuthenticationStrategy {

    private final SimpleSessionProvider sessionProvider;
    private final OIDCTokenCache oidcTokenCache;
    private final Clock clock;
    private final Aud aud;

    @Inject
    public OidcAuthenticationStrategy(SimpleSessionProvider sessionProvider, OIDCTokenCache oidcTokenCache, Clock clock, Aud aud) {
        this.sessionProvider = sessionProvider;
        this.oidcTokenCache = oidcTokenCache;
        this.clock = clock;
        this.aud = aud;
    }

    @Override
    public Mono<MailboxSession> createMailboxSession(HttpServerRequest httpRequest) {
        return Mono.fromCallable(() -> authHeaders(httpRequest))
            .filter(header -> header.startsWith(AUTHORIZATION_HEADER_PREFIX))
            .map(header -> header.substring(AUTHORIZATION_HEADER_PREFIX.length()))
            .filter(token -> !token.startsWith("eyJ")) // Heuristic for detecting JWT
            .map(Token::new)
            .flatMap(oidcTokenCache::associatedInformation)
            .<TokenInfo>handle((tokenInfo, sink) -> {
                if (!tokenInfo.aud().contains(aud)) {
                    sink.error(new UnauthorizedException("Wrong audience. Expected " + aud.value() + " got " + tokenInfo.aud()));
                    return;
                }
                if (clock.instant().isAfter(tokenInfo.exp())) {
                    sink.error(new UnauthorizedException("Expired token"));
                    return;
                }

                sink.next(tokenInfo);
            })
            .map(tokenInfo -> Username.of(tokenInfo.email()))
            .map(Throwing.function(sessionProvider::createSession))
            .onErrorResume(this::handleOidcError);
    }

    private Mono<MailboxSession> handleOidcError(Throwable error) {
        if (error instanceof TokenIntrospectionException || error instanceof UserInfoCheckException) {
            return Mono.error(new UnauthorizedException("Invalid OIDC token", error));
        }
        return Mono.error(error);
    }

    @Override
    public AuthenticationChallenge correspondingChallenge() {
        return AuthenticationChallenge.of(
            AuthenticationScheme.of("Bearer"),
            ImmutableMap.of("realm", "twake_calendar",
                "error", "invalid_token",
                "scope", "openid profile email"));
    }
}
