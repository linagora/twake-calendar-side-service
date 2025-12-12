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

import static com.linagora.calendar.storage.configuration.ConfigurationEntryUtils.EMPTY_CONFIGURATION_ENTRIES;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.restapi.routes.UserConfigurationsRoute.UserConfigDTO;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationEntryUtils;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;


public class UserConfigurationPatchRoute extends CalendarRoute {
    public static final Logger LOGGER = LoggerFactory.getLogger(UserConfigurationPatchRoute.class);

    private final UserConfigurationDAO userConfigurationDAO;
    private final ObjectMapper objectMapper;

    @Inject
    public UserConfigurationPatchRoute(Authenticator authenticator, MetricFactory metricFactory, UserConfigurationDAO userConfigurationDAO) {
        super(authenticator, metricFactory);
        this.userConfigurationDAO = userConfigurationDAO;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.PATCH, "/api/configurations");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asByteArray()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid request body")))
            .flatMap(bodyAsBytes -> Mono.fromCallable(() -> serializeBodyRequest().apply(bodyAsBytes))
                .doOnError(e -> LOGGER.error("Failed to deserialize body request. User: {}", session.getUser().asString())))
            .map(this::toConfigurationEntries)
            .flatMap(patchEntries -> mergeWithExistingConfiguration(patchEntries, session))
            .flatMap(mergedEntries -> userConfigurationDAO.persistConfiguration(mergedEntries, session))
            .then(response.status(204).send());
    }

    private Mono<Set<ConfigurationEntry>> mergeWithExistingConfiguration(Set<ConfigurationEntry> patchEntries, MailboxSession session) {
        return userConfigurationDAO.retrieveConfiguration(session)
            .collect(Collectors.toUnmodifiableSet())
            .switchIfEmpty(Mono.just(EMPTY_CONFIGURATION_ENTRIES))
            .map(existingEntries -> ConfigurationEntryUtils.mergeIncomingWithExistingConfiguration(patchEntries, existingEntries));
    }

    private Set<ConfigurationEntry> toConfigurationEntries(List<UserConfigDTO> dtos) {
        return dtos.stream()
            .flatMap(UserConfigDTO::toConfigurationEntries)
            .collect(Collectors.toSet());
    }

    private Function<byte[], List<UserConfigDTO>> serializeBodyRequest() {
        return body -> {
            try {
                return objectMapper.readValue(body, new TypeReference<>() {
                    });
            } catch (Exception e) {
                LOGGER.warn("Failed to deserialize body: {}", new String(body, StandardCharsets.UTF_8));
                throw new IllegalArgumentException("Invalid request body", e);
            }
        };
    }
}
