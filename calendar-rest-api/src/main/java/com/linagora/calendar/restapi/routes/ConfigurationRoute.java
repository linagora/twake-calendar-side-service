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
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.streams.Iterators;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationDocument;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationResolver;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ConfigurationRoute extends CalendarRoute {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static class RequestDTO {
        private final String name;
        private final List<String> keys;

        @JsonCreator
        public RequestDTO(@JsonProperty("name") String name, @JsonProperty("keys") List<String> keys) {
            this.name = name;
            this.keys = keys;
        }

        Stream<EntryIdentifier> asConfigurationKeys() {
            ModuleName moduleName = new ModuleName(name);
            return keys.stream()
                .map(key -> new EntryIdentifier(moduleName, new ConfigurationKey(key)));
        }
    }

    private final ConfigurationResolver configurationResolver;

    @Inject
    public ConfigurationRoute(Authenticator authenticator, MetricFactory metricFactory, ConfigurationResolver configurationResolver) {
        super(authenticator, metricFactory);
        this.configurationResolver = configurationResolver;
    }

    @Override
    Endpoint endpoint() {
        return Endpoint.ofFixedPath(HttpMethod.POST, "/api/configurations");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asByteArray()
            .map(Throwing.function(OBJECT_MAPPER::readTree))
            .map(node -> {
                Preconditions.checkArgument(node instanceof ArrayNode,"Expecting a JSON array");
                return (ArrayNode) node;
            })
            .map(arrayNode -> Iterators.toStream(arrayNode.elements())
                .map(Throwing.function(node -> OBJECT_MAPPER.treeToValue(node, RequestDTO.class)))
                .flatMap(RequestDTO::asConfigurationKeys)
                .collect(ImmutableSet.toImmutableSet()))
            .flatMap(keys -> configurationResolver.resolve(keys, session))
            .map(ConfigurationDocument::asJson)
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsString))
            .flatMap(string -> response.status(HttpResponseStatus.OK)
                .sendString(Mono.just(string))
                .then());
    }
}
