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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public class EventAuditLogConsumer implements Closeable, Startable {
    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("audit");
    private static final Logger LOGGER = LoggerFactory.getLogger(EventAuditLogConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    public enum AuditQueue {
        CONTACT_CREATED("sabre:contact:created", "tcalendar:audit:sabre:contact:created",
            "tcalendar:audit:sabre:contact:created:dead-letter"),
        CONTACT_DELETED("sabre:contact:deleted", "tcalendar:audit:sabre:contact:deleted",
            "tcalendar:audit:sabre:contact:deleted:dead-letter"),
        CONTACT_UPDATED("sabre:contact:updated", "tcalendar:audit:sabre:contact:updated",
            "tcalendar:audit:sabre:contact:updated:dead-letter"),
        CONTACT_UPDATE("sabre:contact:update", "tcalendar:audit:sabre:contact:update",
            "tcalendar:audit:sabre:contact:update:dead-letter"),
        SUBSCRIPTION_CREATED("calendar:subscription:created", "tcalendar:audit:calendar:subscription:created",
            "tcalendar:audit:calendar:subscription:created:dead-letter"),
        SUBSCRIPTION_DELETED("calendar:subscription:deleted", "tcalendar:audit:calendar:subscription:deleted",
            "tcalendar:audit:calendar:subscription:deleted:dead-letter"),
        SUBSCRIPTION_UPDATED("calendar:subscription:updated", "tcalendar:audit:calendar:subscription:updated",
            "tcalendar:audit:calendar:subscription:updated:dead-letter"),
        CALENDAR_CREATED("calendar:calendar:created", "tcalendar:audit:calendar:calendar:created",
            "tcalendar:audit:calendar:calendar:created:dead-letter"),
        CALENDAR_DELETED("calendar:calendar:deleted", "tcalendar:audit:calendar:calendar:deleted",
            "tcalendar:audit:calendar:calendar:deleted:dead-letter"),
        CALENDAR_UPDATED("calendar:calendar:updated", "tcalendar:audit:calendar:calendar:updated",
            "tcalendar:audit:calendar:calendar:updated:dead-letter"),
        EVENT_CREATED("calendar:event:created", "tcalendar:audit:calendar:event:created",
            "tcalendar:audit:calendar:event:created:dead-letter"),
        EVENT_UPDATED("calendar:event:updated", "tcalendar:audit:calendar:event:updated",
            "tcalendar:audit:calendar:event:updated:dead-letter"),
        EVENT_DELETED("calendar:event:deleted", "tcalendar:audit:calendar:event:deleted",
            "tcalendar:audit:calendar:event:deleted:dead-letter"),
        EVENT_REQUEST("calendar:event:request", "tcalendar:audit:calendar:event:request",
            "tcalendar:audit:calendar:event:request:dead-letter"),
        EVENT_CANCEL("calendar:event:cancel", "tcalendar:audit:calendar:event:cancel",
            "tcalendar:audit:calendar:event:cancel:dead-letter"),
        EVENT_REPLY("calendar:event:reply", "tcalendar:audit:calendar:event:reply",
            "tcalendar:audit:calendar:event:reply:dead-letter"),
        ADDRESSBOOK_CREATED("sabre:addressbook:created", "tcalendar:audit:sabre:addressbook:created",
            "tcalendar:audit:sabre:addressbook:created:dead-letter"),
        ADDRESSBOOK_DELETED("sabre:addressbook:deleted", "tcalendar:audit:sabre:addressbook:deleted",
            "tcalendar:audit:sabre:addressbook:deleted:dead-letter"),
        ADDRESSBOOK_UPDATED("sabre:addressbook:updated", "tcalendar:audit:sabre:addressbook:updated",
            "tcalendar:audit:sabre:addressbook:updated:dead-letter"),
        ADDRESSBOOK_SUBSCRIPTION_CREATED("sabre:addressbook:subscription:created",
            "tcalendar:audit:sabre:addressbook:subscription:created",
            "tcalendar:audit:sabre:addressbook:subscription:created:dead-letter"),
        ADDRESSBOOK_SUBSCRIPTION_DELETED("sabre:addressbook:subscription:deleted",
            "tcalendar:audit:sabre:addressbook:subscription:deleted",
            "tcalendar:audit:sabre:addressbook:subscription:deleted:dead-letter"),
        ADDRESSBOOK_SUBSCRIPTION_UPDATED("sabre:addressbook:subscription:updated",
            "tcalendar:audit:sabre:addressbook:subscription:updated",
            "tcalendar:audit:sabre:addressbook:subscription:updated:dead-letter");

        private final String exchangeName;
        private final String queueName;
        private final String deadLetter;

        AuditQueue(String exchangeName, String queueName, String deadLetter) {
            this.exchangeName = exchangeName;
            this.queueName = queueName;
            this.deadLetter = deadLetter;
        }

        public String exchangeName() {
            return exchangeName;
        }

        public String queueName() {
            return queueName;
        }

        public String deadLetter() {
            return deadLetter;
        }
    }

    private final ReceiverProvider receiverProvider;
    private final Consumer<AuditQueue> declareExchangeAndQueue;
    private final Map<AuditQueue, Disposable> consumeDisposableMap;

    @Inject
    @Singleton
    public EventAuditLogConsumer(ReactorRabbitMQChannelPool channelPool,
                                 @Named(CalendarAmqpModule.INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.receiverProvider = channelPool::createReceiver;

        Sender sender = channelPool.getSender();
        this.declareExchangeAndQueue = eventQueue -> Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(eventQueue.exchangeName)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareExchange(ExchangeSpecification.exchange(eventQueue.deadLetter)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(eventQueue.deadLetter)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(eventQueue.deadLetter)
                    .queue(eventQueue.deadLetter)
                    .routingKey(EMPTY_ROUTING_KEY)),
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

        this.consumeDisposableMap = new EnumMap<>(AuditQueue.class);
    }

    public void init() {
        Arrays.stream(AuditQueue.values())
            .forEach(declareExchangeAndQueue);

        start();
    }

    public void start() {
        for (AuditQueue queue : AuditQueue.values()) {
            consumeDisposableMap.put(queue, doConsume(queue));
        }
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event audit log consumer");
        consumeDisposableMap.values()
            .stream()
            .filter(disposable -> !disposable.isDisposed())
            .forEach(Disposable::dispose);
    }

    private Disposable doConsume(AuditQueue queue) {
        return delivery(queue.queueName())
            .flatMap(delivery -> messageConsume(delivery, queue), DEFAULT_CONCURRENCY)
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, AuditQueue auditQueue) {
        return Mono.fromRunnable(() -> {
                String body = new String(ackDelivery.getBody(), StandardCharsets.UTF_8);
                AUDIT_LOGGER.info("{} {}", auditQueue.exchangeName(), body);
            })
            .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed audit log event {}", auditQueue.exchangeName())))
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume audit log event", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }
}