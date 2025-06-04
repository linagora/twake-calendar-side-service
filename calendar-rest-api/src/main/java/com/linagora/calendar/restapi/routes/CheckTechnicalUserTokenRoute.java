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

import static com.linagora.calendar.restapi.RestApiConstants.JSON_HEADER;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.RestApiConstants;
import com.linagora.calendar.storage.TechnicalTokenService;
import com.linagora.calendar.storage.TechnicalTokenService.JwtToken;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class CheckTechnicalUserTokenRoute implements JMAPRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(CheckTechnicalUserTokenRoute.class);
    private static final String TOKEN_HEADER_NAME = "X-TECHNICAL-TOKEN";
    private final TechnicalTokenService technicalTokenService;
    private final MetricFactory metricFactory;
    private final ObjectMapper objectMapper;

    @Inject
    public CheckTechnicalUserTokenRoute(TechnicalTokenService technicalTokenService, MetricFactory metricFactory) {
        this.technicalTokenService = technicalTokenService;
        this.metricFactory = metricFactory;
        this.objectMapper = RestApiConstants.OBJECT_MAPPER_DEFAULT;
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/technicalToken/introspect");
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(
            JMAPRoute.builder()
                .endpoint(endpoint())
                .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), verifyTechnicalUserToken(req, res))))
                .corsHeaders());
    }

    private Mono<Void> verifyTechnicalUserToken(HttpServerRequest request, HttpServerResponse response) {
        return Mono.justOrEmpty(getToken(request))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Missing technical user token in request headers " + TOKEN_HEADER_NAME)))
            .flatMap(requestToken -> technicalTokenService.claim(new JwtToken(requestToken)))
            .map(Throwing.function(tokenClaims -> objectMapper.writeValueAsBytes(tokenClaims.data())))
            .flatMap(bytes -> response.status(200)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.just(bytes))
                .then())
            .onErrorResume(Exception.class, exception -> {
                LOGGER.warn("Error while verifying technical user ", exception);
                return doOnError(response);
            });
    }

    Mono<Void> doOnError(HttpServerResponse response) {
        return response.status(HttpResponseStatus.NOT_FOUND)
            .headers(JSON_HEADER)
            .sendByteArray(Mono.fromCallable(() -> ErrorResponse.of(404, "Not found", "Token not found or expired")
                .serializeAsBytes()))
            .then();
    }

    private Optional<String> getToken(HttpServerRequest request) {
        HttpHeaders httpHeaders = request.requestHeaders();
        return Optional.ofNullable(httpHeaders.get(TOKEN_HEADER_NAME))
            .or(() -> Optional.ofNullable(httpHeaders.get(TOKEN_HEADER_NAME.toLowerCase(Locale.US))));
    }
}