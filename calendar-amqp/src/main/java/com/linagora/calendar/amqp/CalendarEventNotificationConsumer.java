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

import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.CalendarEventNotificationDispatcher;
import com.linagora.calendar.storage.CalendarURL;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

public class CalendarEventNotificationConsumer implements Closeable, Startable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventNotificationConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

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
    }

    public record CalendarEventMessage(String eventPath, boolean isImport) {
        public CalendarURL extractCalendarURL() {
            return EventFieldConverter.extractCalendarURL(eventPath);
        }
    }

    public interface MessageDeserializer {
        CalendarEventMessage deserialize(byte[] bytes);
    }

    private final CalendarEventNotificationDispatcher notificationDispatcher;
    private final ReceiverProvider receiverProvider;
    private final Sender sender;
    private final Supplier<QueueArguments.Builder> queueArguments;

    private final Map<Queue, Disposable> consumeDisposables = new EnumMap<>(Queue.class);
    private final Map<Queue, MessageDeserializer> deserializers = new EnumMap<>(Queue.class);

    public CalendarEventNotificationConsumer(CalendarEventNotificationDispatcher notificationDispatcher,
                                             ReceiverProvider receiverProvider,
                                             ReactorRabbitMQChannelPool channelPool,
                                             Supplier<QueueArguments.Builder> queueArguments) {

        this.notificationDispatcher = notificationDispatcher;
        this.receiverProvider = receiverProvider;
        this.sender = channelPool.getSender();
        this.queueArguments = queueArguments;

        // Initialize deserializer map
        deserializers.put(Queue.ADD, bytes -> todoNotImplemented("ADD"));
        deserializers.put(Queue.UPDATE, bytes -> todoNotImplemented("UPDATE"));
        deserializers.put(Queue.DELETE, bytes -> todoNotImplemented("DELETE"));
        deserializers.put(Queue.CANCEL, bytes -> todoNotImplemented("CANCEL"));
        deserializers.put(Queue.REQUEST, bytes -> todoNotImplemented("REQUEST"));
        deserializers.put(Queue.REPLY, bytes -> todoNotImplemented("REPLY"));
    }

    private CalendarEventMessage todoNotImplemented(String type) {
        throw new IllegalStateException("Deserializer not implemented for " + type);
    }

    public void init() {
        declareAllQueues();
        start();
    }

    private void declareAllQueues() {
        for (Queue q : Queue.values()) {
            declareExchangeAndQueue(q);
        }
    }

    private void declareExchangeAndQueue(Queue queue) {
        sender.declareExchange(ExchangeSpecification.exchange(queue.exchangeName)
                .durable(DURABLE)
                .type(BuiltinExchangeType.FANOUT.getType()))
            .then(sender.declareQueue(QueueSpecification.queue(queue.deadLetter)
                .durable(DURABLE)
                .arguments(queueArguments.get().build())))
            .then(sender.declareQueue(QueueSpecification.queue(queue.queueName)
                .durable(DURABLE)
                .arguments(queueArguments.get().deadLetter(queue.deadLetter).build())))
            .then(sender.bind(BindingSpecification.binding()
                .exchange(queue.exchangeName)
                .queue(queue.queueName)
                .routingKey(EMPTY_ROUTING_KEY)))
            .block();
    }

    public void start() {
        deserializers.forEach((queue, deserializer) ->
            consumeDisposables.put(queue, consume(queue, deserializer)));
    }

    private Disposable consume(Queue queue, MessageDeserializer deserializer) {
        return delivery(queue.queueName)
            .flatMap(delivery ->
                    messageConsume(delivery, queue, deserializer.deserialize(delivery.getBody())),
                DEFAULT_CONCURRENCY)
            .subscribe();
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ack,
                                   Queue queue,
                                   CalendarEventMessage message) {
        return notificationDispatcher.dispatch(message.extractCalendarURL(), message.eventPath(), queue.queueName)
            .doOnSuccess(v -> ack.ack())
            .onErrorResume(e -> {
                LOGGER.error("Failed to process message from queue {}", queue.queueName, e);
                ack.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private Flux<AcknowledgableDelivery> delivery(String queueName) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queueName, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    @Override
    public void close() {
        consumeDisposables.values().forEach(d -> {
            if (!d.isDisposed()) {
                d.dispose();
            }
        });
    }
}