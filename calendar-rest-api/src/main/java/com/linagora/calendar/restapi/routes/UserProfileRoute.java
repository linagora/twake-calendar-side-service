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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.user.api.UsersRepository;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationResolver;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class UserProfileRoute extends CalendarRoute {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new Jdk8Module());

    public static class ProfileResponseDTO extends UserRoute.ResponseDTO {
        private final JsonNode configuration;
        private final OpenPaaSUser user;

        public ProfileResponseDTO(OpenPaaSUser user, OpenPaaSId domainId, JsonNode configuration) {
            super(user, domainId);
            this.configuration = configuration;
            this.user = user;
        }

        @JsonProperty("isPlatformAdmin")
        public boolean isPlatformAdmin() {
            return false;
        }

        @JsonProperty("id")
        public String id() {
            return getId();
        }

        @JsonProperty("login")
        public LoginDTO getLogin() {
            return new LoginDTO();
        }

        @JsonProperty("accounts")
        public ImmutableList<AccountDTO> getAccount() {
            return ImmutableList.of(new AccountDTO(user));
        }

        @JsonProperty("configurations")
        public JsonNode getConfigurations() {
            ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
            objectNode.put("modules", configuration);
            return objectNode;
        }
    }

    public static class LoginDTO {
        @JsonProperty("success")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX")
        public ZonedDateTime getJoinedAt() {
            return ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        }

        @JsonProperty("failures")
        public ImmutableList<String> getFailures() {
            return ImmutableList.of();
        }
    }

    public static class AccountDTO {
        private final OpenPaaSUser user;

        public AccountDTO(OpenPaaSUser user) {
            this.user = user;
        }

        @JsonProperty("hosted")
        public boolean getHosted() {
            return true;
        }

        @JsonProperty("preferredEmailIndex")
        public int getPreferedIndex() {
            return 0;
        }

        @JsonProperty("type")
        public String getType() {
            return "email";
        }

        @JsonProperty("emails")
        public ImmutableList<String> getEmails() {
            return ImmutableList.of(user.username().asString());
        }

        @JsonProperty("timestamps")
        public DomainRoute.Timestamp getTimestamps() {
            return new DomainRoute.Timestamp();
        }
    }

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final ConfigurationResolver configurationResolver;
    private final UsersRepository usersRepository;

    @Inject
    public UserProfileRoute(Authenticator authenticator, MetricFactory metricFactory, OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO, ConfigurationResolver configurationResolver, UsersRepository usersRepository) {
        super(authenticator, metricFactory);
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.configurationResolver = configurationResolver;
        this.usersRepository = usersRepository;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/user");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return userDAO.retrieve(session.getUser())
            .switchIfEmpty(provisionUser(session.getUser()))
            .flatMap(openPaaSUser -> domainDAO.retrieve(session.getUser().getDomainPart().get())
                .flatMap(openPaaSDomain -> configurationResolver.resolveAll(session)
                    .map(conf -> new ProfileResponseDTO(openPaaSUser, openPaaSDomain.id(), conf.asJson()))))
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> response.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cache-Control", "max-age=60, public")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<OpenPaaSUser> provisionUser(Username username) {
        return Mono.from(usersRepository.containsReactive(username))
            .flatMap(exists -> {
                if (exists) {
                    return userDAO.add(username);
                }
                return Mono.empty();
            });
    }
}
