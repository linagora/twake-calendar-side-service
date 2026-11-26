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

package com.linagora.calendar.saas;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.time.Duration;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;

public class TWPUserCreationConsumer implements Closeable, Startable {
    public static final String QUEUE = "tcalendar-user-created";
    public static final String DEAD_LETTER_QUEUE = "tcalendar-user-created-dead-letter";

    private static final Duration CONSUMER_TIMEOUT = Duration.ofMinutes(10L);

    private final ManagedRabbitMQConsumer consumer;

    @Inject
    public TWPUserCreationConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                   @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                   TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                   TWPUserCreationRabbitMQConfiguration configuration,
                                   TWPUserCreationHandler handler) {
        consumer = new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(QueueDeclaration.builder()
                    .binding(configuration.exchange(), configuration.routingKey())
                    .queue(QUEUE)
                    .deadLetterQueue(DEAD_LETTER_QUEUE)
                    .build())
                .queueArguments(() -> twpCommonRabbitMQConfiguration.quorumQueuesBypass()
                    ? QueueArguments.builder()
                    : rabbitMQConfiguration.workQueueArgumentsBuilder())
                .singleActiveConsumer()
                .consumerTimeout(CONSUMER_TIMEOUT)
                .qos(DEFAULT_CONCURRENCY)
                .handleDelivery(delivery -> handler.handleMessage(delivery.getBody()))
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
}
