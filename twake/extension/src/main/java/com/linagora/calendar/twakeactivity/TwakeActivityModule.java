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

package com.linagora.calendar.twakeactivity;

import java.io.FileNotFoundException;
import java.util.List;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.MonitoredDeadLetterQueue;
import org.apache.james.backends.rabbitmq.MonitoredRabbitMQConsumers;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.amqp.ConsumerReconnectionHandler;

import reactor.core.publisher.Mono;

public class TwakeActivityModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(UserActivities.class).in(Scopes.SINGLETON);
        bind(UserActivityPublisher.class).in(Scopes.SINGLETON);
        bind(UserActivityConsumer.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    TwakeActivityConfiguration configuration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return TwakeActivityConfiguration.from(propertiesProvider);
    }

    @ProvidesIntoSet
    InitializationOperation initializeUserActivityPublisher(UserActivityPublisher publisher) {
        return InitilizationOperationBuilder
            .forClass(UserActivityPublisher.class)
            .init(publisher::init);
    }

    @ProvidesIntoSet
    InitializationOperation initializeUserActivityConsumer(UserActivityConsumer consumer) {
        return InitilizationOperationBuilder
            .forClass(UserActivityConsumer.class)
            .init(consumer::init)
            .requires(List.of(UserActivityPublisher.class));
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideUserActivityReconnectionHandler(UserActivityConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handling reconnection for UserActivityConsumer");
    }

    @ProvidesIntoSet
    MonitoredRabbitMQConsumers userActivityConsumers(SimpleConnectionPool connectionPool, UserActivityConsumer consumer) {
        return MonitoredRabbitMQConsumers.of("twake activity queue", connectionPool, () -> List.of(UserActivityConsumer.QUEUE),
            connection -> Mono.fromRunnable(consumer::restart));
    }

    @ProvidesIntoSet
    MonitoredDeadLetterQueue userActivityDeadLetterQueue(RabbitMQConfiguration rabbitMQConfiguration) {
        return new MonitoredDeadLetterQueue(rabbitMQConfiguration, UserActivityConsumer.DEAD_LETTER_QUEUE);
    }
}
