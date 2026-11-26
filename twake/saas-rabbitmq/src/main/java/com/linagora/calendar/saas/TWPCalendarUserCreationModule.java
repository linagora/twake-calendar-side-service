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
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;

import reactor.core.publisher.Mono;

public class TWPCalendarUserCreationModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(TWPUserCreationHandler.class).in(Singleton.class);
        bind(TWPUserCreationConsumer.class).in(Singleton.class);
    }

    @ProvidesIntoSet
    MonitoredRabbitMQConsumers twpUserCreationConsumers(@Named(TWP_INJECTION_KEY) SimpleConnectionPool twpConnectionPool,
                                                        TWPUserCreationConsumer consumer) {
        return MonitoredRabbitMQConsumers.of("TWP user creation", twpConnectionPool,
            () -> ImmutableList.of(TWPUserCreationConsumer.QUEUE),
            connection -> Mono.fromRunnable(consumer::restart));
    }

    @ProvidesIntoSet
    MonitoredDeadLetterQueue twpUserCreationDeadLetterQueue(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration twpRabbitMQConfiguration) {
        return new MonitoredDeadLetterQueue(twpRabbitMQConfiguration, TWPUserCreationConsumer.DEAD_LETTER_QUEUE);
    }

    @ProvidesIntoSet
    InitializationOperation initializeTWPUserCreationConsumer(TWPUserCreationConsumer consumer) {
        return InitilizationOperationBuilder
            .forClass(TWPUserCreationConsumer.class)
            .init(consumer::init);
    }

    @Provides
    @Singleton
    TWPUserCreationRabbitMQConfiguration provideTWPUserCreationConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return TWPUserCreationRabbitMQConfiguration.from(propertiesProvider.getConfiguration("rabbitmq"));
    }
}
