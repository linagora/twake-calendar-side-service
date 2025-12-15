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

import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.tmail.saas.rabbitmq.TWPCommonRabbitMQConfiguration;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsConsumer;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsDeadLetterQueueHealthCheck;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsQueueConsumerHealthCheck;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsRabbitMQConfiguration;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsRabbitmqModule;
import com.linagora.tmail.saas.rabbitmq.settings.TWPSettingsUpdater;

public class TWPCalendarSettingsModule extends AbstractModule {
    public static final TWPSettingsConsumer.SettingsConsumerConfig CONSUMER_CONFIG = new TWPSettingsConsumer.SettingsConsumerConfig("tcalendar-settings", "tcalendar-settings-dead-letter");

    public static final Module TWP_CALENDAR_SETTINGS_AGGREGATE_MODULE = Modules.override(new TWPSettingsRabbitmqModule())
        .with(new TWPCalendarSettingsModule());

    @Provides
    @Singleton
    TWPSettingsConsumer provideTWPSettingsConsumer(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                                   @Named(TWP_INJECTION_KEY) RabbitMQConfiguration rabbitMQConfiguration,
                                                   @Named(TWP_INJECTION_KEY) TWPSettingsUpdater twpSettingsUpdater,
                                                   TWPCommonRabbitMQConfiguration twpCommonRabbitMQConfiguration,
                                                   TWPSettingsRabbitMQConfiguration twpSettingsRabbitMQConfiguration) {
        return new TWPSettingsConsumer(channelPool, rabbitMQConfiguration, twpCommonRabbitMQConfiguration,
            twpSettingsRabbitMQConfiguration, CONSUMER_CONFIG, twpSettingsUpdater);
    }

    @Provides
    @Singleton
    @Named(TWP_INJECTION_KEY)
    TWPSettingsUpdater provideTWPSettingsUpdater(UserConfigurationDAO userConfigurationDAO,
                                                 OpenPaaSUserDAO openPaaSUserDAO,
                                                 SimpleSessionProvider sessionProvider) {
        return new CalendarSettingUpdater(userConfigurationDAO, openPaaSUserDAO, sessionProvider);
    }

    @Provides
    @Singleton
    TWPSettingsQueueConsumerHealthCheck provideTWPSettingsQueueConsumerHealthCheck(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration twpRabbitMQConfiguration,
                                                                                   TWPSettingsConsumer twpSettingsConsumer) {
        return new TWPSettingsQueueConsumerHealthCheck(twpRabbitMQConfiguration, twpSettingsConsumer, CONSUMER_CONFIG.queue());
    }

    @Provides
    @Singleton
    TWPSettingsDeadLetterQueueHealthCheck provideTWPSettingsDeadLetterQueueHealthCheck(@Named(TWP_INJECTION_KEY) RabbitMQConfiguration twpRabbitMQConfiguration) {
        return new TWPSettingsDeadLetterQueueHealthCheck(twpRabbitMQConfiguration, CONSUMER_CONFIG.deadLetterQueue());
    }
}
