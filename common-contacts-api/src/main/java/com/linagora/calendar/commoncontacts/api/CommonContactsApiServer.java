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

package com.linagora.calendar.commoncontacts.api;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;

import java.util.Optional;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.restapi.ErrorResponse;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class CommonContactsApiServer implements Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommonContactsApiServer.class);

    private final CommonContactsApiConfiguration configuration;
    private final BearerTokenAuthenticator authenticator;
    private final CommonContactsSearchRoute searchRoute;
    private Optional<DisposableServer> server;

    @Inject
    public CommonContactsApiServer(CommonContactsApiConfiguration configuration,
                                   BearerTokenAuthenticator authenticator,
                                   CommonContactsSearchRoute searchRoute) {
        this.configuration = configuration;
        this.authenticator = authenticator;
        this.searchRoute = searchRoute;
        this.server = Optional.empty();
    }

    public void start() {
        server = configuration.port()
            .map(port -> HttpServer.create()
                .port(port)
                .handle(this::handle)
                .bindNow());
        server.ifPresentOrElse(
            disposableServer -> LOGGER.info("Common contacts API listening on port {}", disposableServer.port()),
            () -> LOGGER.info("Common contacts API is disabled as '{}' is not configured", CommonContactsApiConfiguration.PORT_PROPERTY));
    }

    public Port getPort() {
        return server.map(DisposableServer::port)
            .map(Port::of)
            .orElseThrow(() -> new IllegalStateException("port is not available because server is not started or disabled"));
    }

    private Mono<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        if (!authenticator.isAuthorized(request)) {
            return response.status(UNAUTHORIZED)
                .header(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer")
                .send();
        }
        if (!searchRoute.matches(request)) {
            return response.status(NOT_FOUND).send();
        }
        return searchRoute.handle(request, response)
            .onErrorResume(e -> handleError(request, response, e));
    }

    private Mono<Void> handleError(HttpServerRequest request, HttpServerResponse response, Throwable e) {
        if (e instanceof IllegalArgumentException illegalArgumentException) {
            LOGGER.info("Invalid request {} {}", request.method(), request.uri(), e);
            return response.status(BAD_REQUEST)
                .header(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(illegalArgumentException).serializeAsBytes()))
                .then();
        }
        LOGGER.error("Unexpected error on {} {}", request.method(), request.uri(), e);
        return response.status(INTERNAL_SERVER_ERROR).send();
    }

    @PreDestroy
    public void stop() {
        server.ifPresent(DisposableServer::disposeNow);
    }
}
