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

import jakarta.annotation.PreDestroy;

import org.apache.james.events.EventListener.ReactiveEventListener;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import io.lettuce.core.api.reactive.RedisSetReactiveCommands;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * This class is originally based on: {@link org.apache.james.events.RabbitMQAndRedisEventBus}
 * However, this implementation has been simplified:
 * - Only KEY-based event delivery is supported
 * - GROUP registration and RabbitMQ dispatching are intentionally not implemented
 */
public class CalendarRedisEventBus implements EventBus, Startable {
    public static final EventBusName EVENT_BUS_NAME = new EventBusName("calendar-redis-eventbus");

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarRedisEventBus.class);
    private static final String NOT_RUNNING_ERROR_MESSAGE = "Event Bus is not running";

    private final EventSerializer eventSerializer;
    private final RoutingKeyConverter routingKeyConverter;
    private final RetryBackoffConfiguration retryBackoff;
    private final EventBusId eventBusId;
    private final ListenerExecutor listenerExecutor;
    private final MetricFactory metricFactory;
    private final RedisEventBusClientFactory redisEventBusClientFactory;
    private final RedisSetReactiveCommands<String, String> redisSetReactiveCommands;
    private final RedisPubSubReactiveCommands<String, String> redisPublisher;
    private final RedisEventBusConfiguration redisEventBusConfiguration;
    private final NamingStrategy namingStrategy;

    private volatile boolean isRunning;
    private volatile boolean isStopping;
    private RedisKeyRegistrationHandler keyRegistrationHandler;
    private LocalKeyListenerExecutor localKeyListenerExecutor;
    private RedisKeyEventDispatcher redisKeyEventDispatcher;

    public CalendarRedisEventBus(EventSerializer eventSerializer,
                                 RetryBackoffConfiguration retryBackoff,
                                 RoutingKeyConverter routingKeyConverter,
                                 MetricFactory metricFactory,
                                 EventBusId eventBusId,
                                 RedisEventBusClientFactory redisEventBusClientFactory,
                                 RedisEventBusConfiguration redisEventBusConfiguration) {
        this.eventSerializer = eventSerializer;
        this.routingKeyConverter = routingKeyConverter;
        this.retryBackoff = retryBackoff;
        this.metricFactory = metricFactory;
        this.eventBusId = eventBusId;
        this.listenerExecutor = new ListenerExecutor(metricFactory);
        this.redisEventBusClientFactory = redisEventBusClientFactory;
        this.redisSetReactiveCommands = redisEventBusClientFactory.createRedisSetCommand();
        this.redisPublisher = redisEventBusClientFactory.createRedisPubSubCommand();
        this.redisEventBusConfiguration = redisEventBusConfiguration;
        this.namingStrategy = new NamingStrategy(EVENT_BUS_NAME);
        this.isRunning = false;
        this.isStopping = false;
    }

    @Override
    public void start() {
        if (!isRunning && !isStopping) {
            LocalListenerRegistry localListenerRegistry = new LocalListenerRegistry();
            localKeyListenerExecutor = new LocalKeyListenerExecutor(localListenerRegistry, listenerExecutor);
            redisKeyEventDispatcher = new RedisKeyEventDispatcher(eventBusId, eventSerializer, redisPublisher, redisSetReactiveCommands, redisEventBusConfiguration);
            keyRegistrationHandler = new RedisKeyRegistrationHandler(namingStrategy, eventBusId, eventSerializer, routingKeyConverter,
                localListenerRegistry, listenerExecutor, retryBackoff, metricFactory, redisEventBusClientFactory, redisSetReactiveCommands, redisEventBusConfiguration);
            keyRegistrationHandler.start();
            isRunning = true;
        }
    }

    @PreDestroy
    public void stop() {
        if (isRunning && !isStopping) {
            isStopping = true;
            isRunning = false;
            keyRegistrationHandler.stop();
        }
    }

    @Override
    public Mono<Registration> register(EventListener.ReactiveEventListener listener, RegistrationKey key) {
        Preconditions.checkState(isRunning, NOT_RUNNING_ERROR_MESSAGE);
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric("redis-register", keyRegistrationHandler.register(listener, key)));
    }

    @Override
    public Mono<Void> dispatch(Event event, Set<RegistrationKey> key) {
        Preconditions.checkState(isRunning, NOT_RUNNING_ERROR_MESSAGE);
        if (!event.isNoop()) {
            return Mono.from(metricFactory.decoratePublisherWithTimerMetric("redis-dispatch", dispatchEvent(event, key)));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> dispatch(Collection<EventWithRegistrationKey> events) {
        Preconditions.checkState(isRunning, NOT_RUNNING_ERROR_MESSAGE);

        ImmutableList<EventWithRegistrationKey> notNoopEvents = events.stream()
            .filter(e -> !e.event().isNoop())
            .collect(ImmutableList.toImmutableList());
        if (!notNoopEvents.isEmpty()) {
            return Mono.from(metricFactory.decoratePublisherWithTimerMetric("redis-dispatch", dispatchEvent(events)));
        }
        return Mono.empty();
    }

    @Override
    public Registration register(ReactiveEventListener listener, Group group) {
        throw new UnsupportedOperationException("Does not support Group registration");
    }

    @Override
    public Mono<Void> reDeliver(Group group, Event event) {
        return Mono.error(() -> new UnsupportedOperationException("Does not support Group registration"));
    }

    @Override
    public EventBusName eventBusName() {
        return EVENT_BUS_NAME;
    }

    private Mono<Void> dispatchEvent(Event event, Set<RegistrationKey> keys) {
        return Flux.concat(localKeyListenerExecutor.execute(event, keys),
                redisKeyEventDispatcher.dispatch(event, keys))
            .doOnError(err -> LOGGER.error("Error while dispatching event {}", event.getEventId(), err))
            .then();
    }

    private Mono<Void> dispatchEvent(Collection<EventBus.EventWithRegistrationKey> events) {
        return Flux.concat(localKeyListenerExecutor.execute(events),
                redisKeyEventDispatcher.dispatch(events))
            .doOnError(err -> LOGGER.error("Error while dispatching events batch", err))
            .then();
    }

}