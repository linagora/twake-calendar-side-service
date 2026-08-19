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
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Named;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Sender;

public class EventAlarmConsumer implements Closeable, Startable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Logger LOGGER = LoggerFactory.getLogger(EventAlarmConsumer.class);

    public enum Queue {
        CREATE("calendar:event:alarm:created", "tcalendar:event:alarm:created", "tcalendar:event:alarm:created:dead-letter"),
        UPDATE("calendar:event:alarm:updated", "tcalendar:event:alarm:updated", "tcalendar:event:alarm:updated:dead-letter"),
        DELETE("calendar:event:alarm:deleted", "tcalendar:event:alarm:deleted", "tcalendar:event:alarm:deleted:dead-letter"),
        CANCEL("calendar:event:alarm:cancel", "tcalendar:event:alarm:cancel", "tcalendar:event:alarm:cancel:dead-letter"),
        REQUEST("calendar:event:alarm:request", "tcalendar:event:alarm:request", "tcalendar:event:alarm:request:dead-letter");

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

        public String exchangeName() {
            return exchangeName;
        }
    }

    private final ReceiverProvider receiverProvider;
    private final Sender sender;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
    private final Map<Queue, Disposable> consumeDisposableMap;
    private final EventAlarmHandler eventAlarmHandler;

    @Inject
    @Singleton
    public EventAlarmConsumer(ReactorRabbitMQChannelPool channelPool,
                              @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                              EventAlarmHandler eventAlarmHandler) {
        this.receiverProvider = channelPool::createReceiver;
        this.eventAlarmHandler = eventAlarmHandler;
        this.sender = channelPool.getSender();
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.consumeDisposableMap = new EnumMap<>(Queue.class);
    }

    public void init() {
        Arrays.stream(Queue.values())
            .forEach(queue -> RabbitMQConsumerSupport.declareBlocking(sender,
                QueueDeclaration.of(queue.exchangeName(), queue.queueName(), queue.deadLetter()),
                queueArgumentSupplier));

        start();
    }

    public void start() {
        consumeDisposableMap.put(Queue.CREATE, doConsumeCalendarEventMessages(Queue.CREATE, handlerAdd()));
        consumeDisposableMap.put(Queue.REQUEST, doConsumeCalendarEventMessages(Queue.REQUEST, handlerAddOrUpdate()));
        consumeDisposableMap.put(Queue.UPDATE, doConsumeCalendarEventMessages(Queue.UPDATE, handlerAddOrUpdate()));
        consumeDisposableMap.put(Queue.DELETE, doConsumeCalendarEventMessages(Queue.DELETE, handlerDelete()));
        consumeDisposableMap.put(Queue.CANCEL, doConsumeCalendarEventMessages(Queue.CANCEL, handlerDelete()));
    }

    public void restart() {
        close();
        consumeDisposableMap.clear();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event alarm consumer");
        consumeDisposableMap.values().forEach(disposable -> {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
        });
    }

    public interface PersistAlarmHandler {
        Mono<?> handle(CalendarAlarmMessageDTO message);
    }

    private PersistAlarmHandler handlerAdd() {
        return eventAlarmHandler::handleCreate;
    }

    private PersistAlarmHandler handlerAddOrUpdate() {
        return eventAlarmHandler::handleCreateOrUpdate;
    }

    private PersistAlarmHandler handlerDelete() {
        return eventAlarmHandler::handleDelete;
    }

    private Disposable doConsumeCalendarEventMessages(Queue queue, PersistAlarmHandler persistAlarmHandler) {
        return RabbitMQConsumerSupport.consumeOnBoundedElastic(receiverProvider, queue.queueName,
            RabbitMQConsumerSupport.ackNackWrapper(delivery -> messageConsume(delivery, persistAlarmHandler),
                LOGGER, "Error when consume calendar alarm event"));
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery, PersistAlarmHandler persistAlarmHandler) {
        return Mono.fromSupplier(Throwing.supplier(() -> OBJECT_MAPPER.readValue(ackDelivery.getBody(), CalendarAlarmMessageDTO.class)))
            .flatMap(message -> persistAlarmHandler.handle(message)
                .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed calendar alarm event successfully {} '{}'", message.getClass().getSimpleName(), message.eventPath()))));
    }
}
