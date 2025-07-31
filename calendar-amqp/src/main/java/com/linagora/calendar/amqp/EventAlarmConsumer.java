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
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Named;
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

public class EventAlarmConsumer implements Closeable, Startable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final Logger LOGGER = LoggerFactory.getLogger(EventAlarmConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    public enum Queue {
        ADD("calendar:event:alarm:created", "tcalendar:event:alarm:created", "tcalendar:event:alarm:created:dead-letter"),
        UPDATE("calendar:event:alarm:updated", "tcalendar:event:alarm:updated", "tcalendar:event:alarm:updated:dead-letter"),
        DELETE("calendar:event:alarm:deleted", "tcalendar:event:alarm:deleted", "tcalendar:event:alarm:deleted:dead-letter"),
        CANCEL("calendar:event:alarm:cancel", "tcalendar:event:alarm:cancel", "tcalendar:event:alarm:cancel:dead-letter"),
        REQUEST("calendar:event:alarm:request", "tcalendar:event:alarm:request", "tcalendar:event:alarm:request:dead-letter");

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
    }

    private final ReceiverProvider receiverProvider;
    private final Consumer<Queue> declareExchangeAndQueue;
    private final Map<Queue, Disposable> consumeDisposableMap;

    @Inject
    @Singleton
    public EventAlarmConsumer(ReactorRabbitMQChannelPool channelPool,
                              @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.receiverProvider = channelPool::createReceiver;

        Sender sender = channelPool.getSender();
        this.declareExchangeAndQueue = eventQueue -> Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(eventQueue.exchangeName)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(eventQueue.deadLetter)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
                sender.declareQueue(QueueSpecification
                    .queue(eventQueue.queueName)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(eventQueue.deadLetter)
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(eventQueue.exchangeName)
                    .queue(eventQueue.queueName)
                    .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();

        this.consumeDisposableMap = new EnumMap<>(Queue.class);
    }

    public void init() {
        Arrays.stream(Queue.values())
            .forEach(declareExchangeAndQueue);

        start();
    }

    public void start() {
        consumeDisposableMap.put(Queue.ADD, doConsumeCalendarEventMessages(Queue.ADD, handlerAdd));
        consumeDisposableMap.put(Queue.UPDATE, doConsumeCalendarEventMessages(Queue.UPDATE, handlerAddOrUpdate));
        consumeDisposableMap.put(Queue.DELETE, doConsumeCalendarEventMessages(Queue.DELETE, handlerDelete));
        consumeDisposableMap.put(Queue.CANCEL, doConsumeCalendarEventMessages(Queue.CANCEL, handlerDelete));
        consumeDisposableMap.put(Queue.REQUEST, doConsumeCalendarEventMessages(Queue.REQUEST, handlerAddOrUpdate));
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    public void close() {
        consumeDisposableMap.values().forEach(disposable -> {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        });
    }

    public interface EventAlarmHandler {
        Mono<?> handle(CalendarAlarmMessageDTO message);
    }

    private final EventAlarmHandler handlerAdd = (message) -> Mono.empty();

    private final EventAlarmHandler handlerAddOrUpdate = (message) -> Mono.empty();

    private final EventAlarmHandler handlerDelete = (message) -> Mono.empty();

    private Disposable doConsumeCalendarEventMessages(Queue queue, EventAlarmHandler eventAlarmHandler) {
        return delivery(queue.queueName)
            .flatMap(delivery -> messageConsume(delivery,
                Throwing.supplier(() -> OBJECT_MAPPER.readValue(delivery.getBody(), CalendarAlarmMessageDTO.class)).get(),
                eventAlarmHandler), DEFAULT_CONCURRENCY)
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, CalendarAlarmMessageDTO message, EventAlarmHandler eventAlarmHandler) {
        return eventAlarmHandler.handle(message)
            .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar alarm event successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath())))
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume calendar alarm event", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }
}
