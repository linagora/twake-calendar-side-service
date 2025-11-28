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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;
import org.apache.james.events.EventBus;
import org.apache.james.events.Registration;
import org.apache.james.events.RegistrationKey;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.DurationParser;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarNotFoundException;
import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.tmail.james.jmap.ticket.TicketAuthenticationStrategy;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitFailureHandler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class WebsocketRoute extends CalendarRoute {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger LOGGER = LoggerFactory.getLogger(WebsocketRoute.class);
    private static final String WEBSOCKET_PING_INTERVAL_PROPERTY = "websocket.ping.interval";
    private static final Duration WEBSOCKET_PING_INTERVAL_DEFAULT = Duration.ofSeconds(5);

    private final Duration websocketPingInterval;
    private final EventBus eventBus;
    private final CalDavClient calDavClient;
    private final Set<ClientContext> connectedUsers = ConcurrentHashMap.newKeySet();

    @Inject
    protected WebsocketRoute(TicketAuthenticationStrategy ticketAuthenticationStrategy,
                             MetricFactory metricFactory,
                             EventBus eventBus,
                             CalDavClient calDavClient,
                             PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        super(Authenticator.of(metricFactory, ticketAuthenticationStrategy), metricFactory);
        this.eventBus = eventBus;
        this.calDavClient = calDavClient;

        Configuration configuration = propertiesProvider.getConfiguration("configuration");
        this.websocketPingInterval = Optional.ofNullable(configuration.getString(WEBSOCKET_PING_INTERVAL_PROPERTY))
            .map(rawValue -> DurationParser.parse(rawValue, ChronoUnit.SECONDS))
            .map(duration -> {
                Preconditions.checkArgument(!duration.isZero() && !duration.isNegative(), "`" + WEBSOCKET_PING_INTERVAL_PROPERTY + "` must be positive");
                return duration;
            })
            .orElse(WEBSOCKET_PING_INTERVAL_DEFAULT);
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/ws");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        Sinks.Many<CalendarChangeMessage> outboundSink = Sinks.many().unicast().onBackpressureBuffer();
        ClientContext context = ClientContext.create(outboundSink, session);

        return response.sendWebsocket((in, out) -> {
            connectedUsers.add(context);
            Flux<WebSocketFrame> inboundFlux = in.aggregateFrames()
                .receiveFrames()
                .filter(frame -> frame instanceof TextWebSocketFrame)
                .flatMap(message -> handleClientMessage(((TextWebSocketFrame) message).text(), context)
                    .map(TextWebSocketFrame::new));

            Flux<WebSocketFrame> outboundFlux = outboundSink.asFlux().map(Throwing.function(CalendarChangeMessage::asWebSocketFrame));

            return out.sendObject(Flux.merge(outboundFlux, inboundFlux, pingInterval()))
                .then()
                .doFinally(signal -> cleanupWebsocketSession(context));
        });
    }

    private Mono<String> handleClientMessage(String message,
                                             ClientContext context) {
        return Mono.fromCallable(() -> ClientSubscribeRequest.deserialize(message))
            .doOnNext(ClientSubscribeRequest::validate)
            .flatMap(request -> handleSubscribeRequest(request, context)
                .map(ClientSubscribeResult::serialize))
            .onErrorResume(error -> {
                LOGGER.warn("Error when handle client message: {} ", message, error);
                if (error instanceof CalendarSubscribeException) {
                    return Mono.just("{\"error\":\"%s\"}".formatted(error.getMessage()));
                }
                return Mono.just("{\"error\":\"internal-error\"}");
            });
    }

    private Mono<ClientSubscribeResult> handleSubscribeRequest(ClientSubscribeRequest subscribeRequest,
                                                               ClientContext context) {
        Mono<ClientSubscribeResult> registrationResult = Flux.fromIterable(subscribeRequest.register())
            .flatMap(calendarUrl -> registerCalendar(calendarUrl, context)
                .thenReturn(ClientSubscribeResult.registered(calendarUrl))
                .onErrorResume(error -> {
                    LOGGER.error("Error registering {}", calendarUrl, error);
                    return Mono.just(ClientSubscribeResult.notRegistered(calendarUrl, error));
                }))
            .reduce(ClientSubscribeResult::merge)
            .defaultIfEmpty(ClientSubscribeResult.empty());

        Mono<ClientSubscribeResult> unregistrationResult = Flux.fromIterable(subscribeRequest.unregister())
            .flatMap(calendarUrl -> context.unregister(calendarUrl)
                .thenReturn(ClientSubscribeResult.unregistered(calendarUrl))
                .onErrorResume(error -> {
                    LOGGER.error("Error unRegistering {}", calendarUrl, error);
                    return Mono.just(ClientSubscribeResult.notUnregistered(calendarUrl, error));
                }))
            .reduce(ClientSubscribeResult::merge)
            .defaultIfEmpty(ClientSubscribeResult.empty());

        return Mono.zip(registrationResult, unregistrationResult, ClientSubscribeResult::merge);
    }

    private Mono<Registration> registerCalendar(CalendarURL calendarUrl,
                                                ClientContext context) {
        Username username = context.session().getUser();
        return Mono.justOrEmpty(context.subscriptionMap().get(calendarUrl))
            .switchIfEmpty(Mono.defer(() -> {
                CalendarStateChangeListener listener = new CalendarStateChangeListener(context.outbound(), calDavClient, username);
                RegistrationKey registrationKey = new CalendarURLRegistrationKey(calendarUrl);

                return validateAccessRights(username, calendarUrl)
                    .then(Mono.from(eventBus.register(listener, registrationKey)))
                    .flatMap(registration -> {
                        Registration old = context.subscriptionMap().putIfAbsent(calendarUrl, registration);
                        if (old != null) {
                            return Mono.from(registration.unregister())
                                .thenReturn(old);
                        }
                        return Mono.just(registration);
                    });
            }));
    }

    private Mono<Void> validateAccessRights(Username user, CalendarURL url) {
        return calDavClient.retrieveSyncToken(user, url)
            .switchIfEmpty(Mono.error(ForbiddenSubscribeException::new))
            .then();
    }

    private Flux<WebSocketFrame> pingInterval() {
        return Flux.interval(websocketPingInterval)
            .map(any -> new PingWebSocketFrame());
    }

    private void cleanupWebsocketSession(ClientContext context) {
        context.clean();
        connectedUsers.remove(context);
    }

    private record ClientContext(Sinks.Many<CalendarChangeMessage> outbound,
                                 Map<CalendarURL, Registration> subscriptionMap,
                                 MailboxSession session) {

        static ClientContext create(Sinks.Many<CalendarChangeMessage> outbound, MailboxSession session) {
            return new ClientContext(outbound, new ConcurrentHashMap<>(), session);
        }

        Mono<Void> unregister(CalendarURL calendarUrl) {
            Registration registration = subscriptionMap.remove(calendarUrl);
            if (registration != null) {
                return Mono.from(registration.unregister());
            }
            return Mono.empty();
        }

        void clean() {
            Flux.fromIterable(subscriptionMap.values())
                .flatMap(Registration::unregister)
                .doFinally(signal -> {
                    subscriptionMap.clear();
                    outbound.emitComplete(EmitFailureHandler.FAIL_FAST);
                })
                .onErrorResume(error -> {
                    LOGGER.warn("Error during WebSocket cleanup", error);
                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        }
    }

    public static class CalendarSubscribeException extends RuntimeException {

        public CalendarSubscribeException(String message) {
            super(message);
        }

        public CalendarSubscribeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ForbiddenSubscribeException extends RuntimeException {
    }

    record ClientSubscribeRequest(Set<CalendarURL> register,
                                  Set<CalendarURL> unregister) {

        static final String REGISTER_PROPERTY = "register";
        static final String UNREGISTER_PROPERTY = "unregister";

        static ClientSubscribeRequest deserialize(String message) {
            try {
                JsonNode root = MAPPER.readTree(message);
                Set<CalendarURL> registerList = parseCalendarURLArray(root.get(REGISTER_PROPERTY));
                Set<CalendarURL> unregisterList = parseCalendarURLArray(root.get(UNREGISTER_PROPERTY));
                return new ClientSubscribeRequest(registerList, unregisterList);
            } catch (Exception exception) {
                throw new CalendarSubscribeException("Invalid Request", exception);
            }
        }

        static Set<CalendarURL> parseCalendarURLArray(JsonNode node) {
            if (node == null || !node.isArray()) {
                return Set.of();
            }
            return StreamSupport.stream(node.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(jsonNode -> CalendarURL.deserialize(Strings.CS.remove(jsonNode.asText(), CalendarURL.CALENDAR_URL_PATH_PREFIX)))
                .collect(Collectors.toSet());
        }

        void validate() {
            Set<CalendarURL> intersection = Sets.intersection(register, unregister);
            if (!intersection.isEmpty()) {
                throw new CalendarSubscribeException("register and unregister cannot contain duplicated entries: "
                    + intersection.stream().map(url -> url.asUri().toASCIIString()).collect(Collectors.joining(", ")));
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ClientSubscribeResult(@JsonProperty("registered")
                                 @JsonSerialize(contentUsing = CalendarURLSerializer.class)
                                 List<CalendarURL> registered,
                                 @JsonProperty("unregistered")
                                 @JsonSerialize(contentUsing = CalendarURLSerializer.class)
                                 List<CalendarURL> unregistered,
                                 @JsonProperty("notRegistered")
                                 @JsonSerialize(keyUsing = CalendarURLKeySerializer.class)
                                 Map<CalendarURL, String> notRegistered,
                                 @JsonProperty("notUnregistered")
                                 @JsonSerialize(keyUsing = CalendarURLKeySerializer.class)
                                 Map<CalendarURL, String> notUnregistered) {

        static class CalendarURLSerializer extends JsonSerializer<CalendarURL> {
            @Override
            public void serialize(CalendarURL value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.asUri().toASCIIString());
            }
        }

        static class CalendarURLKeySerializer extends JsonSerializer<CalendarURL> {
            @Override
            public void serialize(CalendarURL value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeFieldName(value.asUri().toASCIIString());
            }
        }

        static ClientSubscribeResult registered(CalendarURL url) {
            return new ClientSubscribeResult(List.of(url), List.of(), Map.of(), Map.of());
        }

        static ClientSubscribeResult unregistered(CalendarURL url) {
            return new ClientSubscribeResult(List.of(), List.of(url), Map.of(), Map.of());
        }

        static ClientSubscribeResult notRegistered(CalendarURL url, Throwable reason) {
            String messageReason = switch (reason) {
                case CalendarNotFoundException ignore -> "NotFound";
                case ForbiddenSubscribeException ignore -> "Forbidden";
                default -> "InternalError";
            };
            return new ClientSubscribeResult(List.of(), List.of(), Map.of(url, messageReason), Map.of());
        }

        static ClientSubscribeResult notUnregistered(CalendarURL url, Throwable reason) {
            String message = Optional.ofNullable(reason.getMessage())
                .orElse(reason.getClass().getSimpleName());
            return new ClientSubscribeResult(List.of(), List.of(), Map.of(), Map.of(url, message));
        }

        static ClientSubscribeResult empty() {
            return new ClientSubscribeResult(List.of(), List.of(), Map.of(), Map.of());
        }

        ClientSubscribeResult merge(ClientSubscribeResult other) {
            return new ClientSubscribeResult(
                Stream.concat(this.registered.stream(), other.registered.stream()).toList(),
                Stream.concat(this.unregistered.stream(), other.unregistered.stream()).toList(),
                ImmutableMap.<CalendarURL, String>builder()
                    .putAll(this.notRegistered)
                    .putAll(other.notRegistered)
                    .build(),
                ImmutableMap.<CalendarURL, String>builder()
                    .putAll(this.notUnregistered)
                    .putAll(other.notUnregistered)
                    .build());
        }

        public String serialize() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (Exception e) {
                throw new CalendarSubscribeException("Failed to serialize response", e);
            }
        }
    }

    public record CalendarChangeMessage(CalendarURL calendarURL, SyncToken syncToken) {
        static final String SYNC_TOKEN_PROPERTY = "syncToken";

        public WebSocketFrame asWebSocketFrame() throws JsonProcessingException {
            return new TextWebSocketFrame(serialize());
        }

        public String serialize() throws JsonProcessingException {
            return MAPPER.writeValueAsString(
                MAPPER.createObjectNode().set(calendarURL.asUri().toASCIIString(),
                    MAPPER.createObjectNode().put(SYNC_TOKEN_PROPERTY, syncToken.value())));
        }
    }

}
