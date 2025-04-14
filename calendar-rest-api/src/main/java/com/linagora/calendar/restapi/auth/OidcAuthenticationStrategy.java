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

import java.net.URL;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.apache.james.jmap.http.AuthenticationChallenge;
import org.apache.james.jmap.http.AuthenticationScheme;
import org.apache.james.jmap.http.AuthenticationStrategy;
import org.apache.james.jwt.DefaultCheckTokenClient;
import org.apache.james.jwt.userinfo.UserInfoCheckException;
import org.apache.james.jwt.userinfo.UserinfoResponse;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.ibm.icu.impl.Pair;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

public class OidcAuthenticationStrategy implements AuthenticationStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(OidcAuthenticationStrategy.class);
    private static final String FIRSTNAME_PROPERTY = "given_name";
    private static final String SURNAME_PROPERTY = "family_name";

    private final DefaultCheckTokenClient checkTokenClient;
    private final SimpleSessionProvider sessionProvider;
    private final MetricFactory metricFactory;
    private final URL userInfoURL;
    private final RestApiConfiguration configuration;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    public OidcAuthenticationStrategy(SimpleSessionProvider sessionProvider, RestApiConfiguration configuration,
                                      @Named("userInfo") URL userInfoURL, MetricFactory metricFactory,
                                      OpenPaaSUserDAO userDAO) {
        this.sessionProvider = sessionProvider;
        this.metricFactory = metricFactory;
        this.checkTokenClient = new DefaultCheckTokenClient();
        this.configuration = configuration;
        this.userInfoURL = userInfoURL;
        this.userDAO = userDAO;
    }

    @Override
    public Mono<MailboxSession> createMailboxSession(HttpServerRequest httpRequest) {
        return Mono.fromCallable(() -> authHeaders(httpRequest))
            .filter(header -> header.startsWith(AUTHORIZATION_HEADER_PREFIX))
            .map(header -> header.substring(AUTHORIZATION_HEADER_PREFIX.length()))
            .filter(token -> !token.startsWith("eyJ")) // Heuristic for detecting JWT
            .flatMap(this::correspondingUsername)
            .map(Throwing.function(sessionProvider::createSession))
            .onErrorResume(UserInfoCheckException.class, e -> Mono.error(new UnauthorizedException("Invalid OIDC token: userinfo failed", e)));
    }

    private Mono<Username> correspondingUsername(String token) {
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric("userinfo-lookup",
                checkTokenClient.userInfo(userInfoURL, token)))
            .flatMap(userInfoResponse -> Mono.justOrEmpty(userInfoResponse.claimByPropertyName(configuration.getOidcClaim()))
                .switchIfEmpty(Mono.error(new UnauthorizedException("Invalid OIDC token: userinfo needs to include " + configuration.getOidcClaim() + " claim")))
                .flatMap(username -> provisionUserIfNeed(Username.of(username), parseFirstnameAndSurnameFromToken(userInfoResponse))));
    }

    @Override
    public AuthenticationChallenge correspondingChallenge() {
        return AuthenticationChallenge.of(
            AuthenticationScheme.of("Bearer"),
            ImmutableMap.of("realm", "twake_calendar",
                "error", "invalid_token",
                "scope", "openid profile email"));
    }

    private Mono<Username> provisionUserIfNeed(Username username, Optional<Pair<String, String>> firstnameAndSurnameOpt) {
        Mono<OpenPaaSUser> createPublisher = Mono.justOrEmpty(firstnameAndSurnameOpt)
            .flatMap(name -> userDAO.add(username, name.first, name.second))
            .switchIfEmpty(Mono.defer(() -> userDAO.add(username)))
            .doOnNext(u -> LOGGER.info("Created user: {}", username.asString()));

        return userDAO.retrieve(username)
            .switchIfEmpty(createPublisher)
            .doOnError(error -> LOGGER.error("Failed to provisioning user: {}", username.asString(), error))
            .thenReturn(username);
    }

    private Optional<Pair<String, String>> parseFirstnameAndSurnameFromToken(UserinfoResponse userinfoResponse) {
        Optional<String> firstname = userinfoResponse.claimByPropertyName(FIRSTNAME_PROPERTY);
        Optional<String> surname = userinfoResponse.claimByPropertyName(SURNAME_PROPERTY);

        if (firstname.isEmpty() && surname.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(Pair.of(firstname.orElse(StringUtils.EMPTY), surname.orElse(StringUtils.EMPTY)));
    }
}
