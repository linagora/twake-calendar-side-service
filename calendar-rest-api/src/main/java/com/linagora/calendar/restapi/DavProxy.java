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

import java.util.Optional;

import javax.net.ssl.SSLException;

import jakarta.inject.Inject;

import org.apache.james.jmap.http.Authenticator;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.MDCStructuredLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.DavClient;
import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.TechnicalTokenService;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpStatusClass;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.NettyOutbound;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class DavProxy extends DavClient {
    public static final Logger LOGGER = LoggerFactory.getLogger(DavProxy.class);
    private final Authenticator authenticator;
    private final MetricFactory metricFactory;

    @Inject
    public DavProxy(Authenticator authenticator, DavConfiguration davConfiguration,
                    MetricFactory metricFactory, TechnicalTokenService technicalTokenService) throws SSLException {
        super(davConfiguration, technicalTokenService);

        this.authenticator = authenticator;
        this.metricFactory = metricFactory;
    }

    public Mono<Void> forwardRequest(HttpServerRequest request, HttpServerResponse response) {
        LOGGER.info("Proxying DAV request {} {}", request.method(), request.uri());
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.just("".getBytes()))
            .flatMap(payload -> authenticator.authenticate(request)
                .flatMap(session ->
                    Mono.from(metricFactory.decoratePublisherWithTimerMetric("davProxy",
                            httpClientWithImpersonation(session.getUser()).headers(proxiedHeader -> {
                                    HttpHeaders entries = request.requestHeaders();
                                    Optional.ofNullable(entries.get("Accept", null)).ifPresent(value -> proxiedHeader.add("Accept", value));
                                    Optional.ofNullable(entries.get("Destination", null)).ifPresent(value -> proxiedHeader.add("Destination", value));
                                    Optional.ofNullable(entries.get("Depth", null)).ifPresent(value -> proxiedHeader.add("Depth", value));
                                    Optional.ofNullable(entries.get("Content-Type", null)).ifPresent(value -> proxiedHeader.add("Content-Type", value));
                                })
                                .request(request.method())
                                .uri(request.uri().substring(4)) // remove /dav
                                .send((req, out) -> out.sendByteArray(Mono.just(payload)))
                                .response((res, in) -> handleSabreResponse(response, request, res, in))))
                        .then()));
    }

    private static NettyOutbound handleSabreResponse(HttpServerResponse response, HttpServerRequest req, HttpClientResponse res, ByteBufFlux in) {
        Mono<byte[]> aggregatedBytes = in.aggregate().asByteArray()
            .map(sabreResponseBytes -> {
                if (res.status().codeClass().equals(HttpStatusClass.SERVER_ERROR)) {
                    MDCStructuredLogger.forLogger(LOGGER)
                        .field("method", req.method().name())
                        .field("url", req.uri())
                        .field("statusCode", Integer.toString(res.status().code()))
                        .field("response", new String(sabreResponseBytes))
                        .log(logger -> logger.error("Sabre server error upon DAV request"));
                }
                return sabreResponseBytes;
            });
        response.status(res.status());
        response.headers(res.responseHeaders());
        return response.sendByteArray(aggregatedBytes);
    }
}
