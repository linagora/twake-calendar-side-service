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

package com.linagora.calendar.restapi.routes;

import java.util.List;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;

import com.linagora.calendar.restapi.RestApiConfiguration;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * CalDAV / CardDAV service discovery (RFC 6764): redirects well-known URIs to the DAV server.
 */
public class DavDiscoveryRoute implements JMAPRoutes {
    private static final HttpMethod PROPFIND = HttpMethod.valueOf("PROPFIND");
    private static final List<HttpMethod> SUPPORTED_METHODS = List.of(HttpMethod.GET, HttpMethod.HEAD, PROPFIND);
    private static final List<String> WELL_KNOWN_PATHS = List.of("/.well-known/caldav", "/.well-known/carddav");

    private final String davLocation;

    @Inject
    public DavDiscoveryRoute(RestApiConfiguration configuration) {
        this.davLocation = configuration.getDavURL().toString();
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return supportedEndpoints()
            .map(endpoint -> JMAPRoute.builder()
                .endpoint(endpoint)
                .action(this::redirectToDav)
                .corsHeaders());
    }

    private static Stream<Endpoint> supportedEndpoints() {
        return WELL_KNOWN_PATHS.stream()
            .flatMap(path -> SUPPORTED_METHODS.stream()
                .map(method -> Endpoint.ofFixedPath(method, path)));
    }

    private Mono<Void> redirectToDav(HttpServerRequest request, HttpServerResponse response) {
        return response.status(HttpResponseStatus.TEMPORARY_REDIRECT)
            .header(HttpHeaderNames.LOCATION, davLocation)
            .send();
    }
}
