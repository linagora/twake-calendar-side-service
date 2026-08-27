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

package com.linagora.calendar.app.modules;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;

import jakarta.inject.Named;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.amqp.ConsumerReconnectionHandler;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration;
import com.linagora.calendar.saas.contact.CollectedContactUpdateCalculator;
import com.linagora.calendar.saas.contact.CollectedContactsConsumer;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactNotificationConsumer;
import com.linagora.calendar.saas.contact.CommonContactPublisher;
import com.linagora.calendar.saas.contact.CommonContactsConfiguration;

public class CommonContactsModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(CommonContactEventConverter.class).in(Singleton.class);
        bind(CommonContactPublisher.class).in(Singleton.class);
        bind(CommonContactNotificationConsumer.class).in(Singleton.class);
        bind(CollectedContactUpdateCalculator.class).in(Singleton.class);
        bind(CollectedContactsConsumer.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    CommonContactsConfiguration configuration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return CommonContactsConfiguration.from(propertiesProvider);
    }

    @ProvidesIntoSet
    InitializationOperation initializeCommonContactPublisher(CommonContactPublisher publisher) {
        return InitilizationOperationBuilder
            .forClass(CommonContactPublisher.class)
            .init(publisher::init);
    }

    @ProvidesIntoSet
    InitializationOperation initializeCommonContactNotificationConsumer(CommonContactNotificationConsumer consumer) {
        return InitilizationOperationBuilder
            .forClass(CommonContactNotificationConsumer.class)
            .init(consumer::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideCommonContactNotificationReconnectionHandler(
        CommonContactNotificationConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart,
            "Error while handling reconnection for CommonContactNotificationConsumer");
    }

    @ProvidesIntoSet
    InitializationOperation initializeCollectedContactsConsumer(CollectedContactsConsumer consumer) {
        return InitilizationOperationBuilder
            .forClass(CollectedContactsConsumer.class)
            .init(consumer::init);
    }

    @Provides
    @Singleton
    @Named(TWP_INJECTION_KEY)
    ScheduledReconnectionHandler provideTwpScheduledReconnectionHandler(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                                                        @Named(TWP_INJECTION_KEY) SimpleConnectionPool connectionPool,
                                                                        ScheduledReconnectionHandlerConfiguration configuration,
                                                                        CollectedContactsConsumer consumer) {
        return new ScheduledReconnectionHandler(Set.of(new ConsumerReconnectionHandler(consumer::restart,
            "Error while handling reconnection for CollectedContactsConsumer")),
            rabbitMQConfiguration, connectionPool, configuration, List.of(CollectedContactsConsumer.QUEUE));
    }

    @ProvidesIntoSet
    InitializationOperation startTwpScheduledReconnectionHandler(@Named(TWP_INJECTION_KEY) ScheduledReconnectionHandler scheduledReconnectionHandler) {
        return InitilizationOperationBuilder
            .forClass(ScheduledReconnectionHandler.class)
            .init(scheduledReconnectionHandler::start);
    }
}
