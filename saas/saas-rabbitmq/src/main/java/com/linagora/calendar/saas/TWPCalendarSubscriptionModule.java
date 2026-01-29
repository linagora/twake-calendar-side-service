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
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSDomainSubscriptionConsumer;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionConsumer;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionDeadLetterQueueHealthCheck;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionQueueConsumerHealthCheck;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionRabbitMQConfiguration;

public class TWPCalendarSubscriptionModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder.newSetBinder(binder(), HealthCheck.class).addBinding()
            .to(SaaSSubscriptionDeadLetterQueueHealthCheck.class);
        Multibinder.newSetBinder(binder(), HealthCheck.class).addBinding()
            .to(SaaSSubscriptionQueueConsumerHealthCheck.class);
    }

    @Provides
    @Singleton
    SaaSSubscriptionConsumer provideSaaSSubscriptionConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                                             @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                                             TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                                             SaaSSubscriptionRabbitMQConfiguration saaSSubscriptionRabbitMQConfiguration,
                                                             OpenPaaSUserDAO userDAO,
                                                             OpenPaaSDomainDAO domainDAO,
                                                             CardDavClient cardDavClient) {
        return new SaaSSubscriptionConsumer(channelPool, rabbitMQConfiguration, twpCommonRabbitMQConfiguration,
            saaSSubscriptionRabbitMQConfiguration,
            new SaaSUserSubscriptionHandler(userDAO, domainDAO, cardDavClient));
    }

    @Provides
    @Singleton
    SaaSDomainSubscriptionConsumer provideSaaSDomainSubscriptionConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                                                         @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                                                         TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                                                         SaaSSubscriptionRabbitMQConfiguration saaSSubscriptionRabbitMQConfiguration,
                                                                         OpenPaaSDomainDAO domainDAO) {
        return new SaaSDomainSubscriptionConsumer(channelPool, rabbitMQConfiguration, twpCommonRabbitMQConfiguration,
            saaSSubscriptionRabbitMQConfiguration,
            new SaaSDomainSubscriptionHandler(domainDAO));
    }

    @ProvidesIntoSet
    public InitializationOperation initializeSaaSSubscriptionConsumer(SaaSSubscriptionConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(SaaSSubscriptionConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    public InitializationOperation initializeSaaSDomainSubscriptionConsumer(SaaSDomainSubscriptionConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(SaaSDomainSubscriptionConsumer.class)
            .init(instance::init);
    }

    @Provides
    @Singleton
    SaaSSubscriptionRabbitMQConfiguration provideSaaSSubscriptionRabbitMQConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return SaaSSubscriptionRabbitMQConfiguration.from(propertiesProvider.getConfiguration("rabbitmq"));
    }
}
