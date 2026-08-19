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

import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.slf4j.Logger;

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
 * Factors out the AMQP boilerplate repeated by every consumer in this package: declaring an
 * exchange (or several), its dead-letter exchange/queue/binding and the main queue/binding, then
 * consuming with manual ack and a shared ack-on-success / nack-without-requeue-on-error policy.
 */
public final class RabbitMQConsumerSupport {
    private static final boolean REQUEUE_ON_NACK = true;

    private RabbitMQConsumerSupport() {
    }

    public static Mono<Void> declare(Sender sender, QueueDeclaration declaration, Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        return Flux.concat(
                Flux.fromIterable(declaration.exchangeNames())
                    .flatMap(exchangeName -> sender.declareExchange(ExchangeSpecification.exchange(exchangeName)
                        .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType()))),
                sender.declareExchange(ExchangeSpecification.exchange(declaration.deadLetter())
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(declaration.deadLetter())
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(declaration.deadLetter())
                    .queue(declaration.deadLetter())
                    .routingKey(EMPTY_ROUTING_KEY)),
                sender.declareQueue(QueueSpecification
                    .queue(declaration.queueName())
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(declaration.deadLetter())
                        .build())),
                Flux.fromIterable(declaration.exchangeNames())
                    .flatMap(exchangeName -> sender.bind(BindingSpecification.binding()
                        .exchange(exchangeName)
                        .queue(declaration.queueName())
                        .routingKey(EMPTY_ROUTING_KEY))))
            .then();
    }

    public static void declareBlocking(Sender sender, QueueDeclaration declaration, Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        declare(sender, declaration, queueArgumentSupplier).block();
    }

    public static Flux<AcknowledgableDelivery> delivery(ReceiverProvider receiverProvider, String queue, int prefetchCount) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(prefetchCount)),
            Receiver::close);
    }

    public static Flux<AcknowledgableDelivery> delivery(ReceiverProvider receiverProvider, String queue) {
        return delivery(receiverProvider, queue, DEFAULT_CONCURRENCY);
    }

    /**
     * Consumes on the calling scheduler, matching consumers that never switch off the RabbitMQ
     * receiver's own thread.
     */
    public static Disposable consume(ReceiverProvider receiverProvider, String queueName, Function<AcknowledgableDelivery, Mono<?>> handler) {
        return delivery(receiverProvider, queueName)
            .flatMap(handler, DEFAULT_CONCURRENCY)
            .subscribe();
    }

    /**
     * Consumes on {@link Schedulers#boundedElastic()}, matching consumers whose handler performs
     * blocking or long-running work.
     */
    public static Disposable consumeOnBoundedElastic(ReceiverProvider receiverProvider, String queueName, Function<AcknowledgableDelivery, Mono<?>> handler) {
        return consumeOnBoundedElastic(receiverProvider, queueName, DEFAULT_CONCURRENCY, handler);
    }

    public static Disposable consumeOnBoundedElastic(ReceiverProvider receiverProvider, String queueName, int prefetchCount, Function<AcknowledgableDelivery, Mono<?>> handler) {
        return delivery(receiverProvider, queueName, prefetchCount)
            .flatMap(handler, DEFAULT_CONCURRENCY)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    public static Function<AcknowledgableDelivery, Mono<?>> ackNackWrapper(Function<AcknowledgableDelivery, Mono<?>> business, Logger logger, String errorMessage) {
        return ackDelivery -> business.apply(ackDelivery)
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                logger.error(errorMessage, error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }
}
