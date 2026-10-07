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

package com.linagora.calendar.twakespace.amqp;

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;

import java.io.Closeable;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.linagora.calendar.twakespace.CalendarActivity;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

// A single active consumer, so that the cards of an event reach the feed in the order of its changes.
public class CalendarActivityConsumer implements Closeable, Startable {
    public static final String QUEUE = "tcalendar:twake-space-calendar";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:twake-space-calendar-dead-letter";

    private final ManagedRabbitMQConsumer consumer;
    private final CalendarActivity calendarActivity;
    private final ActivityPublisher activityPublisher;

    @Inject
    public CalendarActivityConsumer(ReactorRabbitMQChannelPool channelPool,
                                    @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                    CalendarActivity calendarActivity,
                                    ActivityPublisher activityPublisher) {
        this.calendarActivity = calendarActivity;
        this.activityPublisher = activityPublisher;
        QueueDeclaration.Builder queueDeclaration = QueueDeclaration.builder()
            .queue(QUEUE)
            .deadLetterQueue(DEAD_LETTER_QUEUE);
        CalendarActivity.EXCHANGES.forEach(exchange -> queueDeclaration.binding(exchange, BuiltinExchangeType.FANOUT, EMPTY_ROUTING_KEY));
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(queueDeclaration.build())
                .queueArguments(queueArgumentSupplier)
                .singleActiveConsumer()
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
        return calendarActivity.handle(delivery.getEnvelope().getExchange(), delivery.getBody())
            .flatMap(activityPublisher::publish);
    }
}
