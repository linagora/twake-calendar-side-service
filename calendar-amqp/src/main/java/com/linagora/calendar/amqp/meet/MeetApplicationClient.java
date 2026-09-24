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

package com.linagora.calendar.amqp.meet;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

/**
 * Client for Meet's external API: mints application tokens, creates rooms on behalf of a user.
 *
 * @see <a href="https://github.com/suitenumerique/meet/blob/main/docs/openapi.yaml">Meet External API v1.0</a>
 */
public class MeetApplicationClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "twake-calendar-side-service " + HttpClient.USER_AGENT;

    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String ACCESS_TOKEN_FIELD = "access_token";
    private static final int MAX_QUOTED_BODY = 256;
    private static final int SLUG_COLLISION_RETRIES = 3;

    /** A bearer token scoped to one user, which Meet accepts as us acting for them. */
    public record MeetToken(String value) {
    }

    /** A room's primary key, and the only handle the API accepts for a lookup. */
    public record RoomId(UUID value) {
    }

    /** The last path segment of a room URL. Meet mints it and marks it read-only: read it, never ask for it. */
    public record RoomSlug(String value) {
    }

    /** A room as Meet's external API describes it. */
    public record Room(RoomId id, RoomSlug slug, URL url) {
    }

    /** A Meet response this service could not use, carrying the status so callers can branch on it. */
    public static class MeetApiException extends RuntimeException {
        private final int status;

        public MeetApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public MeetApiException(String message, Throwable cause) {
            super(message, cause);
            this.status = -1;
        }

        public int status() {
            return status;
        }
    }

    private final HttpClient httpClient;
    private final MeetConfiguration config;

    @Inject
    public MeetApplicationClient(MeetConfiguration config) {
        this.config = config;
        this.httpClient = httpClientFor(config);
    }

    private static HttpClient httpClientFor(MeetConfiguration config) {
        HttpClient httpClient = HttpClient.create()
            .baseUrl(config.externalApiBaseUrl().toString())
            .responseTimeout(config.responseTimeout())
            // Django (Meet backend) enforces SECURE_SSL_REDIRECT and would issue a
            // 301 back to https://… when reached directly on port 8000 inside the
            // docker network. Announce https via the forwarded header — matches
            // what the nginx frontend does when routing external traffic.
            .headers(h -> {
                h.set(HttpHeaderNames.USER_AGENT, USER_AGENT);
                h.set("X-Forwarded-Proto", "https");
            });
        if (!config.trustAllSslCerts()) {
            return httpClient;
        }
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
            return httpClient.secure(spec -> spec.sslContext(sslContext));
        } catch (SSLException e) {
            // Guice binds this class with a plain `bind(...).in(Scopes.SINGLETON)`;
            // a checked exception here would force the module to hand-wire a provider.
            throw new IllegalStateException("Unable to build the insecure SSL context for the Meet client", e);
        }
    }

    public Mono<MeetToken> fetchToken(MailAddress user) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_id", config.clientId());
        body.put("client_secret", config.clientSecret());
        body.put("grant_type", "client_credentials");
        body.put("scope", user.asString());

        // The one call made without a bearer token: this is where it is minted.
        return post(TOKEN_PATH, Optional.empty(), body, "Failed to obtain application token")
            .flatMap(node -> {
                JsonNode token = node.get(ACCESS_TOKEN_FIELD);
                if (token == null || !token.isTextual()) {
                    return Mono.error(new MeetApiException(-1, "Meet token response missing access_token: " + abbreviate(node.toString())));
                }
                return Mono.just(new MeetToken(token.asText()));
            });
    }

    public Mono<Room> createRoom(MeetToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        config.roomAccessLevel().ifPresent(level -> body.put("access_level", level));

        return post(ROOMS_PATH, Optional.of(token), body, "Failed to create room")
            .flatMap(this::toRoom)
            .retryWhen(Retry.max(SLUG_COLLISION_RETRIES)
                .filter(MeetApplicationClient::isSlugCollision)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /** Meet drew a code it had already used: a 400 naming the slug, and the next POST draws another. */
    private static boolean isSlugCollision(Throwable error) {
        return error instanceof MeetApiException meetError
            && meetError.status() == HttpResponseStatus.BAD_REQUEST.code()
            && meetError.getMessage().contains("\"slug\"");
    }

    private Mono<JsonNode> post(String path, Optional<MeetToken> token, ObjectNode body, String action) {
        byte[] payload = serialize(body);
        return Mono.defer(() -> httpClient
            .headers(h -> {
                token.ifPresent(bearer -> h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearer.value()));
                h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            })
            .post()
            .uri(path)
            .send(Mono.just(Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, bodyMono) -> {
                int status = response.status().code();
                return bodyMono.asString().defaultIfEmpty("").flatMap(bodyString -> {
                    if (status < 200 || status >= 300) {
                        return Mono.error(new MeetApiException(status, action + ": HTTP " + status + " — " + abbreviate(bodyString)));
                    }
                    if (bodyString.isEmpty()) {
                        return Mono.just(MAPPER.createObjectNode());
                    }
                    try {
                        return Mono.just(MAPPER.readTree(bodyString));
                    } catch (Exception e) {
                        return Mono.error(new MeetApiException(action + ": unparseable Meet response — " + abbreviate(bodyString), e));
                    }
                });
            }));
    }

    private Mono<Room> toRoom(JsonNode node) {
        JsonNode urlNode = node.get("url");
        if (urlNode == null || !urlNode.isTextual()) {
            return Mono.error(new MeetApiException(-1,
                "Meet room response carries no url — is APPLICATION_BASE_URL set on Meet? " + abbreviate(node.toString())));
        }
        try {
            return Mono.just(new Room(new RoomId(UUID.fromString(node.path("id").asText(""))),
                new RoomSlug(node.path("slug").asText("")),
                URI.create(urlNode.asText()).toURL()));
        } catch (IllegalArgumentException | MalformedURLException e) {
            return Mono.error(new MeetApiException("Unusable Meet room response: " + abbreviate(node.toString()), e));
        }
    }

    private static String abbreviate(String body) {
        return StringUtils.abbreviate(body, MAX_QUOTED_BODY);
    }

    private static byte[] serialize(ObjectNode body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise Meet request body", e);
        }
    }
}
