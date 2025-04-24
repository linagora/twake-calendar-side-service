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

import java.net.URL;
import java.time.Instant;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.apache.james.jwt.DefaultCheckTokenClient;
import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.apache.james.jwt.introspection.TokenIntrospectionResponse;
import org.apache.james.jwt.userinfo.UserinfoResponse;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.icu.impl.Pair;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.TokenInfoResolver;
import com.linagora.calendar.storage.model.Aud;
import com.linagora.calendar.storage.model.Sid;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

import reactor.core.publisher.Mono;

public class OidcEndpointsInfoResolver implements TokenInfoResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OidcEndpointsInfoResolver.class);
    private static final String FIRSTNAME_PROPERTY = "given_name";
    private static final String SURNAME_PROPERTY = "family_name";
    private static final String SID_PROPERTY = "sid";

    private final DefaultCheckTokenClient checkTokenClient;
    private final MetricFactory metricFactory;
    private final URL userInfoURL;
    private final IntrospectionEndpoint introspectionEndpoint;
    private final RestApiConfiguration configuration;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    public OidcEndpointsInfoResolver(DefaultCheckTokenClient checkTokenClient,
                                     MetricFactory metricFactory,
                                     @Named("userInfo") URL userInfoURL,
                                     IntrospectionEndpoint introspectionEndpoint,
                                     RestApiConfiguration configuration,
                                     OpenPaaSUserDAO userDAO) {
        this.checkTokenClient = checkTokenClient;
        this.metricFactory = metricFactory;
        this.userInfoURL = userInfoURL;
        this.introspectionEndpoint = introspectionEndpoint;
        this.configuration = configuration;
        this.userDAO = userDAO;
    }

    @Override
    public Mono<TokenInfo> apply(Token token) {
        return Mono.zip(
            Mono.from(metricFactory.decoratePublisherWithTimerMetric("userinfo-lookup", checkTokenClient.userInfo(userInfoURL, token.value()))),
            Mono.from(metricFactory.decoratePublisherWithTimerMetric("introspection-lookup", checkTokenClient.introspect(introspectionEndpoint, token.value()))))
            .flatMap(tokenInfos -> {
                UserinfoResponse userInfo = tokenInfos.getT1();
                TokenIntrospectionResponse introspectInfo = tokenInfos.getT2();

                Username sub = Username.of(userInfo.claimByPropertyName(configuration.getOidcClaim())
                    .orElseThrow(() -> new UnauthorizedException("Invalid OIDC token: userinfo needs to include " + configuration.getOidcClaim() + " claim")));

                return provisionUserIfNeed(sub, parseFirstnameAndSurnameFromToken(userInfo))
                    .thenReturn(toTokenInfo(sub, userInfo, introspectInfo));
            });
    }

    private TokenInfo toTokenInfo(Username username, UserinfoResponse userinfoResponse, TokenIntrospectionResponse introspectionResponse) {
        return new TokenInfo(
            username.asString(),
            userinfoResponse.claimByPropertyName(SID_PROPERTY).map(Sid::new)
                .or(() -> introspectionResponse.claimByPropertyName(SID_PROPERTY).map(Sid::new)),
            Instant.ofEpochSecond(introspectionResponse.exp().orElseThrow(() -> new UnauthorizedException("Expiration claim ('exp') is required in the token"))),
            introspectionResponse.aud().map(Aud::new).orElseThrow(() -> new UnauthorizedException("Audience claim ('aud') is required in the token")));
    }

    private Mono<Username> provisionUserIfNeed(Username username, Optional<Pair<String, String>> firstnameAndSurnameOpt) {
        Mono<OpenPaaSUser> createPublisher = Mono.justOrEmpty(firstnameAndSurnameOpt)
            .flatMap(name -> userDAO.add(username, name.first, name.second))
            .switchIfEmpty(Mono.defer(() -> userDAO.add(username)))
            .doOnNext(openPaaSUser -> LOGGER.info("Created user: {}", openPaaSUser.username().asString()));

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
