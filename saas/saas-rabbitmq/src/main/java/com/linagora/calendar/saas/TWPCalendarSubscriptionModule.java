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
import java.util.List;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.util.Modules;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSDomainSubscriptionConsumer;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionConsumer;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionDeadLetterQueueHealthCheck;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionModule;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionQueueConsumerHealthCheck;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSSubscriptionRabbitMQConfiguration;

public class TWPCalendarSubscriptionModule extends AbstractModule {
    public static final SaaSSubscriptionConsumer.SubscriptionConsumerConfig SUBSCRIPTION_CONSUMER_CONFIG = new SaaSSubscriptionConsumer.SubscriptionConsumerConfig("tcalendar-saas-subscription", "tcalendar-saas-subscription-dead-letter");
    public static final SaaSDomainSubscriptionConsumer.DomainSubscriptionConsumerConfig DOMAIN_SUBSCRIPTION_CONSUMER_CONFIG = new SaaSDomainSubscriptionConsumer.DomainSubscriptionConsumerConfig("tcalendar-saas-domain-subscription", "tcalendar-saas-domain-subscription-dead-letter");
    public static final List<String> SUBSCRIPTION_QUEUES = ImmutableList.of(SUBSCRIPTION_CONSUMER_CONFIG.queue(), DOMAIN_SUBSCRIPTION_CONSUMER_CONFIG.queue());
    public static final List<String> SUBSCRIPTION_DEAD_LETTER_QUEUES = ImmutableList.of(SUBSCRIPTION_CONSUMER_CONFIG.deadLetterQueue(), DOMAIN_SUBSCRIPTION_CONSUMER_CONFIG.deadLetterQueue());


    public static final Module TWP_CALENDAR_SUBSCRIPTION_AGGREGATE_MODULE = Modules.override(new SaaSSubscriptionModule())
        .with(new TWPCalendarSubscriptionModule());

    @Provides
    @Singleton
    SaaSSubscriptionConsumer provideSaaSSubscriptionConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                                             @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                                             TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                                             SaaSSubscriptionRabbitMQConfiguration saaSSubscriptionRabbitMQConfiguration,
                                                             SaaSUserProvisioner userProvisioner) {
        return new SaaSSubscriptionConsumer(channelPool, rabbitMQConfiguration, twpCommonRabbitMQConfiguration,
            saaSSubscriptionRabbitMQConfiguration,
            new SaaSUserSubscriptionHandler(userProvisioner),
            SUBSCRIPTION_CONSUMER_CONFIG);
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
            new SaaSDomainSubscriptionHandler(domainDAO),
            DOMAIN_SUBSCRIPTION_CONSUMER_CONFIG);
    }

    @Provides
    @Singleton
    SaaSSubscriptionQueueConsumerHealthCheck provideSaaSSubscriptionQueueConsumerHealthCheck(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration twpRabbitMQConfiguration,
                                                                                             SaaSSubscriptionConsumer saaSSubscriptionConsumer,
                                                                                             SaaSDomainSubscriptionConsumer saaSDomainSubscriptionConsumer) {
        return new SaaSSubscriptionQueueConsumerHealthCheck(twpRabbitMQConfiguration, saaSSubscriptionConsumer, saaSDomainSubscriptionConsumer, SUBSCRIPTION_QUEUES);
    }

    @Provides
    @Singleton
    SaaSSubscriptionDeadLetterQueueHealthCheck provideSaaSSubscriptionDeadLetterQueueHealthCheck(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration twpRabbitMQConfiguration) {
        return new SaaSSubscriptionDeadLetterQueueHealthCheck(twpRabbitMQConfiguration, SUBSCRIPTION_DEAD_LETTER_QUEUES);
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
