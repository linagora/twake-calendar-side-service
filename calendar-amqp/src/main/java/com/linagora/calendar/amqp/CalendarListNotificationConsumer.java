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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.name.Named;
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

public class CalendarListNotificationConsumer implements Closeable, Startable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarListNotificationConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    public static final String QUEUE_NAME = "tcalendar:calendar:list:notification";
    public static final String DEAD_LETTER_QUEUE = QUEUE_NAME + ":dead-letter";

    public enum CalendarListExchange {
        SUBSCRIPTION_CREATED("calendar:subscription:created"),
        SUBSCRIPTION_UPDATED("calendar:subscription:updated"),
        SUBSCRIPTION_DELETED("calendar:subscription:deleted"),
        CALENDAR_CREATED("calendar:calendar:created"),
        CALENDAR_UPDATED("calendar:calendar:updated"),
        CALENDAR_DELETED("calendar:calendar:deleted");

        private final String exchangeName;

        CalendarListExchange(String exchangeName) {
            this.exchangeName = exchangeName;
        }

        public String asString() {
            return exchangeName;
        }

        public static Optional<CalendarListExchange> fromString(String exchangeName) {
            return Stream.of(values())
                .filter(exchange -> exchange.exchangeName.equals(exchangeName))
                .findFirst();
        }
    }

    private static final List<CalendarListExchange> EXCHANGES = List.of(CalendarListExchange.values());

    private final ReceiverProvider receiverProvider;
    private final CalendarListNotificationHandler notificationHandler;
    private final ReactorRabbitMQChannelPool channelPool;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;

    private Disposable consumeDisposable;

    @Inject
    public CalendarListNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                            @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                            CalendarListNotificationHandler notificationHandler) {
        this.channelPool = channelPool;
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.receiverProvider = channelPool::createReceiver;
        this.notificationHandler = notificationHandler;
    }

    private static void declareExchangeAndQueue(Supplier<QueueArguments.Builder> queueArgumentSupplier, Sender sender) {
        Flux.concat(Flux.fromIterable(EXCHANGES)
                    .flatMap(exchange -> sender.declareExchange(ExchangeSpecification.exchange(exchange.asString())
                        .durable(DURABLE)
                        .type(BuiltinExchangeType.FANOUT.getType()))),
                sender.declareQueue(QueueSpecification
                    .queue(DEAD_LETTER_QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
                sender.declareQueue(QueueSpecification
                    .queue(QUEUE_NAME)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(DEAD_LETTER_QUEUE)
                        .build())),
                Flux.fromIterable(EXCHANGES)
                    .flatMap(exchange -> sender.bind(BindingSpecification.binding()
                        .exchange(exchange.asString())
                        .queue(QUEUE_NAME)
                        .routingKey(EMPTY_ROUTING_KEY))))
            .then()
            .block();
    }

    public void init() {
        declareExchangeAndQueue(queueArgumentSupplier, channelPool.getSender());
        start();
    }

    public void start() {
        if (consumeDisposable == null || consumeDisposable.isDisposed()) {
            consumeDisposable = doConsumeCalendarListMessages();
        }
    }

    public void restart() {
        close();
        consumeDisposable = doConsumeCalendarListMessages();
    }

    @Override
    public void close() {
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    private Disposable doConsumeCalendarListMessages() {
        return delivery(QUEUE_NAME)
            .flatMap(this::messageConsume, DEFAULT_CONCURRENCY)
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<Void> messageConsume(AcknowledgableDelivery ackDelivery) {
        String exchangeName = ackDelivery.getEnvelope().getExchange();
        CalendarListExchange exchange = CalendarListExchange.fromString(exchangeName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported exchange name: " + exchangeName));

        return Mono.fromCallable(() -> CalendarListChangesMessage.deserialize(ackDelivery.getBody()))
            .flatMap(message -> notificationHandler.handle(exchange, message)
                .then())
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume calendar list notification message", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalendarListChangesMessage(@JsonProperty("calendarPath") String calendarPath,
                                             @JsonProperty("calendarProps") Map<String, JsonNode> calendarProps) {

        public static CalendarListChangesMessage deserialize(byte[] json) {
            try {
                return OBJECT_MAPPER.readValue(json, CalendarListChangesMessage.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to deserialize calendarListChangesMessage: " + new String(json, StandardCharsets.UTF_8), e);
            }
        }

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        private static final String ACCESS_KEY = "access";

        public CalendarURL calendarURL() {
            return CalendarURL.parse(calendarPath);
        }

        public Optional<String> access() {
            return Optional.ofNullable(calendarProps)
                .map(props -> props.get(ACCESS_KEY))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText);
        }
    }
}
