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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import javax.net.ssl.SSLException;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Client for Meet's external API: mints application tokens, creates rooms on behalf of a user.
 *
 * @see <a href="https://github.com/suitenumerique/meet/blob/main/docs/openapi.yaml">Meet External API v1.0</a>
 */
public class MeetApplicationClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeetApplicationClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String ACCESS_TOKEN_FIELD = "access_token";
    private static final int MAX_QUOTED_BODY = 256;
    private static final String GRANT_ACCESS_URL_TEMPLATE = "/external-api/v1.0/rooms/%s/grant-access/";

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
    public record Room(RoomId id, RoomSlug slug, String url) {
    }

    /** A single room-access grant: which room, which delegate. */
    public record RoomAccessGrant(String roomId, String delegateEmail) {
    }

    /** One page of the rooms listing: the matching room id if any, and the next page path. */
    private record RoomPage(Optional<String> roomId, Optional<String> nextPage) {
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
            .headers(h -> h.set("X-Forwarded-Proto", "https"));
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
            .flatMap(this::toRoom);
    }

    /**
     * Find a room by its Meet URL slug (the last non-empty path segment
     * of {@code X-OPENPAAS-VIDEOCONFERENCE}). Only rooms accessible to
     * the JWT's scoped user are considered. Meet's rooms listing is
     * paginated (DRF, 20 rooms per page by default) — every page is
     * walked via {@code next} until the slug matches or the listing
     * runs out.
     */
    public Mono<Optional<String>> findRoomIdBySlug(MeetToken token, RoomSlug slug) {
        return findRoomIdBySlug(token, ROOMS_PATH, slug);
    }

    private Mono<Optional<String>> findRoomIdBySlug(MeetToken token, String pagePath, RoomSlug slug) {
        return get(pagePath, token, "Failed to list rooms")
            .flatMap(page -> matchRoomPage(page, slug))
            .flatMap(page -> {
                if (page.roomId().isPresent() || page.nextPage().isEmpty()) {
                    return Mono.just(page.roomId());
                }
                return findRoomIdBySlug(token, page.nextPage().get(), slug);
            });
    }

    /**
     * Grant admin (or member) access on {@code grant.roomId()} to
     * {@code grant.delegateEmail()}. Idempotent by design of the server
     * (see Meet Patch A).
     */
    public Mono<Void> grantAccess(MeetToken token, RoomAccessGrant grant) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("email", grant.delegateEmail());
        body.put("role", "administrator");

        return post(String.format(GRANT_ACCESS_URL_TEMPLATE, grant.roomId()), Optional.of(token), body,
                "Failed to grant access on room " + grant.roomId() + " to " + grant.delegateEmail())
            .doOnNext(response -> LOGGER.info("Granted admin on Meet room {} to {}", grant.roomId(), grant.delegateEmail()))
            .then();
    }

    private Mono<JsonNode> post(String path, Optional<MeetToken> token, ObjectNode body, String action) {
        byte[] payload = serialize(body);
        return send(HttpMethod.POST, path, token, action,
            request -> request.send(Mono.just(Unpooled.wrappedBuffer(payload))));
    }

    private Mono<JsonNode> get(String path, MeetToken token, String action) {
        return send(HttpMethod.GET, path, Optional.of(token), action, request -> request);
    }

    private Mono<JsonNode> send(HttpMethod method,
                                String path,
                                Optional<MeetToken> token,
                                String action,
                                Function<HttpClient.RequestSender, HttpClient.ResponseReceiver<?>> withBody) {
        return Mono.defer(() -> withBody.apply(httpClient
                .headers(h -> {
                    token.ifPresent(bearer -> h.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + bearer.value()));
                    h.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
                })
                .request(method)
                .uri(path))
            .responseSingle((response, bodyMono) -> {
                int status = response.status().code();
                return bodyMono.asString().defaultIfEmpty("").flatMap(bodyString -> {
                    if (status < 200 || status >= 300) {
                        return Mono.error(new MeetApiException(status, action + ": HTTP " + status + " — " + abbreviate(bodyString)));
                    }
                    return parse(bodyString, action);
                });
            }));
    }

    private Mono<JsonNode> parse(String bodyString, String action) {
        if (bodyString.isEmpty()) {
            return Mono.just(MAPPER.createObjectNode());
        }
        try {
            return Mono.just(MAPPER.readTree(bodyString));
        } catch (Exception e) {
            return Mono.error(new MeetApiException(action + ": unparseable Meet response — " + abbreviate(bodyString), e));
        }
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
                urlNode.asText()));
        } catch (IllegalArgumentException e) {
            return Mono.error(new MeetApiException("Meet room response carries no usable id: " + abbreviate(node.toString()), e));
        }
    }

    private static Mono<RoomPage> matchRoomPage(JsonNode root, RoomSlug slug) {
        JsonNode rooms = root.isArray() ? root : root.get("results");
        return Mono.just(new RoomPage(findSlugMatch(rooms, slug), nextPagePath(root)));
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

    private static Optional<String> findSlugMatch(JsonNode rooms, RoomSlug slug) {
        if (rooms == null || !rooms.isArray()) {
            return Optional.empty();
        }
        return StreamSupport.stream(rooms.spliterator(), false)
            .filter(room -> slug.value().equalsIgnoreCase(room.path("slug").asText("")))
            .map(room -> room.path("id"))
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .findFirst();
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
