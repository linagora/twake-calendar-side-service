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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Named;
import com.linagora.calendar.storage.CalendarURL;
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

public class CalendarDelegatedNotificationConsumer implements Closeable, Startable {

    public static final String QUEUE = "tcalendar:calendar:delegated:created";
    private static final String EXCHANGE = "calendar:calendar:created";
    private static final String DEAD_LETTER = QUEUE + ":dead-letter";
    private static final boolean REQUEUE_ON_NACK = true;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarDelegatedNotificationConsumer.class);

    private final ReceiverProvider receiverProvider;
    private Disposable consumeDisposable;

    private final ReactorRabbitMQChannelPool channelPool;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
    private final DelegatedCalendarNotificationHandler notificationHandler;

    @Inject
    @Singleton
    public CalendarDelegatedNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                                 @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                                 DelegatedCalendarNotificationHandler notificationHandler) {
        this.receiverProvider = channelPool::createReceiver;
        this.channelPool = channelPool;
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.notificationHandler = notificationHandler;
    }

    public void init() {
        declareExchangeAndQueue(channelPool.getSender(), queueArgumentSupplier);
        start();
    }

    public void start() {
        this.consumeDisposable = doConsume();
    }

    @Override
    public void close() {
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    public void restart() {
        close();
        start();
    }

    private void declareExchangeAndQueue(Sender sender, Supplier<QueueArguments.Builder> queueArguments) {
        Flux.concat(sender.declareExchange(ExchangeSpecification.exchange(EXCHANGE)
                    .durable(DURABLE)
                    .type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification.queue(DEAD_LETTER)
                    .durable(DURABLE)
                    .arguments(queueArguments.get().build())),
                sender.declareQueue(QueueSpecification.queue(QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArguments.get()
                        .deadLetter(DEAD_LETTER)
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(EXCHANGE)
                    .queue(QUEUE)
                    .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
    }

    private Disposable doConsume() {
        return consumeFromQueue(QUEUE)
            .flatMap(this::handleMessage, DEFAULT_CONCURRENCY)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    private Flux<AcknowledgableDelivery> consumeFromQueue(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<Void> handleMessage(AcknowledgableDelivery acknowledgableDelivery) {
        return Mono.fromSupplier(Throwing.supplier(() -> CalendarDelegatedCreatedMessage.deserialize(acknowledgableDelivery.getBody())))
            .filter(hasDelegationRightKey())
            .flatMap(notificationHandler::handle)
            .doOnSuccess(any -> acknowledgableDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consuming calendar delegated notification event", error);
                acknowledgableDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private Predicate<CalendarDelegatedCreatedMessage> hasDelegationRightKey() {
        return message -> message.rightKey().isPresent();
    }

    public record CalendarDelegatedCreatedMessage(@JsonProperty("calendarPath") String calendarPath,
                                                  @JsonProperty("calendarProps") Map<String, JsonNode> calendarProps) {
        static final String RIGHT_KEY = "access";

        public static CalendarDelegatedCreatedMessage deserialize(byte[] payload) {
            try {
                return OBJECT_MAPPER.readValue(payload, CalendarDelegatedCreatedMessage.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize calendar delegated created message: " + new String(payload, StandardCharsets.UTF_8), e);
            }
        }

        public CalendarURL calendarURL() {
            return CalendarURL.parse(calendarPath);
        }

        public Optional<String> rightKey() {
            return Optional.ofNullable(calendarProps.get(RIGHT_KEY))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
        }
    }
}