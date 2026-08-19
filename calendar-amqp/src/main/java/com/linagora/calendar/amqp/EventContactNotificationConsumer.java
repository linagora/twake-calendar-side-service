/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  This file is subject to the Affero Gnu Public License           *
 *  version 3.                                                      *
 ********************************************************************/

package com.linagora.calendar.amqp;

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.AddressBookChangeEvent;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.AddressBookURLRegistrationKey;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Sender;

public class EventContactNotificationConsumer implements Closeable, Startable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(EventContactNotificationConsumer.class);

    public enum Queue {
        CREATE("sabre:contact:created", "tcalendar:contact:created:notification", "tcalendar:contact:created:notification-dead-letter"),
        UPDATE("sabre:contact:updated", "tcalendar:contact:updated:notification", "tcalendar:contact:updated:notification-dead-letter"),
        DELETE("sabre:contact:deleted", "tcalendar:contact:deleted:notification", "tcalendar:contact:deleted:notification-dead-letter");

        private final String exchangeName;
        private final String queueName;
        private final String deadLetter;

        Queue(String exchangeName, String queueName, String deadLetter) {
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
    private final Sender sender;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
    private final Map<Queue, Disposable> consumeDisposableMap;
    private final EventBus eventBus;

    @Inject
    public EventContactNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                            @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                            EventBus eventBus) {
        receiverProvider = channelPool::createReceiver;
        this.eventBus = eventBus;
        this.sender = channelPool.getSender();
        this.queueArgumentSupplier = queueArgumentSupplier;
        consumeDisposableMap = new EnumMap<>(Queue.class);
    }

    public void init() {
        Arrays.stream(Queue.values())
            .forEach(queue -> RabbitMQConsumerSupport.declareBlocking(sender,
                QueueDeclaration.of(queue.exchangeName(), queue.queueName(), queue.deadLetter()),
                queueArgumentSupplier));
        start();
    }

    public void start() {
        Arrays.stream(Queue.values()).forEach(queue -> consumeDisposableMap.put(queue, doConsumeContactMessages(queue)));
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        consumeDisposableMap.values().forEach(disposable -> {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        });
    }

    private Disposable doConsumeContactMessages(Queue queue) {
        return RabbitMQConsumerSupport.consume(receiverProvider, queue.queueName,
            RabbitMQConsumerSupport.ackNackWrapper(this::messageConsume,
                LOGGER, "Error when consuming contact notification event"));
    }

    private Mono<Void> messageConsume(AcknowledgableDelivery ackDelivery) {
        return Mono.fromCallable(() -> getAddressBookURL(ackDelivery.getBody()))
            .flatMap(addressBookURL -> eventBus.dispatch(
                    new AddressBookChangeEvent(Event.EventId.random(), addressBookURL),
                    new AddressBookURLRegistrationKey(addressBookURL))
                .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed contact notification event for {}", addressBookURL.asUri()))));
    }

    private AddressBookURL getAddressBookURL(byte[] json) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        String path = root.path("path").asText(null);
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("Missing required field 'path' in contact notification payload");
        }
        return AddressBookURL.parse(path);
    }
}
