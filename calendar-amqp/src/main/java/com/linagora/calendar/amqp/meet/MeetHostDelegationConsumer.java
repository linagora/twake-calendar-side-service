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

package com.linagora.calendar.amqp.meet;

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
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
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.name.Named;
import com.linagora.calendar.amqp.CalendarEventMessage;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

/**
 * Dedicated AMQP consumer that observes calendar event save/update events
 * and grants Meet host delegation as described by
 * {@link MeetHostDelegationService}. Mirrors the structure of
 * {@code EventIndexerConsumer} — separate queues, separate dead-letter
 * bindings, own reconnection handler — so a failure here never affects
 * calendar indexing or other side-effects.
 */
public class MeetHostDelegationConsumer implements Closeable, Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeetHostDelegationConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    public enum Queue {
        ADD("calendar:event:created", "tcalendar:event:created:meet-host-delegation", "tcalendar:event:created:meet-host-delegation-dead-letter"),
        UPDATE("calendar:event:updated", "tcalendar:event:updated:meet-host-delegation", "tcalendar:event:updated:meet-host-delegation-dead-letter");

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
    }

    private final ReceiverProvider receiverProvider;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
    private final Sender sender;
    private final MeetHostDelegationService meetHostDelegationService;
    private final MeetConfiguration meetConfiguration;
    private final Map<Queue, Disposable> consumeDisposableMap;

    @Inject
    @Singleton
    public MeetHostDelegationConsumer(ReactorRabbitMQChannelPool channelPool,
                                      @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                      MeetHostDelegationService meetHostDelegationService,
                                      MeetConfiguration meetConfiguration) {
        this.receiverProvider = channelPool::createReceiver;
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.sender = channelPool.getSender();
        this.meetHostDelegationService = meetHostDelegationService;
        this.meetConfiguration = meetConfiguration;
        this.consumeDisposableMap = new EnumMap<>(Queue.class);
    }

    public void init() {
        if (!meetConfiguration.enabled()) {
            LOGGER.info("Meet host delegation is disabled — skipping AMQP consumer initialization");
            return;
        }
        Arrays.stream(Queue.values()).forEach(this::declareExchangeAndQueue);
        start();
    }

    public void start() {
        if (!meetConfiguration.enabled()) {
            return;
        }
        Arrays.stream(Queue.values())
            .forEach(queue -> consumeDisposableMap.put(queue, doConsumeCalendarEventMessages(queue)));
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop meet host delegation consumer");
        consumeDisposableMap.values().forEach(disposable -> {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        });
    }

    private void declareExchangeAndQueue(Queue q) {
        Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(q.exchangeName)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareExchange(ExchangeSpecification.exchange(q.deadLetter)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification.queue(q.deadLetter)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get().build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(q.deadLetter)
                    .queue(q.deadLetter)
                    .routingKey(EMPTY_ROUTING_KEY)),
                sender.declareQueue(QueueSpecification.queue(q.queueName)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(q.deadLetter)
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(q.exchangeName)
                    .queue(q.queueName)
                    .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
    }

    private Disposable doConsumeCalendarEventMessages(Queue queue) {
        return delivery(queue.queueName)
            .flatMap(this::messageConsume, DEFAULT_CONCURRENCY)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery) {
        return Mono.fromCallable(() -> CalendarEventMessage.CreatedOrUpdated.deserialize(ackDelivery.getBody()))
            .flatMap(meetHostDelegationService::onEventSaved)
            .doOnSuccess(_ -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.warn("Meet host delegation consumer error — nack'ing message (no requeue)", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }
}
