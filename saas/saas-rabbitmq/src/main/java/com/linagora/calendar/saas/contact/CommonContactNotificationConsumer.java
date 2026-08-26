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
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
    private enum ContactNotificationExchange {
        CREATE("sabre:contact:created", Action.ADD),
        UPDATE("sabre:contact:updated", Action.UPDATE),
        DELETE("sabre:contact:deleted", Action.DELETE);

        private final String exchange;
        private final Action action;

        ContactNotificationExchange(String exchange, Action action) {
            this.exchange = exchange;
            this.action = action;
        }

        static Optional<ContactNotificationExchange> from(String exchange) {
            return Stream.of(values())
                .filter(contactExchange -> contactExchange.exchange.equals(exchange))
                .findFirst();
        }
    }

    private static final String QUEUE = "tcalendar:common-contact";
    private static final String DEAD_LETTER_QUEUE = "tcalendar:common-contact:dead-letter";

    private final ManagedRabbitMQConsumer consumer;
    private final CommonContactPublisher publisher;
    private final CommonContactEventConverter converter;

    @Inject
    public CommonContactNotificationConsumer(ReactorRabbitMQChannelPool channelPool,
                                             @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                             CommonContactPublisher publisher,
                                             CommonContactEventConverter converter) {
        this.publisher = publisher;
        this.converter = converter;
        QueueDeclaration.Builder queueDeclaration = QueueDeclaration.builder()
            .queue(QUEUE)
            .deadLetterQueue(DEAD_LETTER_QUEUE);
        Stream.of(ContactNotificationExchange.values())
            .forEach(exchange -> queueDeclaration.binding(exchange.exchange, BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY));
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(queueDeclaration.build())
                .queueArguments(queueArgumentSupplier)
                .qos(DEFAULT_CONCURRENCY)
                .concurrency(DEFAULT_CONCURRENCY)
                .handleDelivery(this::handleDelivery)
                .build());
    }

    public void init() {
        consumer.init();
    }

    public void restart() {
        consumer.restart();
    }

    @Override
    @PreDestroy
    public void close() {
        consumer.close();
    }

    private Mono<Void> handleDelivery(AcknowledgableDelivery delivery) {
        Action action = ContactNotificationExchange.from(delivery.getEnvelope().getExchange())
            .orElseThrow(() -> new IllegalArgumentException("Unsupported contact notification exchange: " + delivery.getEnvelope().getExchange()))
            .action;
        return Mono.fromCallable(() -> SabreContactNotificationDTO.deserialize(delivery.getBody()))
            .flatMap(notificationDTO -> converter.convert(action, notificationDTO))
            .flatMap(publisher::publish);
    }

}
