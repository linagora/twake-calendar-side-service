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
import java.net.URI;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.core.Username;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.name.Named;
import com.linagora.calendar.dav.CalDavClient;
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

public class EventITIPConsumer implements Closeable, Startable {
    public static final String EXCHANGE_NAME = "calendar:itip:deliver";
    public static final String QUEUE_NAME = "tcalendar:itip:deliver";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:itip:deliver:dead-letter";
    public static final String CONNECTED_USER_HEADER = "connectedUser";
    public static final String REQUEST_URI_HEADER = "requestURI";

    private static final Logger LOGGER = LoggerFactory.getLogger(EventITIPConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    private final ReceiverProvider receiverProvider;
    private final CalDavClient calDavClient;
    private final int itipEventMessagesPrefetchCount;

    private Disposable consumeDisposable;

    @Inject
    public EventITIPConsumer(ReactorRabbitMQChannelPool channelPool,
                             @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                             CalDavClient calDavClient,
                             @Named("itipEventMessagesPrefetchCount") int itipEventMessagesPrefetchCount) {
        this.receiverProvider = channelPool::createReceiver;
        this.calDavClient = calDavClient;
        this.itipEventMessagesPrefetchCount = itipEventMessagesPrefetchCount;

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
        consumeDisposable = doConsumeMessages();
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

    private Disposable doConsumeMessages() {
        return delivery(QUEUE_NAME)
            .flatMap(this::consumeMessage, DEFAULT_CONCURRENCY)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(itipEventMessagesPrefetchCount)),
            Receiver::close);
    }

    private Mono<Void> consumeMessage(AcknowledgableDelivery ackDelivery) {
        return Mono.fromCallable(() -> extractHeaderProperties(ackDelivery))
            .flatMap(usernameURIPair -> calDavClient.sendIMIPCallback(usernameURIPair.getLeft(),
                usernameURIPair.getRight(), ackDelivery.getBody()))
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume calendar itip event message", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private Pair<Username, URI> extractHeaderProperties(AcknowledgableDelivery ackDelivery) {
        Username user = Username.of(ackDelivery.getProperties().getHeaders().get(CONNECTED_USER_HEADER).toString());
        String requestUriHeader = ackDelivery.getProperties().getHeaders().get(REQUEST_URI_HEADER).toString();
        String normalizedUri = Strings.CS.startsWith(requestUriHeader, "/") ? requestUriHeader : "/" + requestUriHeader;
        URI requestURI = URI.create(normalizedUri);
        return Pair.of(user, requestURI);
    }
}

