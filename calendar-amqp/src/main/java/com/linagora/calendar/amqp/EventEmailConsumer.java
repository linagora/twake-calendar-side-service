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
import java.util.function.Supplier;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.name.Named;
import com.rabbitmq.client.BuiltinExchangeType;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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

public class EventEmailConsumer implements Closeable, Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventEmailConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String EXCHANGE_NAME = "calendar:event:notificationEmail:send";
    private static final String QUEUE_NAME = "tcalendar:event:notificationEmail:send";
    private static final String DEAD_LETTER_QUEUE = "tcalendar:event:notificationEmail:send:dead-letter";

    private final ReceiverProvider receiverProvider;
    private Disposable consumeDisposable;

    @Inject
    @Singleton
    public EventEmailConsumer(ReactorRabbitMQChannelPool channelPool,
                              @Named(INJECT_KEY_DAV)  Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.receiverProvider = channelPool::createReceiver;

        Sender sender = channelPool.getSender();
        Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(EXCHANGE_NAME)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(DEAD_LETTER_QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get().build())),
                sender.declareQueue(QueueSpecification
                    .queue(QUEUE_NAME)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get().deadLetter(DEAD_LETTER_QUEUE).build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(EXCHANGE_NAME)
                    .queue(QUEUE_NAME)
                    .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
    }

    public void init() {
        start();
    }

    public void start() {
        consumeDisposable = doConsumeCalendarEventMessages();
    }

    public void restart() {
        close();
        start();
    }

    @Override
    public void close() {
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    private Disposable doConsumeCalendarEventMessages() {
        return delivery(QUEUE_NAME)
            .flatMap(this::consumeMessage, DEFAULT_CONCURRENCY)
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> consumeMessage(AcknowledgableDelivery ackDelivery) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.readValue(ackDelivery.getBody(), CalendarEventNotificationEmail.class))
            .flatMap(message -> handleMessage(message)
                .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar mail event message successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath()))) )
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume calendar mail event message", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private Mono<Void> handleMessage(CalendarEventNotificationEmail calendarEventMessage) {
        return switch (calendarEventMessage.method()) {
            case REQUEST -> {
                if (calendarEventMessage.isNewEvent()) {
                    LOGGER.info("Received new calendar event message with method REQUEST and eventPath {}", calendarEventMessage.eventPath());
                    yield Mono.empty();
                } else {
                    LOGGER.info("Received updated calendar event message with method REQUEST and eventPath {}", calendarEventMessage.eventPath());
                    yield Mono.empty();
                }
            }
            case REPLY -> {
                LOGGER.info("Received calendar event message with method REPLY and eventPath {}", calendarEventMessage.eventPath());
                yield Mono.empty();
            }
            case CANCEL -> {
                LOGGER.info("Received calendar event message with method CANCEL and eventPath {}", calendarEventMessage.eventPath());
                yield Mono.empty();
            }
            case COUNTER -> {
                LOGGER.info("Received calendar event message with method COUNTER and eventPath {}", calendarEventMessage.eventPath());
                yield Mono.empty();
            }
            default -> throw new IllegalArgumentException("Unknown method: " + calendarEventMessage.method());
        };
    }
}

