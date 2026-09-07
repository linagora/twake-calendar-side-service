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

import java.net.URI;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.StreamSupport;

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
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

/**
 * Thin Reactor-Netty client for LaSuite Meet's application-scoped
 * external API. Callers authenticate by fetching a bearer token from a
 * {@link MeetTokenProvider}, then pass it to the methods here.
 */
public class MeetApplicationClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeetApplicationClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String GRANT_ACCESS_URL_TEMPLATE = "/external-api/v1.0/rooms/%s/grant-access/";

    private final HttpClient client;

    public MeetApplicationClient(MeetConfiguration config) throws SSLException {
        this.client = config.enabled() ? httpClientFor(config) : null;
    }

    static HttpClient httpClientFor(MeetConfiguration config) throws SSLException {
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
     * Find a room by its Meet URL slug (the last non-empty path segment
     * of {@code X-OPENPAAS-VIDEOCONFERENCE}). Only rooms accessible to
     * the JWT's scoped user are considered. Meet's rooms listing is
     * paginated (DRF, 20 rooms per page by default) — every page is
     * walked via {@code next} until the slug matches or the listing
     * runs out.
     */
    public Mono<Optional<String>> findRoomIdBySlug(String bearerToken, String slug) {
        return findRoomIdBySlug(bearerToken, ROOMS_PATH, slug);
    }

    private Mono<Optional<String>> findRoomIdBySlug(String bearerToken, String pagePath, String slug) {
        return Mono.defer(() -> client.headers(h -> h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken))
            .get()
            .uri(pagePath)
            .responseSingle(handleErrors("Failed to list rooms", bodyString -> matchRoomPage(bodyString, slug)
                .flatMap(page -> {
                    if (page.roomId().isPresent() || page.nextPage().isEmpty()) {
                        return Mono.just(page.roomId());
                    }
                    return findRoomIdBySlug(bearerToken, page.nextPage().get(), slug);
                }))));
    }

    /** One page of the rooms listing: the matching room id if any, and the next page path. */
    private record RoomPage(Optional<String> roomId, Optional<String> nextPage) {
    }

    /**
     * Grant admin (or member) access on {@code grant.roomId()} to
     * {@code grant.delegateEmail()}. Idempotent by design of the server
     * (see Meet Patch A).
     */
    public Mono<Void> grantAccess(String bearerToken, RoomAccessGrant grant) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("email", grant.delegateEmail());
        body.put("role", "administrator");
        byte[] payload = serialize(body);

        String path = String.format(GRANT_ACCESS_URL_TEMPLATE, grant.roomId());

        return client.headers(h -> {
                h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearerToken);
                h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            })
            .post()
            .uri(path)
            .send(Mono.just(Unpooled.wrappedBuffer(payload)))
            .responseSingle(handleErrors(
                "Failed to grant access on room " + grant.roomId() + " to " + grant.delegateEmail(),
                bodyString -> {
                    LOGGER.info("Granted admin on Meet room {} to {}", grant.roomId(), grant.delegateEmail());
                    return Mono.<Void>empty();
                }));
    }

    /** A single room-access grant: which room, which delegate. */
    public record RoomAccessGrant(String roomId, String delegateEmail) {
    }

    /**
     * Maps a Meet HTTP response: success (2xx) is handed to
     * {@code onSuccess}, anything else fails with {@link MeetApiException}
     * carrying {@code action} and the status.
     */
    private static <T> BiFunction<HttpClientResponse, ByteBufMono, Mono<T>> handleErrors(String action, Function<String, Mono<T>> onSuccess) {
        return (response, bodyMono) -> {
            HttpResponseStatus status = response.status();
            return bodyMono.asString().defaultIfEmpty("").flatMap(bodyString -> {
                if (status.code() >= 200 && status.code() < 300) {
                    return onSuccess.apply(bodyString);
                }
                return Mono.error(new MeetApiException(action + ": HTTP " + status.code() + " — " + bodyString));
            });
        };
    }

    private Mono<RoomPage> matchRoomPage(String bodyString, String slug) {
        try {
            JsonNode root = MAPPER.readTree(bodyString);
            JsonNode rooms = root.isArray() ? root : root.get("results");
            return Mono.just(new RoomPage(findSlugMatch(rooms, slug), nextPagePath(root)));
        } catch (Exception e) {
            return Mono.error(new MeetApiException("Failed to parse Meet /rooms/ response", e));
        }
    }

    /**
     * DRF returns {@code next} as an absolute URL pointing at Meet's own
     * host. Only its path and query are followed — the configured base URL
     * stays the authority, which keeps docker-network addressing and the
     * forwarded-proto header working.
     */
    private static Optional<String> nextPagePath(JsonNode root) {
        JsonNode next = root.get("next");
        if (next == null || !next.isTextual()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(next.asText());
            return Optional.of(uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> findSlugMatch(JsonNode rooms, String slug) {
        if (rooms == null || !rooms.isArray()) {
            return Optional.empty();
        }
        return StreamSupport.stream(rooms.spliterator(), false)
            .filter(room -> slug.equalsIgnoreCase(room.path("slug").asText("")))
            .map(room -> room.path("id"))
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .findFirst();
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
