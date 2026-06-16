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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.name.Named;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.exception.CalendarSearchIndexingException;
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

public class EventIndexerConsumer implements Closeable, Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventIndexerConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    public enum Queue {
        ADD("calendar:event:created", "tcalendar:event:created:search", "tcalendar:event:created:search-dead-letter"),
        UPDATE("calendar:event:updated", "tcalendar:event:updated:search", "tcalendar:event:updated:search-dead-letter"),
        DELETE("calendar:event:deleted", "tcalendar:event:deleted:search", "tcalendar:event:deleted:search-dead-letter");
        /* Search indexing intentionally ignores:
         * - `calendar:event:request`: Sabre 4.7 also emits an equivalent `calendar:event:updated`
         * - `calendar:event:cancel`: Sabre 4.7 also emits an equivalent `calendar:event:deleted`
         * - `calendar:event:reply`: eventSearch does not rely on attendee partstat changes
         */

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
    private final Consumer<Queue> declareExchangeAndQueue;
    private final CalendarSearchService calendarSearchService;
    private final MetricFactory metricFactory;
    private final Map<Queue, Disposable> consumeDisposableMap;

    @Inject
    @Singleton
    public EventIndexerConsumer(ReactorRabbitMQChannelPool channelPool,
                                CalendarSearchService calendarSearchService,
                                @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                MetricFactory metricFactory) {
        this.receiverProvider = channelPool::createReceiver;
        this.calendarSearchService = calendarSearchService;
        this.metricFactory = metricFactory;

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

        this.consumeDisposableMap = new EnumMap<>(Queue.class);
    }

    public void init() {
        Arrays.stream(Queue.values())
            .forEach(declareExchangeAndQueue);

        start();
    }

    public void start() {
        consumeDisposableMap.put(Queue.ADD, doConsumeCalendarEventMessages(Queue.ADD, handlerAdd));
        consumeDisposableMap.put(Queue.UPDATE, doConsumeCalendarEventMessages(Queue.UPDATE, handlerAddOrUpdate));
        consumeDisposableMap.put(Queue.DELETE, doConsumeCalendarEventMessages(Queue.DELETE, handlerDelete));
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    public void close() {
        consumeDisposableMap.values().forEach(disposable -> {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        });
    }

    public interface CalendarEventHandler {
        Mono<?> handle(CalendarEventMessage calendarEventMessage);

        Mono<CalendarEventMessage> deserialize(byte[] messagesAsBytes);
    }

    private final CalendarEventHandler handlerAdd = new CalendarEventHandler() {
        @Override
        public Mono<?> handle(CalendarEventMessage calendarEventMessage) {
            return Mono.fromCallable(calendarEventMessage::extractCalendarEvents)
                .flatMap(calendarSearchService::index)
                .then();
        }

        @Override
        public Mono<CalendarEventMessage> deserialize(byte[] messagesAsBytes) {
            return Mono.fromCallable(() -> CalendarEventMessage.CreatedOrUpdated.deserialize(messagesAsBytes));
        }
    };

    private final CalendarEventHandler handlerAddOrUpdate = new CalendarEventHandler() {

        @Override
        public Mono<?> handle(CalendarEventMessage calendarEventMessage) {
            return Mono.fromCallable(calendarEventMessage::extractCalendarEvents)
                .flatMap(this::indexEvents)
                .then();
        }

        private Mono<Void> indexEvents(CalendarEvents calendarEvents) {
           if (hasRecurrenceEvents(calendarEvents)) {
                return calendarSearchService.delete(calendarEvents.calendarURL(), calendarEvents.eventUid())
                    .then(calendarSearchService.index(calendarEvents))
                    .onErrorResume(this::isVersionConflict, error -> {
                        LOGGER.info("Ignoring duplicated DAV recurring event message for eventUid {} in calendarURL {}",
                            calendarEvents.eventUid().value(), calendarEvents.calendarURL().serialize(), error);
                        return Mono.empty();
                    });
            } else {
                return calendarSearchService.index(calendarEvents);
           }
        }

        private boolean hasRecurrenceEvents(CalendarEvents calendarEvents) {
            return calendarEvents.events().size() > 1;
        }

        private boolean isVersionConflict(Throwable error) {
            return error instanceof CalendarSearchIndexingException
                && Optional.ofNullable(error.getCause())
                .map(Throwable::getMessage)
                .filter(message -> message.contains("version conflict, required seqNo"))
                .isPresent();
        }

        @Override
        public Mono<CalendarEventMessage> deserialize(byte[] messagesAsBytes) {
            return Mono.fromCallable(() -> CalendarEventMessage.CreatedOrUpdated.deserialize(messagesAsBytes));
        }
    };

    private final CalendarEventHandler handlerDelete = new CalendarEventHandler() {
        @Override
        public Mono<?> handle(CalendarEventMessage calendarEventMessage) {
            return Flux.fromIterable(((CalendarEventMessage.Deleted) calendarEventMessage).extractEventUid())
                .flatMap(eventUid -> calendarSearchService.delete(calendarEventMessage.extractCalendarURL(), eventUid)
                    .onErrorResume(error -> {
                        LOGGER.warn("Failed to delete eventUid {} from calendarURL {}",
                            eventUid.value(), calendarEventMessage.extractCalendarURL().serialize(), error);
                        return Mono.empty();
                    }))
                .then();
        }

        @Override
        public Mono<CalendarEventMessage> deserialize(byte[] messagesAsBytes) {
            return Mono.fromCallable(() -> CalendarEventMessage.Deleted.deserialize(messagesAsBytes));
        }
    };

    private Disposable doConsumeCalendarEventMessages(Queue queue, CalendarEventHandler calendarEventHandler) {
        return delivery(queue.queueName)
            .flatMap(delivery -> messageConsume(delivery,
                calendarEventHandler.deserialize(delivery.getBody()),
                calendarEventHandler), DEFAULT_CONCURRENCY)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, Mono<CalendarEventMessage> messagePublisher, CalendarEventHandler calendarEventHandler) {
        return messagePublisher
            .flatMap(message -> Mono.from(metricFactory.decoratePublisherWithTimerMetric("calendar.event.indexing",
                calendarEventHandler.handle(message)
                    .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar event message successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath))))))
            .doOnSuccess(_ -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Failed to consume calendar event message", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }
}
