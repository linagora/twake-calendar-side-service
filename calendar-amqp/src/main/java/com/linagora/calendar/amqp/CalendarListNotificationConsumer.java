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
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.QueueArguments.Builder;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.core.Username;
import org.apache.james.lifecycle.api.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.name.Named;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarListNotificationConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    public static final String QUEUE_NAME = "tcalendar:calendar:list:notification";
    public static final String DEAD_LETTER_QUEUE = QUEUE_NAME + ":dead-letter";

    private static final List<String> EXCHANGES = List.of(
        "calendar:subscription:created",
        "calendar:subscription:updated",
        "calendar:subscription:deleted",
        "calendar:calendar:created",
        "calendar:calendar:updated",
        "calendar:calendar:deleted");

    private final ReceiverProvider receiverProvider;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final ReactorRabbitMQChannelPool channelPool;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;

    private Disposable consumeDisposable;

    @Inject
    public CalendarListNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                            @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                            OpenPaaSUserDAO openPaaSUserDAO) {
        this.channelPool = channelPool;
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.receiverProvider = channelPool::createReceiver;
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    private static void declareExchangeAndQueue(Supplier<Builder> queueArgumentSupplier, Sender sender) {
        Flux.concat(Flux.fromIterable(EXCHANGES)
                    .flatMap(exchange -> sender.declareExchange(ExchangeSpecification.exchange(exchange)
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
                        .exchange(exchange)
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
        String rawPayload = new String(ackDelivery.getBody(), StandardCharsets.UTF_8);

        return Mono.fromCallable(() -> CalendarListChangesMessage.deserialize(ackDelivery.getBody()))
            .map(CalendarListChangesMessage::calendarURL)
            .flatMap(calendarURL -> resolveUser(calendarURL)
                .doOnNext(username -> LOGGER.debug("Consumed calendar list change exchangeName={} rawPayload={} calendarPath={} targetUser={}",
                    exchangeName, rawPayload, calendarURL.asUri(), username.asString()))
                .then())
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume calendar list notification message", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private Mono<Username> resolveUser(CalendarURL calendarURL) {
        return openPaaSUserDAO.retrieve(calendarURL.base())
            .map(OpenPaaSUser::username)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("OpenPaaS user not found for id " + calendarURL.base().value())));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalendarListChangesMessage(
        @JsonProperty(value = "calendarPath", required = true) String calendarPath) {
        public static CalendarListChangesMessage deserialize(byte[] json) {
            try {
                return OBJECT_MAPPER.readValue(json, CalendarListChangesMessage.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to deserialize calendarListChangesMessage: " + new String(json, StandardCharsets.UTF_8), e);
            }
        }

        public CalendarURL calendarURL() {
            return CalendarURL.parse(calendarPath);
        }
    }
}
