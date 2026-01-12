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

import static com.linagora.calendar.restapi.routes.AvatarRoute.extractEmail;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.user.api.UsersRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class UsersRoute extends CalendarRoute {
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new Jdk8Module());
    private static final String EMPTY_JSON_ARRAY = "[]";

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final UsersRepository usersRepository;
    private final SettingsBasedResolver settingsResolver;
    private final RestApiConfiguration restApiConfiguration;

    @Inject
    public UsersRoute(Authenticator authenticator,
                      MetricFactory metricFactory,
                      OpenPaaSUserDAO userDAO,
                      OpenPaaSDomainDAO domainDAO,
                      UsersRepository usersRepository,
                      @Named("language_timezone") SettingsBasedResolver settingsResolver,
                      RestApiConfiguration restApiConfiguration) {
        super(authenticator, metricFactory);
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.usersRepository = usersRepository;
        this.settingsResolver = settingsResolver;
        this.restApiConfiguration = restApiConfiguration;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/users");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        Username queryUsername = Username.of(extractEmail(request));
        if (crossDomainAccess(session, queryUsername.getDomainPart().get())) {
            return respondWithEmptyResult(response);
        }
        return userDAO.retrieve(queryUsername)
            .switchIfEmpty(provisionUser(queryUsername))
            .flatMap(user -> Mono.zip(domainDAO.retrieve(user.username().getDomainPart().get()),
                    settingsResolver.resolveOrDefault(user.username()))
                .map(tuple -> {
                    OpenPaaSDomain domain = tuple.getT1();
                    SettingsBasedResolver.ResolvedSettings settings = tuple.getT2();
                    String timezone = settings.zoneId().toString();
                    return new UserRoute.ResponseDTO(user, domain.id(), timezone);
                }))
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsString))
            .map(s -> "[" + s + "]")
            .switchIfEmpty(Mono.just(EMPTY_JSON_ARRAY))
            .flatMap(bytes -> response.status(HttpResponseStatus.OK)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cache-Control", "max-age=60, public")
                .sendString(Mono.just(bytes))
                .then());
    }

    private Mono<Void> respondWithEmptyResult(HttpServerResponse response) {
        return response.status(HttpResponseStatus.OK)
            .sendString(Mono.just(EMPTY_JSON_ARRAY))
            .then();
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
