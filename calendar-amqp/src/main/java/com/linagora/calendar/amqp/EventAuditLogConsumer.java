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
import java.util.List;
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
    static final String AUDIT_QUEUE = "tcalendar:audit";
    static final String AUDIT_DEAD_LETTER = "tcalendar:audit:dead-letter";

    private static final List<String> EXCHANGES = List.of(
        "sabre:contact:created",
        "sabre:contact:deleted",
        "sabre:contact:updated",
        "sabre:contact:update",
        "calendar:subscription:created",
        "calendar:subscription:deleted",
        "calendar:subscription:updated",
        "calendar:calendar:created",
        "calendar:calendar:deleted",
        "calendar:calendar:updated",
        "calendar:event:created",
        "calendar:event:updated",
        "calendar:event:deleted",
        "calendar:event:request",
        "calendar:event:cancel",
        "calendar:event:reply",
        "sabre:addressbook:created",
        "sabre:addressbook:deleted",
        "sabre:addressbook:updated",
        "sabre:addressbook:subscription:created",
        "sabre:addressbook:subscription:deleted",
        "sabre:addressbook:subscription:updated");

    private final ReceiverProvider receiverProvider;
    private final AuditLogger auditLogger;
    private Disposable consumeDisposable;

    @Inject
    @Singleton
    public EventAuditLogConsumer(ReactorRabbitMQChannelPool channelPool,
                                 @Named(CalendarAmqpModule.INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.receiverProvider = channelPool::createReceiver;
        this.auditLogger = new DefaultAuditLogger();
        declareInfrastructure(channelPool.getSender(), queueArgumentSupplier);
    }

    private void declareInfrastructure(Sender sender, Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(AUDIT_DEAD_LETTER)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(AUDIT_DEAD_LETTER)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(AUDIT_DEAD_LETTER)
                    .queue(AUDIT_DEAD_LETTER)
                    .routingKey(EMPTY_ROUTING_KEY)),
                sender.declareQueue(QueueSpecification
                    .queue(AUDIT_QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(AUDIT_DEAD_LETTER)
                        .build())))
            .thenMany(Flux.fromIterable(EXCHANGES)
                .flatMap(exchange -> Flux.concat(
                    sender.declareExchange(ExchangeSpecification.exchange(exchange)
                        .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                    sender.bind(BindingSpecification.binding()
                        .exchange(exchange)
                        .queue(AUDIT_QUEUE)
                        .routingKey(EMPTY_ROUTING_KEY)))))
            .then()
            .block();
    }

    public void init() {
        start();
    }

    public void start() {
        consumeDisposable = doConsume();
    }

    public void restart() {
        close();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event audit log consumer");
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    private Disposable doConsume() {
        return delivery()
            .flatMap(this::messageConsume, DEFAULT_CONCURRENCY)
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery() {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(AUDIT_QUEUE, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery) {
        return Mono.fromRunnable(() -> {
                String body = new String(ackDelivery.getBody(), StandardCharsets.UTF_8);
                String exchangeName = ackDelivery.getEnvelope().getExchange();
                String logLine = auditLogger.format(body, exchangeName);
                AUDIT_LOGGER.info(logLine);
            })
            .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed audit log event")))
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume audit log event", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }
}