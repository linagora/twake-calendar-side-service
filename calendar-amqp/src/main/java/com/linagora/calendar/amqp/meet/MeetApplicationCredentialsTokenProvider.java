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

import javax.net.ssl.SSLException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * {@link MeetTokenProvider} backed by Meet application credentials: exchanges
 * the configured client_id / client_secret, against Meet's own token
 * endpoint, for a JWT scoped to the given user email. Meet bakes the resolved
 * user into the JWT as a {@code user_id} claim and trusts this service's
 * assertion that it acts for that person.
 */
public class MeetApplicationCredentialsTokenProvider implements MeetTokenProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";

    private final HttpClient client;
    private final MeetConfiguration config;

    public MeetApplicationCredentialsTokenProvider(MeetConfiguration config) throws SSLException {
        this.config = config;
        this.client = config.enabled() ? MeetApplicationClient.httpClientFor(config) : null;
    }

    @Override
    public Mono<String> fetchToken(String userEmail) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_id", config.clientId());
        body.put("client_secret", config.clientSecret());
        body.put("grant_type", "client_credentials");
        body.put("scope", userEmail);

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
                    return Mono.error(new MeetApplicationClient.MeetApiException(
                        "Failed to obtain application token: HTTP " + status.code() + " — " + bodyString));
                });
            });
    }

    private Mono<String> extractAccessToken(String bodyString) {
        try {
            JsonNode node = MAPPER.readTree(bodyString);
            JsonNode tokenNode = node.get("access_token");
            if (tokenNode == null || !tokenNode.isTextual()) {
                return Mono.error(new MeetApplicationClient.MeetApiException("Meet token response missing access_token: " + bodyString));
            }
            return Mono.just(tokenNode.asText());
        } catch (Exception e) {
            return Mono.error(new MeetApplicationClient.MeetApiException("Failed to parse Meet token response: " + bodyString, e));
        }
    }

    private static byte[] serialize(ObjectNode body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialise Meet request body", e);
        }
    }
}
