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

package com.linagora.calendar.amqp;

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Named;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class EventResourceConsumer implements Closeable, Startable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Logger LOGGER = LoggerFactory.getLogger(EventResourceConsumer.class);

    public enum Queue {
        CREATE("resource:calendar:event:created", "resource:tcalendar:event:created", "resource:tcalendar:event:created:dead-letter"),
        ACCEPT("resource:calendar:event:accepted", "resource:tcalendar:event:accepted", "resource:tcalendar:event:accepted:dead-letter"),
        DECLINE("resource:calendar:event:declined", "resource:tcalendar:event:declined", "resource:tcalendar:event:declined:dead-letter");

        private final String exchangeName;
        private final String queueName;
        private final String deadLetter;

        Queue(String exchangeName, String queueName, String deadLetter) {
            this.exchangeName = exchangeName;
            this.queueName = queueName;
            this.deadLetter = deadLetter;
        }

        public String queueName() {
            return queueName;
        }

        public String deadLetter() {
            return deadLetter;
        }

        public String exchangeName() {
            return exchangeName;
        }
    }

    private final Map<Queue, ManagedRabbitMQConsumer> consumers;
    private final EventResourceHandler eventResourceHandler;

    @Inject
    @Singleton
    public EventResourceConsumer(ReactorRabbitMQChannelPool channelPool,
                                 @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                 EventResourceHandler eventResourceHandler) {
        this.eventResourceHandler = eventResourceHandler;
        this.consumers = new EnumMap<>(Queue.class);
        ManagedRabbitMQConsumer.Factory factory = new ManagedRabbitMQConsumer.Factory(channelPool);
        Arrays.stream(Queue.values())
            .forEach(queue -> consumers.put(queue, factory.create(parameters(queue, queueArgumentSupplier))));
    }

    private ManagedRabbitMQConsumer.Parameters parameters(Queue queue, Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        return ManagedRabbitMQConsumer.Parameters.builder()
            .queueDeclaration(QueueDeclaration.builder()
                .binding(queue.exchangeName(), BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY)
                .queue(queue.queueName())
                .deadLetterQueue(queue.deadLetter())
                .build())
            .queueArguments(queueArgumentSupplier)
            .qos(DEFAULT_CONCURRENCY)
            .concurrency(DEFAULT_CONCURRENCY)
            .handleDelivery(delivery -> messageConsume(delivery, handlerFor(queue)).then())
            .build();
    }

    public void init() {
        consumers.values().forEach(ManagedRabbitMQConsumer::init);
    }

    public void start() {
        consumers.values().forEach(ManagedRabbitMQConsumer::start);
    }

    public void restart() {
        consumers.values().forEach(ManagedRabbitMQConsumer::restart);
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event resource consumer");
        consumers.values().forEach(ManagedRabbitMQConsumer::close);
    }

    public interface EventHandler {
        Mono<?> handle(CalendarResourceMessageDTO message);
    }

    private EventHandler handlerFor(Queue queue) {
        return switch (queue) {
            case CREATE -> eventResourceHandler::handleCreateEvent;
            case ACCEPT -> eventResourceHandler::handleAcceptEvent;
            case DECLINE -> eventResourceHandler::handleDeclineEvent;
        };
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, EventHandler eventHandler) {
        return Mono.fromSupplier(Throwing.supplier(() -> OBJECT_MAPPER.readValue(ackDelivery.getBody(), CalendarResourceMessageDTO.class)))
            .flatMap(message -> eventHandler.handle(message)
                .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar resource event successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath()))));
    }
}