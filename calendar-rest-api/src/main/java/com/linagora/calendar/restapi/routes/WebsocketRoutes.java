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

import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;

import org.apache.james.events.EventBus;
import org.apache.james.events.Registration;
import org.apache.james.events.RegistrationKey;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.CalendarStateChangeListener;
import com.linagora.calendar.UsernameRegistrationKey;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitFailureHandler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class WebsocketRoutes extends CalendarRoute {

    private final EventBus eventBus;

    @Inject
    public WebsocketRoutes(Authenticator authenticator, MetricFactory metricFactory, EventBus eventBus) {
        super(authenticator, metricFactory);
        this.eventBus = eventBus;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/ws");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();

        AtomicReference<Registration> registrationRef = new AtomicReference<>();

        return response.sendWebsocket((in, out) -> {
            Flux<String> inbound = in.receive()
                .asString()
                .flatMap(msg -> handleClientMessage(msg, outbound, registrationRef, session));
            Flux<String> outboundFlux = outbound.asFlux();
            Flux<String> merged = Flux.merge(inbound, outboundFlux);
            return out.sendString(merged)
                .then()
                .publishOn(Schedulers.boundedElastic())
                .doFinally(signal -> cleanupWebsocket(registrationRef, outbound).subscribe());
        });
    }

    private Mono<String> handleClientMessage(String message,
                                             Sinks.Many<String> outbound,
                                             AtomicReference<Registration> registrationRef,
                                             MailboxSession session) {
        return switch (message) {
            case "enablePush" -> registerListenerForUser(outbound, session)
                .doOnNext(registrationRef::set)
                .thenReturn("{\"status\": \"push-enabled\"}");

            case "disablePush" -> Mono.justOrEmpty(registrationRef.get())
                .flatMap(reg -> Mono.from(reg.unregister()))
                .thenReturn("{\"status\": \"push-disabled\"}");

            default -> Mono.just("{\"error\":\"unknown-command\"}");
        };
    }

    private Mono<Registration> registerListenerForUser(Sinks.Many<String> outbound,
                                                       MailboxSession session) {
        CalendarStateChangeListener listener = new CalendarStateChangeListener(outbound);
        RegistrationKey key = new UsernameRegistrationKey(session.getUser());
        return Mono.from(eventBus.register(listener, key));
    }

    private Mono<Void> cleanupWebsocket(AtomicReference<Registration> registrationRef,
                                        Sinks.Many<String> outbound) {
        outbound.emitComplete(EmitFailureHandler.FAIL_FAST);
        return Mono.justOrEmpty(registrationRef.get())
            .flatMap(reg -> Mono.from(reg.unregister()))
            .doOnTerminate(() -> registrationRef.set(null));
    }
}
