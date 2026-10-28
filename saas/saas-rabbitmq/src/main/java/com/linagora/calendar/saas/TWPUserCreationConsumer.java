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

import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.time.Duration;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;

import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.rabbitmq.QueueDeclaration;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;

public final class TWPUserCreationConsumer {
    public record UserCreationConsumerConfig(String queue, String deadLetterQueue) {
        public static final UserCreationConsumerConfig DEFAULT = new UserCreationConsumerConfig("tcalendar-user-created", "tcalendar-user-created-dead-letter");
    }

    private static final Duration CONSUMER_TIMEOUT = Duration.ofMinutes(10L);

    private TWPUserCreationConsumer() {
    }

    public static ManagedRabbitMQConsumer create(ReactorRabbitMQChannelPool channelPool,
                                                 RabbitMQConfiguration rabbitMQConfiguration,
                                                 TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                                 TWPUserCreationRabbitMQConfiguration userCreationRabbitMQConfiguration,
                                                 UserCreationConsumerConfig consumerConfig,
                                                 TWPUserCreationHandler handler) {
        return new ManagedRabbitMQConsumer.Factory(channelPool)
            .create(ManagedRabbitMQConsumer.Parameters.builder()
                .queueDeclaration(QueueDeclaration.builder()
                    .binding(userCreationRabbitMQConfiguration.exchange(), userCreationRabbitMQConfiguration.routingKey())
                    .queue(consumerConfig.queue())
                    .deadLetterQueue(consumerConfig.deadLetterQueue())
                    .build())
                .queueArguments(() -> queueArgumentSupplier(rabbitMQConfiguration, twpCommonRabbitMQConfiguration))
                .singleActiveConsumer()
                .consumerTimeout(CONSUMER_TIMEOUT)
                .qos(DEFAULT_CONCURRENCY)
                .handleDelivery(delivery -> handler.handleMessage(delivery.getBody()))
                .build());
    }

    private static QueueArguments.Builder queueArgumentSupplier(RabbitMQConfiguration rabbitMQConfiguration,
                                                                TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration) {
        if (!twpCommonRabbitMQConfiguration.quorumQueuesBypass()) {
            return rabbitMQConfiguration.workQueueArgumentsBuilder();
        }
        return QueueArguments.builder();
    }
}
