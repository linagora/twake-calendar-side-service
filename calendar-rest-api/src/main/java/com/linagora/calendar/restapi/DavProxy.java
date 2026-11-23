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

package com.linagora.calendar.restapi;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SSLException;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCStructuredLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.DavClient;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.TechnicalTokenService;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.NettyOutbound;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.client.PrematureCloseException;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class DavProxy extends DavClient {
    public static final Logger LOGGER = LoggerFactory.getLogger(DavProxy.class);
    private static final String USER_AGENT = "twake-calendar-dav-proxy " + HttpClient.USER_AGENT;
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final List<String> FORWARDED_HEADERS = List.of("Accept", "Destination", "Depth", "Content-Type",
        "X-Http-Method-Override", "Prefer", "If-Match", "If-None-Match", "If-Modified-Since", "If-Unmodified-Since");

    private final Authenticator authenticator;
    private final MetricFactory metricFactory;

    @Inject
    public DavProxy(Authenticator authenticator, DavConfiguration davConfiguration,
                    MetricFactory metricFactory, TechnicalTokenService technicalTokenService) throws SSLException {
        super(davConfiguration, technicalTokenService, USER_AGENT);

        this.authenticator = authenticator;
        this.metricFactory = metricFactory;
    }

    public Mono<Void> forwardRequest(HttpServerRequest request, HttpServerResponse response) {
        LOGGER.debug("Proxying DAV request {} {}", request.method(), request.uri());
        // Authenticate before reading the body: an anonymous caller must not get its upload buffered.
        return authenticator.authenticate(request)
            .flatMap(session -> proxy(request, response, session.getUser()));
    }

    private Mono<Void> proxy(HttpServerRequest request, HttpServerResponse response, Username user) {
        boolean hasBody = hasBody(request.requestHeaders());
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric("davProxy",
                httpClientWithImpersonation(user)
                    // The request body is streamed once: a retry could not send it again.
                    .disableRetry(hasBody)
                    .headers(proxiedHeaders -> copyForwardedHeaders(request.requestHeaders(), proxiedHeaders))
                    .request(request.method())
                    .uri(request.uri().substring(4)) // remove /dav
                    .send((req, out) -> sendBody(request, out, hasBody))
                    .response((res, in) -> handleSabreResponse(response, request, res, in))))
            .then()
            .doOnError(error -> logUpstreamFailure(request, error))
            .onErrorMap(DavProxy::asProxyError);
    }

    private static void copyForwardedHeaders(HttpHeaders entries, HttpHeaders proxiedHeaders) {
        Optional.ofNullable(entries.get("X-Forwarded-For", null)).ifPresent(value -> proxiedHeaders.set("X-Forwarded-For", value));
        Optional.ofNullable(entries.get(HttpHeaderNames.CONTENT_LENGTH, null)).ifPresent(value -> proxiedHeaders.set(HttpHeaderNames.CONTENT_LENGTH, value));
        FORWARDED_HEADERS.forEach(header -> Optional.ofNullable(entries.get(header, null))
            .ifPresent(value -> proxiedHeaders.add(header, value)));
    }

    private static boolean hasBody(HttpHeaders headers) {
        return headers.contains(HttpHeaderNames.CONTENT_LENGTH)
            || headers.containsValue(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED, true);
    }

    private static NettyOutbound sendBody(HttpServerRequest request, NettyOutbound out, boolean hasBody) {
        if (hasBody) {
            // Stream the body chunk by chunk: it is never held whole in memory.
            return out.send(request.receive().retain());
        }
        return out.sendByteArray(Mono.just(EMPTY_BODY));
    }

    private static void logUpstreamFailure(HttpServerRequest request, Throwable error) {
        MDCStructuredLogger.forLogger(LOGGER)
            .field("method", request.method().name())
            .field("url", request.uri())
            .field("exception", error.getClass().getName())
            .log(logger -> logger.warn("Failure upon proxied DAV request {} {}: {}", request.method(), request.uri(), error.getClass().getName(), error));
    }

    private static RuntimeException asProxyError(Throwable error) {
        if (error instanceof ReadTimeoutException) {
            return new DavGatewayTimeoutException(error);
        }
        if (error instanceof PrematureCloseException) {
            return new DavServiceUnavailableException("DAV upstream service closed the connection prematurely", error);
        }
        return new DavServiceUnavailableException(error);
    }

    private static NettyOutbound handleSabreResponse(HttpServerResponse response, HttpServerRequest req, HttpClientResponse res, ByteBufFlux in) {
        response.status(res.status());
        response.headers(res.responseHeaders());
        if (isServerError(res)) {
            return response.sendByteArray(in.aggregate().asByteArray()
                .doOnNext(sabreResponseBytes -> logSabreServerError(req, res, sabreResponseBytes)));
        }
        return response.send(in.retain());
    }

    private static boolean isServerError(HttpClientResponse res) {
        return res.status().codeClass().equals(HttpStatusClass.SERVER_ERROR);
    }

    private static void logSabreServerError(HttpServerRequest req, HttpClientResponse res, byte[] sabreResponseBytes) {
        MDCStructuredLogger.forLogger(LOGGER)
            .field("method", req.method().name())
            .field("url", req.uri())
            .field("statusCode", Integer.toString(res.status().code()))
            .field("response", new String(sabreResponseBytes, StandardCharsets.UTF_8))
            .log(logger -> logger.error("Sabre server error upon DAV request"));
    }
}
