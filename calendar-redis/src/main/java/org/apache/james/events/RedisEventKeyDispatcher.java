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

package org.apache.james.events;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.apache.james.events.RoutingKeyConverter.RoutingKey;
import org.apache.james.util.MDCBuilder;
import org.apache.james.util.MDCStructuredLogger;
import org.apache.james.util.StructuredLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.reactive.RedisSetReactiveCommands;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

/**
 * This class is originally based on: {@link org.apache.james.events.TMailEventDispatcher}
 * The implementation has been adapted and simplified:
 * - Only KEY-based event dispatching is supported
 * - GROUP-based dispatching and RabbitMQ message flow are intentionally not implemented
 */
public class RedisEventKeyDispatcher {
    public static final Predicate<? super Throwable> REDIS_ERROR_PREDICATE = throwable -> throwable instanceof RedisException || throwable instanceof TimeoutException;
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisEventKeyDispatcher.class);

    private final EventSerializer eventSerializer;
    private final LocalListenerRegistry localListenerRegistry;
    private final ListenerExecutor listenerExecutor;
    private final RedisPubSubReactiveCommands<String, String> redisPublisher;
    private final RedisSetReactiveCommands<String, String> redisSetReactiveCommands;
    private final EventBusId eventBusId;
    private final RedisEventBusConfiguration redisEventBusConfiguration;

    RedisEventKeyDispatcher(EventBusId eventBusId, EventSerializer eventSerializer,
                            LocalListenerRegistry localListenerRegistry,
                            ListenerExecutor listenerExecutor,
                            RedisPubSubReactiveCommands<String, String> redisPubSubReactiveCommands,
                            RedisSetReactiveCommands<String, String> redisSetReactiveCommands,
                            RedisEventBusConfiguration redisEventBusConfiguration) {
        this.eventSerializer = eventSerializer;
        this.localListenerRegistry = localListenerRegistry;
        this.listenerExecutor = listenerExecutor;
        this.redisPublisher = redisPubSubReactiveCommands;
        this.redisSetReactiveCommands = redisSetReactiveCommands;
        this.eventBusId = eventBusId;
        this.redisEventBusConfiguration = redisEventBusConfiguration;
    }

    Mono<Void> dispatch(Event event, Set<RegistrationKey> keys) {
        return Flux
            .concat(dispatchToLocalListeners(event, keys),
                dispatchToRemoteListeners(event, keys))
            .doOnError(throwable -> LOGGER.error("error while dispatching event", throwable))
            .then();
    }

    Mono<Void> dispatch(Collection<EventBus.EventWithRegistrationKey> events) {
        return Flux
            .concat(dispatchToLocalListeners(events),
                dispatchToRemoteListeners(events))
            .doOnError(throwable -> LOGGER.error("error while dispatching event", throwable))
            .then();
    }

    private Mono<Void> dispatchToLocalListeners(Collection<EventBus.EventWithRegistrationKey> events) {
        return Flux.fromIterable(events)
            .concatMap(e -> dispatchToLocalListeners(e.event(), e.keys()))
            .then();
    }

    private Mono<Void> dispatchToLocalListeners(Event event, Set<RegistrationKey> keys) {
        return Flux.fromIterable(keys)
            .flatMap(key -> Flux.fromIterable(localListenerRegistry.getLocalListeners(key))
                .map(listener -> Tuples.of(key, listener)), EventBus.EXECUTION_RATE)
            .filter(pair -> pair.getT2().getExecutionMode() == EventListener.ExecutionMode.SYNCHRONOUS)
            .flatMap(pair -> executeListener(event, pair.getT2(), pair.getT1()), EventBus.EXECUTION_RATE)
            .then();
    }

    private Mono<Void> dispatchToRemoteListeners(Collection<EventBus.EventWithRegistrationKey> events) {
        ImmutableList<Event> underlyingEvents = events.stream()
            .map(EventBus.EventWithRegistrationKey::event)
            .collect(ImmutableList.toImmutableList());

        ImmutableSet<RegistrationKey> keys = events.stream()
            .flatMap(event -> event.keys().stream())
            .collect(ImmutableSet.toImmutableSet());

        return Mono.fromCallable(() -> eventSerializer.toJson(underlyingEvents))
            .flatMap(serializedEvent -> remoteKeysDispatch(serializedEvent, keys))
            .then();
    }

    private Mono<Void> executeListener(Event event, EventListener.ReactiveEventListener listener, RegistrationKey registrationKey) {
        return listenerExecutor.execute(listener,
                MDCBuilder.create()
                    .addToContext(EventBus.StructuredLoggingFields.REGISTRATION_KEY, registrationKey.asString()),
                event)
            .onErrorResume(e -> {
                structuredLogger(event, ImmutableSet.of(registrationKey))
                    .log(logger -> logger.error("Exception happens when dispatching event", e));
                return Mono.empty();
            });
    }

    private StructuredLogger structuredLogger(Event event, Set<RegistrationKey> keys) {
        return MDCStructuredLogger.forLogger(LOGGER)
            .field(EventBus.StructuredLoggingFields.EVENT_ID, event.getEventId().getId().toString())
            .field(EventBus.StructuredLoggingFields.EVENT_CLASS, event.getClass().getCanonicalName())
            .field(EventBus.StructuredLoggingFields.USER, event.getUsername().asString())
            .field(EventBus.StructuredLoggingFields.REGISTRATION_KEYS, keys.toString());
    }

    private Mono<Void> dispatchToRemoteListeners(Event event, Set<RegistrationKey> keys) {
        return Mono.fromCallable(() -> serializeEvent(event))
            .flatMap(serializedEvent -> remoteKeysDispatch(eventSerializer.toJson(event), keys))
            .then();
    }

    private Mono<Void> remoteKeysDispatch(String eventAsJson, Set<RegistrationKey> keys) {
        return remoteDispatch(eventAsJson,
            keys.stream()
                .map(RoutingKey::of)
                .collect(ImmutableList.toImmutableList()));
    }

    private Mono<Void> remoteDispatch(String eventAsJson, Collection<RoutingKey> routingKeys) {
        if (routingKeys.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(routingKeys)
            .flatMap(routingKey -> getTargetChannels(routingKey)
                .flatMap(channel -> redisPublisher.publish(channel, KeyChannelMessage.from(eventBusId, routingKey, eventAsJson).serialize()))
                .timeout(redisEventBusConfiguration.durationTimeout())
                .onErrorResume(REDIS_ERROR_PREDICATE.and(e -> redisEventBusConfiguration.failureIgnore()), e -> {
                    LOGGER.warn("Error while dispatching event to remote listeners", e);
                    return Flux.empty();
                })
                .then())
            .then();
    }

    private Flux<String> getTargetChannels(RoutingKey routingKey) {
        return redisSetReactiveCommands.smembers(routingKey.asString());
    }

    private byte[] serializeEvent(Event event) {
        return eventSerializer.toJsonBytes(event);
    }
}
