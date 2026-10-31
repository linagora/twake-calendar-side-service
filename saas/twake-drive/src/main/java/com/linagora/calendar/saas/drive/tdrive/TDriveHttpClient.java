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

package com.linagora.calendar.saas.drive.tdrive;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

final class TDriveHttpClient {
    static final String JSON_API = "application/vnd.api+json";

    record Request(HttpMethod method, String url, String bearer, String accept) {
        @Override
        public String toString() {
            return "Request[method=" + method + ", credentials=REDACTED]";
        }
    }

    record RequestBody(String contentType, byte[] payload, String checksum) {
        static final RequestBody EMPTY = new RequestBody(null, null, null);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client;
    private final TDriveConfiguration configuration;

    TDriveHttpClient(TDriveConfiguration configuration) {
        this.configuration = configuration;
        HttpClient httpClient = HttpClient.create()
            .followRedirect(false)
            // Never retry a POST implicitly: a lost response may already have created a file or permission.
            .disableRetry(true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) configuration.timeout().toMillis())
            .responseTimeout(configuration.timeout())
            .headers(headers -> headers.set(HttpHeaderNames.USER_AGENT, "twake-calendar-side-service " + HttpClient.USER_AGENT));
        if (configuration.trustAllSslCerts()) {
            try {
                SslContext sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
            } catch (SSLException e) {
                throw new IllegalStateException("Cannot configure TDrive TLS", e);
            }
        }
        this.client = httpClient;
    }

    <T> Mono<T> request(Request request, RequestBody body, Class<T> responseType, String operation) {
        return Mono.defer(() -> withHeaders(request, body)
            .request(request.method()).uri(request.url())
            .send(body.payload() == null ? Mono.empty() : Mono.fromSupplier(() -> Unpooled.wrappedBuffer(body.payload())))
            .responseSingle((response, content) -> content.asString(StandardCharsets.UTF_8).defaultIfEmpty("")
                .flatMap(value -> parseResponse(response.status().code(), value, responseType, operation))))
            .timeout(configuration.timeout())
            .onErrorMap(error -> !(error instanceof AttachmentStorageException), error ->
                new AttachmentStorageException(transportFailureReason(error), -1, operation + ": request failed", error));
    }

    private HttpClient withHeaders(Request request, RequestBody body) {
        return client.headers(headers -> {
            headers.set(HttpHeaderNames.ACCEPT, request.accept());
            headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer " + request.bearer());
            if (body.contentType() != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, body.contentType());
            }
            if (body.checksum() != null) {
                headers.set(HttpHeaderNames.CONTENT_MD5, body.checksum());
            }
        });
    }

    private <T> Mono<T> parseResponse(int status, String value, Class<T> responseType, String operation) {
        if (status < 200 || status >= 300) {
            // Error bodies can echo partner credentials, service tokens or share codes.
            return Mono.error(new AttachmentStorageException(Reason.UPSTREAM_FAILURE, status,
                operation + ": HTTP " + status, null));
        }
        try {
            T parsed = MAPPER.readValue(value, responseType);
            if (parsed == null) {
                return Mono.error(new AttachmentStorageException(Reason.INVALID_RESPONSE, operation + ": empty response"));
            }
            return Mono.just(parsed);
        } catch (JsonProcessingException e) {
            return Mono.error(new AttachmentStorageException(Reason.INVALID_RESPONSE, operation + ": invalid JSON response"));
        }
    }

    private static Reason transportFailureReason(Throwable error) {
        return switch (error) {
            case TimeoutException ignored -> Reason.TIMEOUT;
            case ReadTimeoutException ignored -> Reason.TIMEOUT;
            case ConnectTimeoutException ignored -> Reason.TIMEOUT;
            default -> Reason.UPSTREAM_FAILURE;
        };
    }
}
