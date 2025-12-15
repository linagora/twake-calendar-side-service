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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * @deprecated Use {@link UserConfigurationPatchRoute} instead.
 *             This PUT endpoint resets all user configurations and does not
 *             support read-only settings semantics.
 */
@Deprecated
public class UserConfigurationsRoute extends CalendarRoute {
    public static final Logger LOGGER = LoggerFactory.getLogger(UserConfigurationsRoute.class);

    public record ConfigurationEntryDTO(@JsonProperty(value = "name", required = true) String name,
                                        @JsonProperty(value = "value", required = true) JsonNode value) {

        public ConfigurationEntryDTO {
            Preconditions.checkArgument(StringUtils.isNotBlank(name), "Name cannot be blank");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserConfigDTO(@JsonProperty(value = "name", required = true) String name,
                                @JsonProperty(value = "configurations", required = true) List<ConfigurationEntryDTO> configurations) {

        public UserConfigDTO {
            Preconditions.checkArgument(StringUtils.isNotBlank(name), "Name cannot be blank");
            Preconditions.checkArgument(configurations != null, "Configurations cannot be null");
        }

        public Stream<ConfigurationEntry> toConfigurationEntries() {
            return configurations.stream()
                .map(entry -> ConfigurationEntry.of(name, entry.name, entry.value));
        }
    }

    private final UserConfigurationDAO userConfigurationDAO;
    private final ObjectMapper objectMapper;

    @Inject
    public UserConfigurationsRoute(Authenticator authenticator, MetricFactory metricFactory, UserConfigurationDAO userConfigurationDAO) {
        super(authenticator, metricFactory);
        this.userConfigurationDAO = userConfigurationDAO;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.PUT, "/api/configurations");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        String scopeValue = extractScopeParam(request);
        if (!"user".equals(scopeValue)) {
            throw new IllegalArgumentException("Invalid scope parameter, expected 'user', got: " + scopeValue);
        }

        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid request body")))
            .flatMap(bodyAsBytes -> Mono.fromCallable(() -> serializeBodyRequest().apply(bodyAsBytes))
                .doOnError(e -> LOGGER.error("Failed to deserialize body request. User: {}, \n{}", session.getUser().asString(),
                    new String(bodyAsBytes, StandardCharsets.UTF_8))))
            .map(toConfigurationEntries())
            .flatMap(configurationEntries -> userConfigurationDAO.persistConfiguration(configurationEntries, session))
            .then(response.status(204).send());
    }

    private String extractScopeParam(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri())
            .parameters()
            .getOrDefault("scope", List.of())
            .stream()
            .findFirst()
            .orElse(null);
    }

    private Function<List<UserConfigDTO>, Set<ConfigurationEntry>> toConfigurationEntries() {
        return dtos -> dtos.stream()
            .flatMap(UserConfigDTO::toConfigurationEntries)
            .collect(Collectors.toSet());
    }

    private Function<byte[], List<UserConfigDTO>> serializeBodyRequest() {
        return body -> {
            try {
                return objectMapper.readValue(
                    body, new TypeReference<>() {
                    });
            } catch (Exception e) {
                LOGGER.warn("Failed to deserialize body: {}", body);
                throw new IllegalArgumentException("Invalid request body", e);
            }
        };
    }
}