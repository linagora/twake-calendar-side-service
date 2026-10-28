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

import java.io.FileNotFoundException;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.MonitoredDeadLetterQueue;
import org.apache.james.backends.rabbitmq.MonitoredRabbitMQConsumers;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.saas.TWPUserCreationConsumer.UserCreationConsumerConfig;
import com.linagora.tmail.rabbitmq.ManagedRabbitMQConsumer;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;

import reactor.core.publisher.Mono;

public class TWPCalendarUserCreationModule extends AbstractModule {
    public static final UserCreationConsumerConfig CONSUMER_CONFIG = UserCreationConsumerConfig.DEFAULT;

    private static final String TWP_USER_CREATION_CONSUMER = "twp-user-creation";

    @Provides
    @Singleton
    @Named(TWP_USER_CREATION_CONSUMER)
    ManagedRabbitMQConsumer provideTWPUserCreationConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                                           @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                                           TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                                           TWPUserCreationRabbitMQConfiguration twpUserCreationRabbitMQConfiguration,
                                                           SaaSUserProvisioner userProvisioner) {
        return TWPUserCreationConsumer.create(channelPool, rabbitMQConfiguration, twpCommonRabbitMQConfiguration,
            twpUserCreationRabbitMQConfiguration, CONSUMER_CONFIG, new TWPUserCreationHandler(userProvisioner));
    }

    @ProvidesIntoSet
    MonitoredRabbitMQConsumers twpUserCreationConsumers(@Named(TWP_INJECTION_KEY) SimpleConnectionPool twpConnectionPool,
                                                        @Named(TWP_USER_CREATION_CONSUMER) ManagedRabbitMQConsumer twpUserCreationConsumer) {
        return MonitoredRabbitMQConsumers.of("TWP user creation", twpConnectionPool,
            () -> ImmutableList.of(CONSUMER_CONFIG.queue()),
            connection -> Mono.fromRunnable(twpUserCreationConsumer::restart));
    }

    @ProvidesIntoSet
    MonitoredDeadLetterQueue twpUserCreationDeadLetterQueue(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration twpRabbitMQConfiguration) {
        return new MonitoredDeadLetterQueue(twpRabbitMQConfiguration, CONSUMER_CONFIG.deadLetterQueue());
    }

    @ProvidesIntoSet
    InitializationOperation initializeTWPUserCreationConsumer(@Named(TWP_USER_CREATION_CONSUMER) ManagedRabbitMQConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(ManagedRabbitMQConsumer.class)
            .init(instance::init);
    }

    @Provides
    @Singleton
    TWPUserCreationRabbitMQConfiguration provideTWPUserCreationConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return TWPUserCreationRabbitMQConfiguration.from(propertiesProvider.getConfiguration("rabbitmq"));
    }
}
