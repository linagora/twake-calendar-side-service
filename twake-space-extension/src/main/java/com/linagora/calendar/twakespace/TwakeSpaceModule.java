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

package com.linagora.calendar.twakespace;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;

import java.io.FileNotFoundException;
import java.util.List;

import jakarta.inject.Named;

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

public class TwakeSpaceModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(TwakeSpaceRepository.class).in(Scopes.SINGLETON);
        bind(SpaceTeamCalendars.class).in(Scopes.SINGLETON);
        bind(TeamCalendarSharing.class).in(Scopes.SINGLETON);
        bind(TwakeSpaceProvisioner.class).in(Scopes.SINGLETON);
        bind(CalendarActivity.class).in(Scopes.SINGLETON);
        bind(ActivityPublisher.class).in(Scopes.SINGLETON);
        bind(SpaceEventConsumer.class).in(Scopes.SINGLETON);
        bind(CalendarActivityConsumer.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    TwakeSpaceConfiguration configuration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return TwakeSpaceConfiguration.from(propertiesProvider);
    }

    @ProvidesIntoSet
    InitializationOperation initializeActivityPublisher(ActivityPublisher publisher) {
        return InitilizationOperationBuilder
            .forClass(ActivityPublisher.class)
            .init(publisher::init);
    }

    @ProvidesIntoSet
    InitializationOperation initializeSpaceEventConsumer(SpaceEventConsumer consumer) {
        return InitilizationOperationBuilder
            .forClass(SpaceEventConsumer.class)
            .init(consumer::init)
            .requires(List.of(ActivityPublisher.class));
    }

    @ProvidesIntoSet
    InitializationOperation initializeCalendarActivityConsumer(CalendarActivityConsumer consumer) {
        return InitilizationOperationBuilder
            .forClass(CalendarActivityConsumer.class)
            .init(consumer::init)
            .requires(List.of(ActivityPublisher.class));
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideCalendarActivityReconnectionHandler(CalendarActivityConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handling reconnection for CalendarActivityConsumer");
    }

    @ProvidesIntoSet
    MonitoredRabbitMQConsumers spaceEventConsumers(@Named(TWP_INJECTION_KEY) SimpleConnectionPool connectionPool, SpaceEventConsumer consumer) {
        return MonitoredRabbitMQConsumers.of("twake space queue", connectionPool, () -> List.of(SpaceEventConsumer.QUEUE),
            connection -> Mono.fromRunnable(consumer::restart));
    }

    @ProvidesIntoSet
    MonitoredRabbitMQConsumers calendarActivityConsumers(SimpleConnectionPool connectionPool, CalendarActivityConsumer consumer) {
        return MonitoredRabbitMQConsumers.of("twake space calendar queue", connectionPool, () -> List.of(CalendarActivityConsumer.QUEUE),
            connection -> Mono.fromRunnable(consumer::restart));
    }

    @ProvidesIntoSet
    MonitoredDeadLetterQueue spaceEventDeadLetterQueue(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration) {
        return new MonitoredDeadLetterQueue(rabbitMQConfiguration, SpaceEventConsumer.DEAD_LETTER_QUEUE);
    }

    @ProvidesIntoSet
    MonitoredDeadLetterQueue calendarActivityDeadLetterQueue(RabbitMQConfiguration rabbitMQConfiguration) {
        return new MonitoredDeadLetterQueue(rabbitMQConfiguration, CalendarActivityConsumer.DEAD_LETTER_QUEUE);
    }
}
