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

package com.linagora.calendar.storage;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.base.Preconditions;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public interface TechnicalTokenService {

    Mono<JwtToken> generate(OpenPaaSId domainId);

    Mono<TechnicalTokenInfo> claim(JwtToken token);

    class TechnicalTokenClaimException extends RuntimeException {
        public TechnicalTokenClaimException(String message) {
            super(message);
        }
    }

    record JwtToken(String value) {

        public JwtToken {
            Preconditions.checkArgument(StringUtils.isNotEmpty(value), "Token must not be empty");
        }
    }

    record TechnicalTokenInfo(OpenPaaSId domainId,
                              Map<String, Object> data) {

        public TechnicalTokenInfo {
            Preconditions.checkNotNull(domainId, "Domain ID must not be null");
            Preconditions.checkNotNull(data, "Data must not be null");
        }
    }

    class Impl implements TechnicalTokenService {

        public static Impl from(PropertiesProvider propertiesProvider) throws ConfigurationException {
            try {
                Configuration configuration = propertiesProvider.getConfiguration("configuration");
                String secret = configuration.getString("jwt.technical.token.secret", UUID.randomUUID().toString());
                Duration expiration = Duration.ofSeconds(configuration.getLong("jwt.technical.token.expiration", DEFAULT_EXPIRATION_SECONDS));
                return new Impl(secret, expiration);
            } catch (FileNotFoundException e) {
                LOGGER.info("Configuration file not found, using default values for technical token service");
                return new Impl(UUID.randomUUID().toString(), Duration.ofSeconds(DEFAULT_EXPIRATION_SECONDS));
            }
        }

        public static final Function<OpenPaaSId, Map<String, Object>> TOKEN_DATA_FUNCTION = domainId -> Map.of(
            "data", Map.of("principal", "principals/technicalUser"),
            "type", "dav",
            "user_type", "technical",
            "schemaVersion", 1,
            "__v", 0,
            "description", "Allows to authenticate on Sabre DAV",
            "name", "Sabre Dav",
            "domainId", domainId.value(),
            "_id", UUID.nameUUIDFromBytes(domainId.value().getBytes(StandardCharsets.UTF_8)).toString());

        private static final Logger LOGGER = LoggerFactory.getLogger(TechnicalTokenService.class);
        private static final String CLAIM_NAME_DOMAIN_ID = "domainId";
        private static final String CLAIM_NAME_DATA = "data";
        private static final String CLAIM_TECHNICAL_TOKEN = "technicalToken";
        private static final String ISSUER = "Twake-calendar-side-service";
        private static final long DEFAULT_EXPIRATION_SECONDS = 3600;

        private final Algorithm algorithm;
        private final Duration expiration;
        private final Clock clock;
        private final JWTVerifier jwtVerifier;

        public Impl(String secret,
                    Duration expiration,
                    Clock clock) {
            Preconditions.checkArgument(expiration.toSeconds() > 0, "Expiration must be positive");
            Preconditions.checkArgument(StringUtils.isNotEmpty(secret), "Secret must not be empty");

            this.algorithm = Algorithm.HMAC256(secret);
            this.jwtVerifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
            this.expiration = expiration;
            this.clock = clock;
        }

        public Impl(String secret,
                    Duration expiration) {
            this(secret, expiration, Clock.system(ZoneId.of("UTC")));
        }

        @Override
        public Mono<JwtToken> generate(OpenPaaSId domainId) {
            return Mono.fromCallable(() -> JWT.create()
                    .withIssuer(ISSUER)
                    .withClaim(CLAIM_NAME_DOMAIN_ID, domainId.value())
                    .withClaim(CLAIM_TECHNICAL_TOKEN, true)
                    .withClaim(CLAIM_NAME_DATA, TOKEN_DATA_FUNCTION.apply(domainId))
                    .withExpiresAt(clock.instant().plusSeconds(expiration.toSeconds()))
                    .sign(algorithm))
                .map(JwtToken::new)
                .subscribeOn(Schedulers.parallel());
        }

        @Override
        public Mono<TechnicalTokenInfo> claim(JwtToken token) {
            Preconditions.checkNotNull(token, "Token must not be null");
            return Mono.fromCallable(() -> jwtVerifier.verify(token.value()))
                .map(decodedJWT -> {
                    Preconditions.checkArgument(decodedJWT.getClaim(CLAIM_TECHNICAL_TOKEN).asBoolean(),
                        "Token is not a technical token: " + token.value());
                    String domainId = decodedJWT.getClaim(CLAIM_NAME_DOMAIN_ID).asString();
                    Map<String, Object> data = decodedJWT.getClaim(CLAIM_NAME_DATA).asMap();
                    return new TechnicalTokenInfo(new OpenPaaSId(domainId), data);
                })
                .subscribeOn(Schedulers.parallel())
                .onErrorResume(throwable
                    -> Mono.error(new TechnicalTokenClaimException("Unable to claim technical token: " + token.value() + " , " + throwable.getMessage())));
        }
    }
}
