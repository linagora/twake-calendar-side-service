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
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.name.Named;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Sender;

public class EventIndexerConsumer implements Closeable, Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventIndexerConsumer.class);

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
    private final Sender sender;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
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
        this.sender = channelPool.getSender();
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.consumeDisposableMap = new EnumMap<>(Queue.class);
    }

    public void init() {
        Arrays.stream(Queue.values())
            .forEach(queue -> RabbitMQConsumerSupport.declareBlocking(sender,
                QueueDeclaration.of(queue.exchangeName, queue.queueName(), queue.deadLetter()),
                queueArgumentSupplier));

        start();
    }

    public void start() {
        consumeDisposableMap.put(Queue.ADD, doConsumeCalendarEventMessages(Queue.ADD, handlerAddOrUpdate));
        consumeDisposableMap.put(Queue.UPDATE, doConsumeCalendarEventMessages(Queue.UPDATE, handlerAddOrUpdate));
        consumeDisposableMap.put(Queue.DELETE, doConsumeCalendarEventMessages(Queue.DELETE, handlerDelete));
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event indexer consumer");
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

    // Both created and updated events are indexed the same way: each occurrence (master and overridden
    // occurrences) is upserted as its own document guarded by its sequence. We intentionally do NOT delete
    // by eventUid before re-indexing recurring events: that delete bypasses the per-document sequence guard
    // and races with concurrent/reordered messages (see issue #895). Search collapses on the uid and keeps
    // the sequence-guarded master, so stale overridden occurrences are never surfaced.
    private final CalendarEventHandler handlerAddOrUpdate = new CalendarEventHandler() {
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
        return RabbitMQConsumerSupport.consumeOnBoundedElastic(receiverProvider, queue.queueName,
            RabbitMQConsumerSupport.ackNackWrapper(
                ackDelivery -> messageConsume(ackDelivery, calendarEventHandler.deserialize(ackDelivery.getBody()), calendarEventHandler),
                LOGGER, "Failed to consume calendar event message"));
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, Mono<CalendarEventMessage> messagePublisher, CalendarEventHandler calendarEventHandler) {
        return messagePublisher
            .flatMap(message -> Mono.from(metricFactory.decoratePublisherWithTimerMetric("calendar.event.indexing",
                calendarEventHandler.handle(message)
                    .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar event message successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath))))));
    }
}
