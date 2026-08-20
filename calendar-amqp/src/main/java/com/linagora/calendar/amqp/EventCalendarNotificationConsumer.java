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
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class EventCalendarNotificationConsumer implements Closeable, Startable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final Logger LOGGER = LoggerFactory.getLogger(EventCalendarNotificationConsumer.class);

    public enum Queue {
        ADD("calendar:event:created", "tcalendar:event:created:notification", "tcalendar:event:created:notification-dead-letter"),
        UPDATE("calendar:event:updated", "tcalendar:event:updated:notification", "tcalendar:event:updated:notification-dead-letter"),
        DELETE("calendar:event:deleted", "tcalendar:event:deleted:notification", "tcalendar:event:deleted:notification-dead-letter"),
        CANCEL("calendar:event:cancel", "tcalendar:event:cancel:notification", "tcalendar:event:cancel:notification-dead-letter"),
        REQUEST("calendar:event:request", "tcalendar:event:request:notification", "tcalendar:event:request:notification-dead-letter"),
        REPLY("calendar:event:reply", "tcalendar:event:reply:notification", "tcalendar:event:reply:notification-dead-letter");

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
    private final EventBus eventBus;

    @Inject
    public EventCalendarNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                             @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier, EventBus eventBus) {
        this.eventBus = eventBus;
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
            .handleDelivery(this::messageConsume)
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
        LOGGER.info("Trying to stop event calendar notification consumer");
        consumers.values().forEach(ManagedRabbitMQConsumer::close);
    }

    private Mono<Void> messageConsume(AcknowledgableDelivery ackDelivery) {
        return Mono.fromCallable(() -> Throwing.supplier(() -> getEventPath(ackDelivery.getBody())).get())
            .flatMap(eventPath -> handle(eventPath)
                .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar notification event successfully {}", eventPath))));
    }

    private String getEventPath(byte[] json) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode eventPathNode = root.at("/eventPath");
        if (eventPathNode.isMissingNode() || eventPathNode.isNull()) {
            throw new IllegalArgumentException("Missing required field 'eventPath' in message payload");
        }
        return eventPathNode.asText();
    }

    private Mono<Void> handle(String eventPath) {
        CalendarURL calendarURL = CalendarURL.parse(eventPath);
        return eventBus.dispatch(new CalendarChangeEvent(Event.EventId.random(), calendarURL), new CalendarURLRegistrationKey(calendarURL));
    }
}