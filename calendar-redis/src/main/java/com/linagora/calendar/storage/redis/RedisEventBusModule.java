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

package com.linagora.calendar.storage.redis;

import java.io.FileNotFoundException;

import jakarta.inject.Singleton;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.events.CalendarEventSerializer;
import org.apache.james.events.CalendarRedisEventBus;
import org.apache.james.events.CalendarURLRegistrationKeyFactory;
import org.apache.james.events.EventBus;
import org.apache.james.events.EventBusId;
import org.apache.james.events.EventSerializer;
import org.apache.james.events.RedisEventBusConfiguration;
import org.apache.james.events.RetryBackoffConfiguration;
import org.apache.james.events.RoutingKeyConverter;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.multibindings.ProvidesIntoSet;

public class RedisEventBusModule extends AbstractModule {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RedisEventBusModule.class);

    @Override
    protected void configure() {
        bind(RetryBackoffConfiguration.class).toInstance(RetryBackoffConfiguration.DEFAULT);
        bind(EventSerializer.class).to(CalendarEventSerializer.class);
        bind(RoutingKeyConverter.class).toInstance(new RoutingKeyConverter(ImmutableSet.of(new CalendarURLRegistrationKeyFactory())));
        bind(EventBusId.class).toInstance(EventBusId.random());
        bind(CalendarRedisEventBus.class).in(Scopes.SINGLETON);
        bind(EventBus.class).to(CalendarRedisEventBus.class);
    }

    @ProvidesIntoSet
    InitializationOperation initializeRedisEventBus(CalendarRedisEventBus instance) {
        return InitilizationOperationBuilder
            .forClass(CalendarRedisEventBus.class)
            .init(instance::start);
    }

    @Provides
    @Singleton
    RedisEventBusConfiguration redisEventBusConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            Configuration config = propertiesProvider.getConfiguration("redis");
            return RedisEventBusConfiguration.from(config);
        } catch (FileNotFoundException e) {
            LOGGER.info("Missing `redis.properties` configuration file -> using default RedisEventBusConfiguration");
            return RedisEventBusConfiguration.DEFAULT;
        }
    }
}
