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

import java.util.Optional;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Thin Reactor-Netty client for LaSuite Meet's application-scoped
 * external API. See {@code MeetHostDelegationService} for the intended
 * calling pattern.
 */
public class MeetApplicationClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeetApplicationClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String GRANT_ACCESS_URL_TEMPLATE = "/external-api/v1.0/rooms/%s/grant-access/";

    private final HttpClient client;
    private final MeetConfiguration config;

    public MeetApplicationClient(MeetConfiguration config) throws SSLException {
        this.config = config;
        this.client = config.enabled() ? createHttpClient(config) : null;
    }

    private static HttpClient createHttpClient(MeetConfiguration config) throws SSLException {
        HttpClient httpClient = HttpClient.create()
            .baseUrl(config.externalApiBaseUrl().toString())
            .responseTimeout(config.responseTimeout())
            // Django (Meet backend) enforces SECURE_SSL_REDIRECT and would issue a
            // 301 back to https://… when reached directly on port 8000 inside the
            // docker network. Announce https via the forwarded header — matches
            // what the nginx frontend does when routing external traffic.
            .headers(h -> h.set("X-Forwarded-Proto", "https"));
        if (config.trustAllSslCerts()) {
            SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
            return httpClient.secure(spec -> spec.sslContext(sslContext));
        }
        return httpClient;
    }

    /**
     * Exchange client credentials for a JWT scoped to {@code organizerEmail}.
     * Fails with {@link MeetApiException} on any non-2xx or transport error.
     */
    public Mono<String> fetchApplicationToken(String organizerEmail) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_id", config.clientId());
        body.put("client_secret", config.clientSecret());
        body.put("grant_type", "client_credentials");
        body.put("scope", organizerEmail);

        byte[] payload = serialize(body);

        return client.headers(h -> h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON))
            .post()
            .uri(TOKEN_PATH)
            .send(Mono.just(Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, bodyMono) -> {
                HttpResponseStatus status = response.status();
                return bodyMono.asString().defaultIfEmpty("").flatMap(bodyString -> {
                    if (status.code() >= 200 && status.code() < 300) {
                        return extractAccessToken(bodyString);
                    }
                    return Mono.error(new MeetApiException(
                        "Failed to obtain application token: HTTP " + status.code() + " — " + bodyString));
                });
            });
    }

    /**
     * Find a room by its Meet URL slug (the last non-empty path segment
     * of {@code X-OPENPAAS-VIDEOCONFERENCE}). Only rooms accessible to
     * the JWT's scoped user are considered.
     */
    public Mono<Optional<String>> findRoomIdBySlug(String bearerToken, String slug) {
        return client.headers(h -> h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken))
            .get()
            .uri(ROOMS_PATH)
            .responseSingle((response, bodyMono) -> {
                HttpResponseStatus status = response.status();
                return bodyMono.asString().defaultIfEmpty("").flatMap(bodyString -> {
                    if (status.code() >= 200 && status.code() < 300) {
                        return matchRoomBySlug(bodyString, slug);
                    }
                    return Mono.error(new MeetApiException(
                        "Failed to list rooms: HTTP " + status.code() + " — " + bodyString));
                });
            });
    }

    /**
     * Grant admin (or member) access on {@code roomId} to {@code delegateEmail}.
     * Idempotent by design of the server (see Meet Patch A).
     */
    public Mono<Void> grantAccess(String bearerToken, String roomId, String delegateEmail) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("email", delegateEmail);
        body.put("role", "administrator");
        byte[] payload = serialize(body);

        String path = String.format(GRANT_ACCESS_URL_TEMPLATE, roomId);

        return client.headers(h -> {
                h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken);
                h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            })
            .post()
            .uri(path)
            .send(Mono.just(Unpooled.wrappedBuffer(payload)))
            .responseSingle((response, bodyMono) -> {
                HttpResponseStatus status = response.status();
                return bodyMono.asString().defaultIfEmpty("").flatMap(bodyString -> {
                    if (status.code() >= 200 && status.code() < 300) {
                        LOGGER.info("Granted admin on Meet room {} to {} (HTTP {})", roomId, delegateEmail, status.code());
                        return Mono.<Void>empty();
                    }
                    return Mono.error(new MeetApiException(
                        "Failed to grant access on room " + roomId + " to " + delegateEmail
                            + ": HTTP " + status.code() + " — " + bodyString));
                });
            });
    }

    private Mono<String> extractAccessToken(String bodyString) {
        try {
            JsonNode node = MAPPER.readTree(bodyString);
            JsonNode tokenNode = node.get("access_token");
            if (tokenNode == null || !tokenNode.isTextual()) {
                return Mono.error(new MeetApiException("Meet token response missing access_token: " + bodyString));
            }
            return Mono.just(tokenNode.asText());
        } catch (Exception e) {
            return Mono.error(new MeetApiException("Failed to parse Meet token response: " + bodyString, e));
        }
    }

    private Mono<Optional<String>> matchRoomBySlug(String bodyString, String slug) {
        try {
            JsonNode root = MAPPER.readTree(bodyString);
            JsonNode rooms = root.isArray() ? root : root.get("results");
            if (rooms == null || !rooms.isArray()) {
                return Mono.just(Optional.empty());
            }
            for (JsonNode room : rooms) {
                JsonNode slugNode = room.get("slug");
                JsonNode idNode = room.get("id");
                if (slugNode != null && slug.equalsIgnoreCase(slugNode.asText()) && idNode != null) {
                    return Mono.just(Optional.of(idNode.asText()));
                }
            }
            return Mono.just(Optional.empty());
        } catch (Exception e) {
            return Mono.error(new MeetApiException("Failed to parse Meet /rooms/ response", e));
        }
    }

    private static byte[] serialize(ObjectNode body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise Meet request body", e);
        }
    }

    public static class MeetApiException extends RuntimeException {
        public MeetApiException(String message) {
            super(message);
        }

        public MeetApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
