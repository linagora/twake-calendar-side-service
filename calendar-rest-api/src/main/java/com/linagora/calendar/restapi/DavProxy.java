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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.DavClient;
import com.linagora.calendar.dav.DavConfiguration;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class DavProxy extends DavClient {
    public static final Logger LOGGER = LoggerFactory.getLogger(DavProxy.class);
    private final Authenticator authenticator;
    private final MetricFactory metricFactory;

    @Inject
    public DavProxy(Authenticator authenticator, DavConfiguration davConfiguration, MetricFactory metricFactory) throws SSLException {
        super(davConfiguration);

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
                            client.headers(proxiedHeader -> {
                                    HttpHeaders entries = request.requestHeaders();
                                    Optional.ofNullable(entries.get("Accept", null)).ifPresent(value -> proxiedHeader.add("Accept", value));
                                    Optional.ofNullable(entries.get("Definition", null)).ifPresent(value -> proxiedHeader.add("Accept", value));
                                    Optional.ofNullable(entries.get("Depth", null)).ifPresent(value -> proxiedHeader.add("Depth", value));
                                    Optional.ofNullable(entries.get("Content-Type", null)).ifPresent(value -> proxiedHeader.add("Content-Type", value));
                                    proxiedHeader.add(HttpHeaderNames.AUTHORIZATION, authenticationToken(session.getUser().asString()));
                                })
                                .request(request.method())
                                .uri(request.uri().substring(4)) // remove /dav
                                .send((req, out) -> out.sendByteArray(Mono.just(payload)))
                                .response((res, in) -> {
                                    response.status(res.status());
                                    response.headers(res.responseHeaders());
                                    return response.sendByteArray(in.asByteArray());
                                })))
                        .then()));
    }
}
