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

package com.linagora.calendar.twakespace;

import java.io.FileNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.MonitoredRabbitMQConsumers;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.RabbitMQConsumersHealthCheck;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.utils.PropertiesProvider;
import org.apache.james.utils.UserDefinedStartable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.amqp.ConsumerReconnectionHandler;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.util.retry.Retry;

// A singleton, so that TwakeSpaceHealthCheck monitors the started consumer.
@Singleton
public class TwakeSpaceStartable implements UserDefinedStartable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwakeSpaceStartable.class);
    private static final Duration MIN_DECLARE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_DECLARE_BACKOFF = Duration.ofSeconds(30);
    // A failed event goes to the dead letter queue: retries ride out a short DAV or database outage first.
    private static final int MAX_HANDLE_RETRIES = 3;
    private static final Duration FIRST_HANDLE_BACKOFF = Duration.ofSeconds(1);
    private static final String EMPTY_ROUTING_KEY = "";

    private final TwakeSpaceConfiguration configuration;
    private final MetricFactory metricFactory;
    private final TwakeSpaceProvisioner provisioner;
    private final CalendarActivity calendarActivity;
    private RabbitMQ space;
    private RabbitMQ calendar;
    private ActivityPublisher activityPublisher;
    private volatile RabbitMQConsumersHealthCheck consumersCheck;
    private Disposable declaration;

    // A connection, and the consumer of its queue that the reconnection restarts.
    private record RabbitMQ(SimpleConnectionPool connectionPool, ReactorRabbitMQChannelPool channelPool,
                            ManagedRabbitMQConsumer consumer, ConsumerReconnectionHandler reconnectionHandler, String queue) {
        MonitoredRabbitMQConsumers monitored(String name) {
            return MonitoredRabbitMQConsumers.of(name, connectionPool, () -> List.of(queue), reconnectionHandler::handleReconnection);
        }

        void close() {
            consumer.close();
            channelPool.close();
            connectionPool.close();
        }
    }

    @Inject
    public TwakeSpaceStartable(PropertiesProvider propertiesProvider, RabbitMQConfiguration sideServiceRabbitMQ,
                               MetricFactory metricFactory, TwakeSpaceProvisioner provisioner, CalendarActivity calendarActivity)
        throws FileNotFoundException, ConfigurationException {
        this.configuration = TwakeSpaceConfiguration.from(propertiesProvider.getConfiguration("extensions"), sideServiceRabbitMQ);
        this.metricFactory = metricFactory;
        this.provisioner = provisioner;
        this.calendarActivity = calendarActivity;
    }

    @Override
    public void start() {
        QueueDeclaration.Builder spaceQueue = QueueDeclaration.builder()
            .queue(configuration.queue())
            .deadLetterQueue(configuration.deadLetterQueue());
        configuration.routingKeys().forEach(routingKey -> spaceQueue.binding(configuration.exchange(), routingKey));
        space = rabbitMQ(configuration.rabbitMQ(), spaceQueue.build(), this::handleSpaceEvent);
        activityPublisher = new ActivityPublisher(space.channelPool().getSender(), configuration.activityExchange());

        QueueDeclaration.Builder calendarQueue = QueueDeclaration.builder()
            .queue(configuration.calendarQueue())
            .deadLetterQueue(configuration.calendarDeadLetterQueue());
        CalendarActivity.EXCHANGES.forEach(exchange -> calendarQueue.binding(exchange, BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY));
        calendar = rabbitMQ(configuration.calendarRabbitMQ(), calendarQueue.build(), this::handleCalendarEvent);

        declaration = activityPublisher.declare()
            .then(space.consumer().declare())
            .then(calendar.consumer().declare())
            .retryWhen(Retry.backoff(Long.MAX_VALUE, MIN_DECLARE_BACKOFF).maxBackoff(MAX_DECLARE_BACKOFF)
                .doBeforeRetry(signal -> LOGGER.warn("Failed to declare the TwakeSpace queues, retrying", signal.failure())))
            .then(Mono.fromRunnable(() -> {
                space.consumer().start();
                calendar.consumer().start();
                consumersCheck = new RabbitMQConsumersHealthCheck(Set.of(
                    space.monitored(TwakeSpaceHealthCheck.COMPONENT_NAME.getName()),
                    calendar.monitored(TwakeSpaceHealthCheck.CALENDAR_COMPONENT_NAME.getName())));
            }))
            .subscribe(null, error -> LOGGER.error("Failed to start the TwakeSpace consumers", error));
    }

    private RabbitMQ rabbitMQ(RabbitMQConfiguration rabbitMQConfiguration, QueueDeclaration queue,
                              Function<AcknowledgableDelivery, Mono<Void>> handler) {
        SimpleConnectionPool connectionPool = new SimpleConnectionPool(new RabbitMQConnectionFactory(rabbitMQConfiguration),
            SimpleConnectionPool.Configuration.DEFAULT);
        ReactorRabbitMQChannelPool channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.DEFAULT, metricFactory, new NoopGaugeRegistry());
        channelPool.start();
        ManagedRabbitMQConsumer consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(queue)
                .queueArguments(rabbitMQConfiguration::workQueueArgumentsBuilder)
                .singleActiveConsumer()
                .handleDelivery(handler::apply)
                .build());
        ConsumerReconnectionHandler reconnectionHandler = new ConsumerReconnectionHandler(consumer::restart,
            "Error while restarting the consumer of " + queue.queue());
        connectionPool.init(Set.of(reconnectionHandler));
        return new RabbitMQ(connectionPool, channelPool, consumer, reconnectionHandler, queue.queue());
    }

    Optional<RabbitMQConsumersHealthCheck> consumersCheck() {
        return Optional.ofNullable(consumersCheck);
    }

    @PreDestroy
    public void stop() {
        if (declaration != null) {
            declaration.dispose();
        }
        if (calendar != null) {
            calendar.close();
        }
        if (space != null) {
            space.close();
        }
    }

    private Mono<Void> handleSpaceEvent(AcknowledgableDelivery delivery) {
        String routingKey = delivery.getEnvelope().getRoutingKey();
        return Mono.fromCallable(() -> SpaceEvent.deserialize(delivery.getBody()))
            .doOnNext(event -> LOGGER.debug("Received {} for space {}", routingKey, event.id()))
            .flatMap(event -> retried(Mono.defer(() -> provisioner.handle(routingKey, event)
                    .flatMap(activityPublisher::publish)),
                "Failed to handle " + routingKey + " for space " + event.id() + ", retrying"));
    }

    private Mono<Void> handleCalendarEvent(AcknowledgableDelivery delivery) {
        String exchange = delivery.getEnvelope().getExchange();
        return retried(Mono.defer(() -> calendarActivity.handle(exchange, delivery.getBody(), activityPublisher)),
            "Failed to handle a calendar event from " + exchange + ", retrying");
    }

    private static Mono<Void> retried(Mono<Void> handling, String retryMessage) {
        return handling.retryWhen(Retry.backoff(MAX_HANDLE_RETRIES, FIRST_HANDLE_BACKOFF)
            .filter(error -> !(error instanceof UnprocessableSpaceEventException))
            .doBeforeRetry(signal -> LOGGER.warn(retryMessage, signal.failure())));
    }
}
