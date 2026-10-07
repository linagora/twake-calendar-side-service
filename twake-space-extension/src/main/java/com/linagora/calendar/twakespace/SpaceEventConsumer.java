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

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;

import java.io.Closeable;
import java.util.stream.Stream;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

// A single active consumer, so that the events of a space are handled in order across replicas.
public class SpaceEventConsumer implements Closeable, Startable {
    public static final String QUEUE = "tcalendar:twake-space";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:twake-space-dead-letter";

    private static final Logger LOGGER = LoggerFactory.getLogger(SpaceEventConsumer.class);

    private final ManagedRabbitMQConsumer consumer;
    private final TwakeSpaceProvisioner provisioner;
    private final ActivityPublisher activityPublisher;

    @Inject
    public SpaceEventConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                              @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                              TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                              TwakeSpaceConfiguration configuration,
                              TwakeSpaceProvisioner provisioner,
                              ActivityPublisher activityPublisher) {
        this.provisioner = provisioner;
        this.activityPublisher = activityPublisher;
        QueueDeclaration.Builder queueDeclaration = QueueDeclaration.builder()
            .queue(QUEUE)
            .deadLetterQueue(DEAD_LETTER_QUEUE);
        Stream.of(SpaceEventType.values())
            .forEach(type -> queueDeclaration.binding(configuration.spaceExchange(), type.routingKey()));
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(queueDeclaration.build())
                .queueArguments(() -> twpCommonRabbitMQConfiguration.quorumQueuesBypass()
                    ? QueueArguments.builder()
                    : rabbitMQConfiguration.workQueueArgumentsBuilder())
                .singleActiveConsumer()
                .handleDelivery(this::handleDelivery)
                .build());
    }

    public void init() {
        consumer.init();
    }

    public void restart() {
        consumer.restart();
    }

    @Override
    @PreDestroy
    public void close() {
        consumer.close();
    }

    private Mono<Void> handleDelivery(AcknowledgableDelivery delivery) {
        String routingKey = delivery.getEnvelope().getRoutingKey();
        return Mono.fromCallable(() -> SpaceEvent.deserialize(delivery.getBody()))
            .doOnNext(event -> LOGGER.debug("Received {} for space {}", routingKey, event.id()))
            .flatMap(event -> SpaceEventType.fromRoutingKey(routingKey)
                .map(type -> provisioner.handle(type, event)
                    .flatMap(activityPublisher::publish))
                .orElseGet(() -> Mono.fromRunnable(() ->
                    LOGGER.warn("Ignoring {} for space {}: not a space event TwakeSpace handles", routingKey, event.id()))));
    }
}
