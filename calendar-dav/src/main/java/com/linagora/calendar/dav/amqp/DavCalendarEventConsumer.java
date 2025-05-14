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

package com.linagora.calendar.dav.amqp;

import static com.linagora.calendar.dav.amqp.DavCalendarEventModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;

import java.io.Closeable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.apache.james.vacation.api.AccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.name.Named;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

public class DavCalendarEventConsumer implements Closeable, Startable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DavCalendarEventConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;

    enum Queue {
        ADD("calendar:event:created", "tcalendar:event:created", "tcalendar:event:created-dead-letter"),
        UPDATE("calendar:event:updated", "tcalendar:event:updated", "tcalendar:event:updated-dead-letter"),
        DELETE("calendar:event:deleted", "tcalendar:event:deleted", "tcalendar:event:deleted-dead-letter"),
        CANCEL("calendar:event:cancel", "tcalendar:event:cancel", "tcalendar:event:cancel-dead-letter"),
        REQUEST("calendar:event:request", "tcalendar:event:request", "tcalendar:event:request-dead-letter");
        /* Ignored the `calendar:event:reply`, which is triggered exclusively when an attendee updates their participation status (partstat).
        Since eventSearch does not rely on partstat, this queue is not required. */

        private final String exchangeName;
        private final String queueName;
        private final String deadLetter;

        Queue(String exchangeName, String queueName, String deadLetter) {
            this.exchangeName = exchangeName;
            this.queueName = queueName;
            this.deadLetter = deadLetter;
        }
    }

    private final ReceiverProvider receiverProvider;
    private final Consumer<Queue> declareExchangeAndQueue;
    private final CalendarSearchService calendarSearchService;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final Map<Queue, Disposable> consumeDisposableMap;

    @Inject
    public DavCalendarEventConsumer(ReactorRabbitMQChannelPool channelPool,
                                    CalendarSearchService calendarSearchService,
                                    OpenPaaSUserDAO openPaaSUserDAO,
                                    @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.receiverProvider = channelPool::createReceiver;
        this.calendarSearchService = calendarSearchService;
        this.openPaaSUserDAO = openPaaSUserDAO;

        Sender sender = channelPool.getSender();
        this.declareExchangeAndQueue = eventQueue -> Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(eventQueue.exchangeName)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(eventQueue.deadLetter)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
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
        consumeDisposableMap.put(Queue.ADD, doConsumeCalendarEventMessages(Queue.ADD, handlerIndex));
        consumeDisposableMap.put(Queue.UPDATE, doConsumeCalendarEventMessages(Queue.UPDATE, handlerIndex));
        consumeDisposableMap.put(Queue.DELETE, doConsumeCalendarEventMessages(Queue.DELETE, handlerDelete));
        consumeDisposableMap.put(Queue.CANCEL, doConsumeCalendarEventMessages(Queue.CANCEL, handlerDelete));
        consumeDisposableMap.put(Queue.REQUEST, doConsumeCalendarEventMessages(Queue.REQUEST, handlerIndex));
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
        Mono<?> handle(AccountId ownerAccountId, CalendarEventMessage calendarEventMessage);

        Mono<CalendarEventMessage> deserialize(byte[] messagesAsBytes);
    }

    private final CalendarEventHandler handlerIndex = new CalendarEventHandler() {
        @Override
        public Mono<?> handle(AccountId ownerAccountId, CalendarEventMessage calendarEventMessage) {
            return Mono.fromCallable(calendarEventMessage::extractCalendarEvents)
                .flatMap(calendarEvents -> calendarSearchService.index(ownerAccountId, calendarEvents))
                .then();
        }

        @Override
        public Mono<CalendarEventMessage> deserialize(byte[] messagesAsBytes) {
            return Mono.fromCallable(() -> CalendarEventMessage.CreatedOrUpdated.deserialize(messagesAsBytes));
        }
    };

    private final CalendarEventHandler handlerDelete = new CalendarEventHandler() {
        @Override
        public Mono<?> handle(AccountId ownerAccountId, CalendarEventMessage calendarEventMessage) {
            return Flux.fromIterable(((CalendarEventMessage.Deleted) calendarEventMessage).extractEventUid())
                .flatMap(eventUid -> calendarSearchService.delete(ownerAccountId, eventUid)
                    .onErrorResume(error -> {
                        LOGGER.warn("Failed to delete eventId: {} for accountId {} ", eventUid.value(), ownerAccountId.getIdentifier(), error);
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
                calendarEventHandler))
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, Mono<CalendarEventMessage> messagePublisher, CalendarEventHandler calendarEventHandler) {
        return messagePublisher
            .flatMap(message ->
                getAccountId(message.extractCalendarURL().base())
                    .flatMap(accountId -> calendarEventHandler.handle(accountId, message))
                    .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar event successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath))))
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume calendar event", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private Mono<AccountId> getAccountId(OpenPaaSId openpaasUserId) {
        return openPaaSUserDAO.retrieve(openpaasUserId)
            .map(openPaaSUser -> AccountId.fromUsername(openPaaSUser.username()))
            .switchIfEmpty(Mono.error(new CalendarEventConsumerException("Unable to find user with id '%s'".formatted(openpaasUserId.value()))));
    }
}
