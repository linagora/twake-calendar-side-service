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

import static com.linagora.calendar.dav.CardDavClient.LIMIT_PARAM;
import static com.linagora.calendar.storage.AddressBookURL.ADDRESS_BOOK_URL_PATH_PREFIX;
import static com.linagora.calendar.storage.CalendarURL.CALENDAR_URL_PATH_PREFIX;

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
import com.linagora.calendar.dav.AddressBookNotFoundException;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarNotFoundException;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.AddressBookURLRegistrationKey;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.calendar.storage.UsernameRegistrationKey;
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
    private final CardDavClient cardDavClient;
    private final Set<ClientContext> connectedClients = ConcurrentHashMap.newKeySet();

    @Inject
    protected WebsocketRoute(TicketAuthenticationStrategy ticketAuthenticationStrategy,
                             MetricFactory metricFactory,
                             EventBus eventBus,
                             CalDavClient calDavClient,
                             CardDavClient cardDavClient,
                             PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        super(Authenticator.of(metricFactory, ticketAuthenticationStrategy), metricFactory);
        this.eventBus = eventBus;
        this.calDavClient = calDavClient;
        this.cardDavClient = cardDavClient;

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
        Sinks.Many<WebsocketMessage> outboundSink = Sinks.many().unicast().onBackpressureBuffer();
        ClientContext context = ClientContext.create(outboundSink, session);

        return response.sendWebsocket((in, out) -> {
            connectedClients.add(context);
            Flux<WebSocketFrame> inboundFlux = in.aggregateFrames()
                .receiveFrames()
                .filter(frame -> frame instanceof TextWebSocketFrame)
                .flatMap(message -> handleClientMessage(((TextWebSocketFrame) message).text(), context)
                    .map(TextWebSocketFrame::new));

            Flux<WebSocketFrame> outboundFlux = outboundSink.asFlux().map(Throwing.function(WebsocketMessage::asWebSocketFrame));

            return out.sendObject(Flux.merge(outboundFlux, inboundFlux, pingInterval()))
                .then()
                .doFinally(signal -> cleanupWebsocketSession(context));
        });
    }

    private Mono<String> handleClientMessage(String message,
                                             ClientContext context) {
        return Mono.fromCallable(() -> deserializeClientRequest(message))
            .flatMap(request -> switch (request) {
                case EnableDisplayNotificationRequest enableRequest -> handleEnableDisplayNotificationRequest(enableRequest, context);
                case ClientSubscribeRequest subscribeRequest -> {
                    subscribeRequest.validate();
                    yield handleSubscribeRequest(subscribeRequest, context)
                        .map(ClientSubscribeResult::serialize);
                }
                default -> throw new IllegalArgumentException("Unexpected value: " + request);
            })
            .onErrorResume(error -> {
                LOGGER.warn("Error when handle client message: {} ", message, error);
                if (error instanceof CalendarSubscribeException) {
                    return Mono.just("{\"error\":\"%s\"}".formatted(error.getMessage()));
                }
                return Mono.just("{\"error\":\"internal-error\"}");
            });
    }

    private ClientRequest deserializeClientRequest(String message) throws JsonProcessingException {
        JsonNode jsonNode = MAPPER.readTree(message);
        if (jsonNode.has(EnableDisplayNotificationRequest.ENABLE_DISPLAY_NOTIFICATION_PROPERTY)) {
            return MAPPER.treeToValue(jsonNode, EnableDisplayNotificationRequest.class);
        }
        return ClientSubscribeRequest.deserialize(jsonNode);
    }

    private Mono<String> handleEnableDisplayNotificationRequest(EnableDisplayNotificationRequest request,
                                                                ClientContext context) {
        Username username = context.session().getUser();
        AlarmSubscriptionKey alarmSubscriptionKey = new AlarmSubscriptionKey(username);

        if (request.enableDisplayNotification()) {
            return Mono.justOrEmpty(context.subscriptionMap().get(alarmSubscriptionKey))
                .map(existing -> EnableDisplayNotificationResponse.ENABLED.serialize())
                .switchIfEmpty(Mono.defer(() -> {
                    WebSocketNotificationListener listener = new WebSocketNotificationListener(context.outbound(), calDavClient, username);
                    UsernameRegistrationKey registrationKey = new UsernameRegistrationKey(username);
                    return Mono.from(eventBus.register(listener, registrationKey))
                        .doOnNext(registration -> context.subscriptionMap().put(alarmSubscriptionKey, registration))
                        .thenReturn(EnableDisplayNotificationResponse.ENABLED.serialize());
                }));
        } else {
            return context.unregister(alarmSubscriptionKey)
                .thenReturn(EnableDisplayNotificationResponse.DISABLED.serialize());
        }
    }

    private Mono<ClientSubscribeResult> handleSubscribeRequest(ClientSubscribeRequest subscribeRequest,
                                                               ClientContext context) {
        Mono<ClientSubscribeResult> registrationResult = Flux.fromIterable(subscribeRequest.register())
            .flatMap(subscriptionKey -> registerSubscription(subscriptionKey, context)
                .thenReturn(ClientSubscribeResult.registered(subscriptionKey))
                .onErrorResume(error -> {
                    LOGGER.error("Error registering {}", subscriptionKey.asString(), error);
                    return Mono.just(ClientSubscribeResult.notRegistered(subscriptionKey, error));
                }))
            .reduce(ClientSubscribeResult::merge)
            .defaultIfEmpty(ClientSubscribeResult.empty());

        Mono<ClientSubscribeResult> unregistrationResult = Flux.fromIterable(subscribeRequest.unregister())
            .flatMap(subscriptionKey -> context.unregister(subscriptionKey)
                .thenReturn(ClientSubscribeResult.unregistered(subscriptionKey))
                .onErrorResume(error -> {
                    LOGGER.error("Error unRegistering {}", subscriptionKey.asString(), error);
                    return Mono.just(ClientSubscribeResult.notUnregistered(subscriptionKey, error));
                }))
            .reduce(ClientSubscribeResult::merge)
            .defaultIfEmpty(ClientSubscribeResult.empty());

        return Mono.zip(registrationResult, unregistrationResult, ClientSubscribeResult::merge);
    }

    private Mono<Registration> registerSubscription(SubscriptionKey subscriptionKey,
                                                    ClientContext context) {
        return switch (subscriptionKey) {
            case CalendarSubscriptionKey calendarKey -> registerCalendar(calendarKey, context);
            case AddressBookSubscriptionKey addressBookKey -> registerAddressBook(addressBookKey, context);
            default -> Mono.error(new CalendarSubscribeException("Unsupported subscription key type"));
        };
    }

    private Mono<Registration> registerAddressBook(AddressBookSubscriptionKey subscriptionKey,
                                                   ClientContext context) {
        Username username = context.session().getUser();
        WebSocketNotificationListener listener = new WebSocketNotificationListener(context.outbound(), calDavClient, username);
        AddressBookURL addressBookURL = subscriptionKey.addressBookURL();
        RegistrationKey registrationKey = new AddressBookURLRegistrationKey(addressBookURL);
        Mono<Void> accessValidation = validateAccessRights(username, addressBookURL);

        return doRegisterSubscription(subscriptionKey, registrationKey, listener, accessValidation, context);
    }

    private Mono<Registration> registerCalendar(CalendarSubscriptionKey subscriptionKey,
                                                ClientContext context) {
        Username username = context.session().getUser();
        WebSocketNotificationListener listener = new WebSocketNotificationListener(context.outbound(), calDavClient, username);
        CalendarURL calendarURL = subscriptionKey.calendarURL();
        RegistrationKey registrationKey = new CalendarURLRegistrationKey(calendarURL);
        Mono<Void> accessValidation = validateAccessRights(username, calendarURL);

        return doRegisterSubscription(subscriptionKey, registrationKey, listener, accessValidation, context);
    }

    private Mono<Registration> doRegisterSubscription(SubscriptionKey subscriptionKey,
                                                      RegistrationKey registrationKey,
                                                      WebSocketNotificationListener listener,
                                                      Mono<Void> accessValidation,
                                                      ClientContext context) {

        return Mono.justOrEmpty(context.subscriptionMap().get(subscriptionKey))
            .switchIfEmpty(Mono.defer(() ->
                accessValidation
                    .then(Mono.from(eventBus.register(listener, registrationKey)))
                    .flatMap(registration -> {
                        Registration old = context.subscriptionMap().putIfAbsent(subscriptionKey, registration);
                        if (old != null) {
                            return Mono.from(registration.unregister())
                                .thenReturn(old);
                        }
                        return Mono.just(registration);
                    })
            ));
    }

    private Mono<Void> validateAccessRights(Username user, CalendarURL url) {
        return calDavClient.retrieveSyncToken(user, url)
            .switchIfEmpty(Mono.error(ForbiddenSubscribeException::new))
            .then();
    }

    private Mono<Void> validateAccessRights(Username user, AddressBookURL addressBookURL) {
        return cardDavClient
            .exportAddressBook(user, addressBookURL, Map.of(LIMIT_PARAM, "1"))
            .then()
            .onErrorMap(CardDavClient.CardDavExportException.class, exportException ->
                switch (exportException.statusCode()) {
                    case 403 -> new ForbiddenSubscribeException();
                    case 404 -> new AddressBookNotFoundException(addressBookURL);
                    default -> exportException;
                });
    }

    private Flux<WebSocketFrame> pingInterval() {
        return Flux.interval(websocketPingInterval)
            .map(any -> new PingWebSocketFrame());
    }

    private void cleanupWebsocketSession(ClientContext context) {
        context.clean();
        connectedClients.remove(context);
    }

    sealed interface SubscriptionKey permits CalendarSubscriptionKey, AddressBookSubscriptionKey, AlarmSubscriptionKey {

        String asString();
    }

    record CalendarSubscriptionKey(CalendarURL calendarURL) implements SubscriptionKey {
        @Override
        public String asString() {
            return calendarURL().asUri().toASCIIString();
        }
    }

    record AddressBookSubscriptionKey(AddressBookURL addressBookURL) implements SubscriptionKey {
        @Override
        public String asString() {
            return addressBookURL.asUri().toASCIIString();
        }
    }

    record AlarmSubscriptionKey(Username username) implements SubscriptionKey {
        @Override
        public String asString() {
            return "alarm:" + username.asString();
        }
    }

    private record ClientContext(Sinks.Many<WebsocketMessage> outbound,
                                 Map<SubscriptionKey, Registration> subscriptionMap,
                                 MailboxSession session) {

        static ClientContext create(Sinks.Many<WebsocketMessage> outbound, MailboxSession session) {
            return new ClientContext(outbound, new ConcurrentHashMap<>(), session);
        }

        Mono<Void> unregister(SubscriptionKey subscriptionKey) {
            Registration registration = subscriptionMap.remove(subscriptionKey);
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

    interface ClientRequest {
    }

    record EnableDisplayNotificationRequest(boolean enableDisplayNotification) implements ClientRequest {
        static final String ENABLE_DISPLAY_NOTIFICATION_PROPERTY = "enableDisplayNotification";
    }

    record EnableDisplayNotificationResponse(boolean displayNotificationEnabled) {
        static final EnableDisplayNotificationResponse ENABLED = new EnableDisplayNotificationResponse(true);
        static final EnableDisplayNotificationResponse DISABLED = new EnableDisplayNotificationResponse(false);

        String serialize() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    record ClientSubscribeRequest(Set<SubscriptionKey> register,
                                  Set<SubscriptionKey> unregister) implements ClientRequest {

        static final String REGISTER_PROPERTY = "register";
        static final String UNREGISTER_PROPERTY = "unregister";

        static ClientSubscribeRequest deserialize(JsonNode root) {
            try {
                Set<SubscriptionKey> register = parseSubscriptionKeyArray(root.get(REGISTER_PROPERTY));
                Set<SubscriptionKey> unregister = parseSubscriptionKeyArray(root.get(UNREGISTER_PROPERTY));
                return new ClientSubscribeRequest(register, unregister);
            } catch (Exception exception) {
                throw new CalendarSubscribeException("Invalid Request", exception);
            }
        }

        static Set<SubscriptionKey> parseSubscriptionKeyArray(JsonNode node) {
            if (node == null || !node.isArray()) {
                return Set.of();
            }

            return StreamSupport.stream(node.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .map(ClientSubscribeRequest::toSubscriptionKey)
                .collect(Collectors.toSet());
        }

        static SubscriptionKey toSubscriptionKey(String raw) {
            if (Strings.CS.startsWith(raw, CALENDAR_URL_PATH_PREFIX)) {
                CalendarURL calendarURL = CalendarURL.parse(raw);
                return new CalendarSubscriptionKey(calendarURL);
            }

            if (Strings.CS.startsWith(raw, ADDRESS_BOOK_URL_PATH_PREFIX)) {
                AddressBookURL addressBookURL = AddressBookURL.parse(raw);
                return new AddressBookSubscriptionKey(addressBookURL);
            }

            throw new CalendarSubscribeException("Unsupported subscription resource: " + raw);
        }

        void validate() {
            Set<SubscriptionKey> intersection = Sets.intersection(register, unregister);
            if (!intersection.isEmpty()) {
                throw new CalendarSubscribeException("register and unregister cannot contain duplicated entries");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ClientSubscribeResult(@JsonProperty("registered")
                                 @JsonSerialize(contentUsing = SubscriptionKeySerializer.class)
                                 List<SubscriptionKey> registered,
                                 @JsonProperty("unregistered")
                                 @JsonSerialize(contentUsing = SubscriptionKeySerializer.class)
                                 List<SubscriptionKey> unregistered,
                                 @JsonProperty("notRegistered")
                                 @JsonSerialize(keyUsing = SubscriptionKeyMapSerializer.class)
                                 Map<SubscriptionKey, String> notRegistered,
                                 @JsonProperty("notUnregistered")
                                 @JsonSerialize(keyUsing = SubscriptionKeyMapSerializer.class)
                                 Map<SubscriptionKey, String> notUnregistered) {

        static class SubscriptionKeySerializer extends JsonSerializer<SubscriptionKey> {
            @Override
            public void serialize(SubscriptionKey value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.asString());
            }
        }

        static class SubscriptionKeyMapSerializer extends JsonSerializer<SubscriptionKey> {
            @Override
            public void serialize(SubscriptionKey value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeFieldName(value.asString());
            }
        }

        static ClientSubscribeResult registered(SubscriptionKey subscriptionKey) {
            return new ClientSubscribeResult(List.of(subscriptionKey), List.of(), Map.of(), Map.of());
        }

        static ClientSubscribeResult unregistered(SubscriptionKey subscriptionKey) {
            return new ClientSubscribeResult(List.of(), List.of(subscriptionKey), Map.of(), Map.of());
        }

        static ClientSubscribeResult notRegistered(SubscriptionKey subscriptionKey, Throwable reason) {
            String messageReason = switch (reason) {
                case CalendarNotFoundException ignore -> "NotFound";
                case AddressBookNotFoundException ignore -> "NotFound";
                case ForbiddenSubscribeException ignore -> "Forbidden";
                default -> "InternalError";
            };
            return new ClientSubscribeResult(List.of(), List.of(), Map.of(subscriptionKey, messageReason), Map.of());
        }

        static ClientSubscribeResult notUnregistered(SubscriptionKey subscriptionKey, Throwable reason) {
            String message = Optional.ofNullable(reason.getMessage())
                .orElse(reason.getClass().getSimpleName());
            return new ClientSubscribeResult(List.of(), List.of(), Map.of(), Map.of(subscriptionKey, message));
        }

        static ClientSubscribeResult empty() {
            return new ClientSubscribeResult(List.of(), List.of(), Map.of(), Map.of());
        }

        ClientSubscribeResult merge(ClientSubscribeResult other) {
            return new ClientSubscribeResult(
                Stream.concat(this.registered.stream(), other.registered.stream()).toList(),
                Stream.concat(this.unregistered.stream(), other.unregistered.stream()).toList(),
                ImmutableMap.<SubscriptionKey, String>builder()
                    .putAll(this.notRegistered)
                    .putAll(other.notRegistered)
                    .build(),
                ImmutableMap.<SubscriptionKey, String>builder()
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

    public interface WebsocketMessage {
        WebSocketFrame asWebSocketFrame() throws Exception;
    }

}
