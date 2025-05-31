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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.apache.james.jmap.http.AuthenticationChallenge;
import org.apache.james.jmap.http.AuthenticationScheme;
import org.apache.james.jmap.http.AuthenticationStrategy;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;

import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServerRequest;

public class LemonCookieAuthenticationStrategy implements AuthenticationStrategy {

    public record ResolutionConfiguration(URI resolutionURL, Domain resolutionDomain) {
        public static final String LEMON_COOKIE_RESOLUTION_URL_PROPERTY = "oidc.fallback.lemon.cookie.resolution.url";
        public static final String LEMON_COOKIE_RESOLUTION_DOMAIN_PROPERTY = "oidc.fallback.lemon.cookie.resolution.domain";

        public static Optional<ResolutionConfiguration> maybeFrom(Configuration configuration) {
            String urlString = configuration.getString(LEMON_COOKIE_RESOLUTION_URL_PROPERTY, null);
            String domainString = configuration.getString(LEMON_COOKIE_RESOLUTION_DOMAIN_PROPERTY, null);

            if (urlString == null || domainString == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolutionConfiguration(URI.create(urlString), Domain.of(domainString)));
        }

        public ResolutionConfiguration {
            Preconditions.checkNotNull(resolutionURL, "Resolution URL must not be null");
            Preconditions.checkNotNull(resolutionDomain, "Resolution domain must not be null");
        }
    }

    public static class LemonClient {

        public static class LemonClientException extends RuntimeException {
            public LemonClientException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        private static final String USERNAME_PROPERTY = "result";
        private static final Duration RESPONSE_TIMEOUT_DURATION = Duration.ofSeconds(10);

        private final HttpClient httpClient;
        private final ObjectReader objectReader;
        private final URI resolutionURL;

        public LemonClient(URI resolutionURL) {
            this.httpClient = HttpClient.create()
                .responseTimeout(RESPONSE_TIMEOUT_DURATION);
            this.resolutionURL = resolutionURL;
            this.objectReader = new ObjectMapper().reader().forType(JsonNode.class);
        }

        public Mono<String> resolveUser(String lemonCookieValue) {
            Preconditions.checkArgument(StringUtils.startsWith(lemonCookieValue, "lemonldap="),
                "Invalid Lemon cookie: must start with 'lemonldap=', but got: " + lemonCookieValue);
            return httpClient
                .headers(headers -> {
                    headers.add("Accept", "application/json");
                    headers.add("Cookie", lemonCookieValue);
                })
                .get()
                .uri(resolutionURL)
                .responseSingle((response, responseContent) -> {
                    if (response.status().code() == HttpStatus.SC_OK) {
                        return responseContent.asByteArray().flatMap(this::extractUsernameFromResponse);
                    } else {
                        return responseContent.asString(StandardCharsets.UTF_8)
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .flatMap(responseBody -> Mono.error(new UnauthorizedException(
                                "Failed to resolve Lemon user. Status: " + response.status().code() +
                                    ", Response: " + responseBody)));
                    }
                })
                .onErrorResume(error -> {
                    if (error instanceof UnauthorizedException || error instanceof LemonClientException) {
                        return Mono.error(error);
                    }
                    return Mono.error(new LemonClientException("Failed to resolve Lemon user: " + error.getMessage(), error));
                });
        }

        private Mono<String> extractUsernameFromResponse(byte[] responseBody) {
            return Mono.fromCallable(() -> objectReader.readTree(responseBody))
                .map(jsonNode -> jsonNode.get(USERNAME_PROPERTY))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText)
                .onErrorResume(error
                    -> Mono.error(new LemonClientException("Failed to parse Lemon response: " + new String(responseBody, StandardCharsets.UTF_8), error)));
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(LemonCookieAuthenticationStrategy.class);

    private final LemonClient lemonClient;
    private final ResolutionConfiguration resolutionConfiguration;
    private final SimpleSessionProvider sessionProvider;

    public LemonCookieAuthenticationStrategy(ResolutionConfiguration resolutionConfiguration, SimpleSessionProvider sessionProvider) {
        this.lemonClient = new LemonClient(resolutionConfiguration.resolutionURL());
        this.resolutionConfiguration = resolutionConfiguration;
        this.sessionProvider = sessionProvider;
    }

    @Override
    public Mono<MailboxSession> createMailboxSession(HttpServerRequest httpRequest) {
        return Mono.justOrEmpty(extractLemonCookie(httpRequest))
            .flatMap(lemonClient::resolveUser)
            .map(user -> Username.fromLocalPartWithDomain(user, resolutionConfiguration.resolutionDomain()))
            .map(Throwing.function(sessionProvider::createSession))
            .onErrorResume(error -> {
                if (error instanceof LemonClient.LemonClientException) {
                    LOGGER.warn("Failed to resolve Lemon user", error);
                    return Mono.error(new UnauthorizedException(error.getMessage(), error));
                }
                LOGGER.error("Unexpected error", error);
                return Mono.error(error);
            });
    }

    @Override
    public AuthenticationChallenge correspondingChallenge() {
        return AuthenticationChallenge.of(
            AuthenticationScheme.of("LemonLDAPCookie"),
            ImmutableMap.of());
    }

    private String extractHeaderValue(HttpServerRequest request, String headerName) {
        HttpHeaders httpHeaders = request.requestHeaders();
        return Optional.ofNullable(httpHeaders.get(headerName))
            .orElse(httpHeaders.get(headerName.toLowerCase(Locale.US)));
    }

    private Optional<String> extractLemonCookie(HttpServerRequest request) {
        return Optional.ofNullable(extractHeaderValue(request, "Cookie"))
            .flatMap(cookieHeader -> Splitter.on(';')
                .trimResults()
                .splitToStream(cookieHeader)
                .filter(c -> StringUtils.startsWith(c, "lemonldap="))
                .findFirst());
    }
}