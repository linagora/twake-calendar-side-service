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

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;

import java.io.Closeable;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.linagora.calendar.twakespace.SpaceMeetings;
import com.linagora.calendar.twakespace.TwakeSpaceConfiguration;
import com.linagora.calendar.twakespace.model.MeetingRequest;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

public class MeetingRequestConsumer implements Closeable, Startable {
    public static final String QUEUE = "tcalendar:twake-space-meeting";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:twake-space-meeting-dead-letter";

    private final ManagedRabbitMQConsumer consumer;
    private final SpaceMeetings spaceMeetings;

    @Inject
    public MeetingRequestConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                  @Named(TWP_INJECTION_KEY) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                  TwakeSpaceConfiguration configuration,
                                  SpaceMeetings spaceMeetings) {
        this.spaceMeetings = spaceMeetings;
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(QueueDeclaration.builder()
                    .queue(QUEUE)
                    .deadLetterQueue(DEAD_LETTER_QUEUE)
                    .binding(configuration.commandExchange(), MeetingRequest.TYPE)
                    .build())
                .queueArguments(queueArgumentSupplier)
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
        return Mono.fromCallable(() -> MeetingRequest.deserialize(delivery.getBody()))
            .flatMap(spaceMeetings::schedule);
    }
}
