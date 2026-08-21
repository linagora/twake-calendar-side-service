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

package com.linagora.calendar.saas.contact;

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Action;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class CommonContactNotificationConsumer implements Closeable, Startable {
    public enum Queue {
        CREATE("sabre:contact:created", "tcalendar:common-contact:created", "tcalendar:common-contact:created-dead-letter", Action.ADD),
        UPDATE("sabre:contact:updated", "tcalendar:common-contact:updated", "tcalendar:common-contact:updated-dead-letter", Action.UPDATE),
        DELETE("sabre:contact:deleted", "tcalendar:common-contact:deleted", "tcalendar:common-contact:deleted-dead-letter", Action.DELETE);

        private final String exchange;
        private final String queue;
        private final String deadLetter;
        private final Action action;

        Queue(String exchange, String queue, String deadLetter, Action action) {
            this.exchange = exchange;
            this.queue = queue;
            this.deadLetter = deadLetter;
            this.action = action;
        }

        public Action action() {
            return action;
        }
    }

    private final List<ManagedRabbitMQConsumer> consumers;
    private final CommonContactPublisher publisher;
    private final CommonContactEventConverter converter;

    @Inject
    public CommonContactNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                             @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                             CommonContactPublisher publisher,
                                             CommonContactEventConverter converter) {
        this.publisher = publisher;
        this.converter = converter;
        ManagedRabbitMQConsumer.Factory factory = new ManagedRabbitMQConsumer.Factory(channelPool);
        consumers = Arrays.stream(Queue.values())
            .map(queue -> factory.create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(QueueDeclaration.builder()
                    .binding(queue.exchange, BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY)
                    .queue(queue.queue)
                    .deadLetterQueue(queue.deadLetter)
                    .build())
                .queueArguments(queueArgumentSupplier)
                .qos(DEFAULT_CONCURRENCY)
                .concurrency(DEFAULT_CONCURRENCY)
                .handleDelivery(delivery -> handleDelivery(queue, delivery))
                .build()))
            .toList();
    }

    public void init() {
        consumers.forEach(ManagedRabbitMQConsumer::init);
    }

    public void restart() {
        consumers.forEach(ManagedRabbitMQConsumer::restart);
    }

    @Override
    @PreDestroy
    public void close() {
        consumers.forEach(ManagedRabbitMQConsumer::close);
    }

    private Mono<Void> handleDelivery(Queue queue, AcknowledgableDelivery delivery) {
        return Mono.fromCallable(() -> SabreContactNotificationDTO.deserialize(delivery.getBody()))
            .flatMap(notificationDTO -> converter.convert(queue, notificationDTO))
            .flatMap(publisher::publish);
    }

}
