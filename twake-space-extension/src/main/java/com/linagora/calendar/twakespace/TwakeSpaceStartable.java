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

    private final TwakeSpaceConfiguration configuration;
    private final MetricFactory metricFactory;
    private final TwakeSpaceProvisioner provisioner;
    private SimpleConnectionPool connectionPool;
    private ReactorRabbitMQChannelPool channelPool;
    private ManagedRabbitMQConsumer consumer;
    private volatile RabbitMQConsumersHealthCheck consumersCheck;
    private Disposable declaration;

    @Inject
    public TwakeSpaceStartable(PropertiesProvider propertiesProvider, RabbitMQConfiguration sideServiceRabbitMQ,
                               MetricFactory metricFactory, TwakeSpaceProvisioner provisioner)
        throws FileNotFoundException, ConfigurationException {
        this.configuration = TwakeSpaceConfiguration.from(propertiesProvider.getConfiguration("extensions"), sideServiceRabbitMQ);
        this.metricFactory = metricFactory;
        this.provisioner = provisioner;
    }

    @Override
    public void start() {
        connectionPool = new SimpleConnectionPool(new RabbitMQConnectionFactory(configuration.rabbitMQ()),
            SimpleConnectionPool.Configuration.DEFAULT);
        channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.DEFAULT, metricFactory, new NoopGaugeRegistry());
        channelPool.start();

        QueueDeclaration.Builder queueDeclaration = QueueDeclaration.builder()
            .queue(configuration.queue())
            .deadLetterQueue(configuration.deadLetterQueue());
        configuration.routingKeys().forEach(routingKey -> queueDeclaration.binding(configuration.exchange(), routingKey));
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(queueDeclaration.build())
                .queueArguments(configuration.rabbitMQ()::workQueueArgumentsBuilder)
                .singleActiveConsumer()
                .handleDelivery(this::handle)
                .build());

        ConsumerReconnectionHandler reconnectionHandler = new ConsumerReconnectionHandler(consumer::restart,
            "Error while restarting the TwakeSpace consumer");
        connectionPool.init(Set.of(reconnectionHandler));

        declaration = consumer.declare()
            .retryWhen(Retry.backoff(Long.MAX_VALUE, MIN_DECLARE_BACKOFF).maxBackoff(MAX_DECLARE_BACKOFF)
                .doBeforeRetry(signal -> LOGGER.warn("Failed to declare {}, retrying", configuration.queue(), signal.failure())))
            .then(Mono.fromRunnable(() -> {
                consumer.start();
                consumersCheck = new RabbitMQConsumersHealthCheck(Set.of(MonitoredRabbitMQConsumers.of(TwakeSpaceHealthCheck.COMPONENT_NAME.getName(),
                    connectionPool, () -> List.of(configuration.queue()), reconnectionHandler::handleReconnection)));
            }))
            .subscribe(null, error -> LOGGER.error("Failed to start the TwakeSpace consumer", error));
    }

    Optional<RabbitMQConsumersHealthCheck> consumersCheck() {
        return Optional.ofNullable(consumersCheck);
    }

    @PreDestroy
    public void stop() {
        if (declaration != null) {
            declaration.dispose();
        }
        if (consumer != null) {
            consumer.close();
            channelPool.close();
            connectionPool.close();
        }
    }

    private Mono<Void> handle(AcknowledgableDelivery delivery) {
        String routingKey = delivery.getEnvelope().getRoutingKey();
        return Mono.fromCallable(() -> SpaceEvent.deserialize(delivery.getBody()))
            .doOnNext(event -> LOGGER.debug("Received {} for space {}", routingKey, event.id()))
            .flatMap(event -> Mono.defer(() -> provisioner.handle(routingKey, event))
                .retryWhen(Retry.backoff(MAX_HANDLE_RETRIES, FIRST_HANDLE_BACKOFF)
                    .filter(error -> !(error instanceof UnprocessableSpaceEventException))
                    .doBeforeRetry(signal -> LOGGER.warn("Failed to handle {} for space {}, retrying", routingKey, event.id(), signal.failure()))));
    }
}
