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

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.DomainSettings;
import com.linagora.calendar.storage.DomainSettingsResolver;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class DomainSettingsRoute extends CalendarRoute {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public record DomainSettingsDTO(@JsonProperty("userSearchMode") String userSearchMode,
                                    @JsonProperty("resourceSearchEnabled") boolean resourceSearchEnabled,
                                    @JsonProperty("defaultCalendarPublicVisibility") String defaultCalendarPublicVisibility) {
        static DomainSettingsDTO from(DomainSettings settings) {
            return new DomainSettingsDTO(
                settings.userSearchMode().orElse(DomainSettings.DEFAULT_USER_SEARCH_MODE).serialize(),
                settings.resourceSearchEnabled().orElse(DomainSettings.DEFAULT_RESOURCE_SEARCH_ENABLED),
                settings.defaultCalendarPublicVisibility().orElse(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY).serialize());
        }
    }

    private final DomainSettingsResolver domainSettingsResolver;

    @Inject
    public DomainSettingsRoute(Authenticator authenticator,
                               MetricFactory metricFactory,
                               DomainSettingsResolver domainSettingsResolver) {
        super(authenticator, metricFactory);
        this.domainSettingsResolver = domainSettingsResolver;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/domainSettings");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        Domain domain = session.getUser().getDomainPart()
            .orElseThrow(() -> new IllegalArgumentException("Authenticated user has no domain"));

        return domainSettingsResolver.resolve(domain)
            .map(DomainSettingsDTO::from)
            .map(Throwing.function(OBJECT_MAPPER::writeValueAsBytes))
            .flatMap(bytes -> response.status(200)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }
}
