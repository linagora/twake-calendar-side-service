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

package com.linagora.calendar.amqp;

import java.io.FileNotFoundException;
import java.util.function.Supplier;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.name.Named;

public class CalendarAmqpModule extends AbstractModule {
    public static final String INJECT_KEY_DAV = "dav";
    public static final int DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT = 16;

    private static final boolean FALLBACK_CLASSIC_QUEUES_VERSION_1 = Boolean.parseBoolean(System.getProperty("fallback.classic.queues.v1", "false"));
    private static final String QUEUES_QUORUM_BYPASS_PROPERTY = "dav.queues.quorum.bypass";
    private static final boolean QUEUES_QUORUM_BYPASS_DEFAULT = false;

    @Override
    protected void configure() {
        bind(EventITIPConsumer.class).in(Scopes.SINGLETON);
        bind(EventIndexerConsumer.class).in(Scopes.SINGLETON);
        bind(EventEmailConsumer.class).in(Scopes.SINGLETON);
        bind(EventAlarmConsumer.class).in(Scopes.SINGLETON);
        bind(EventResourceConsumer.class).in(Scopes.SINGLETON);
        bind(EventCalendarConsumer.class).in(Scopes.SINGLETON);

        bind(EventCalendarHandler.class).in(Scopes.SINGLETON);

        Multibinder<HealthCheck> healthCheckMultibinder = Multibinder.newSetBinder(binder(), HealthCheck.class);
        healthCheckMultibinder.addBinding().to(RabbitMQCalendarQueueConsumerHealthCheck.class);
        healthCheckMultibinder.addBinding().to(RabbitMQDeadLetterQueueEmptinessHealthCheck.class);
    }

    @Provides
    @Singleton
    @Named(INJECT_KEY_DAV)
    public Supplier<QueueArguments.Builder> provideQueueArgumentsBuilder(RabbitMQConfiguration rabbitMQConfiguration,
                                                                         PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        boolean quorumQueuesByPass = getQuorumQueuesByPass(propertiesProvider);
        if (quorumQueuesByPass) {
            return rabbitMQConfiguration::workQueueArgumentsBuilder;
        }
        if (!FALLBACK_CLASSIC_QUEUES_VERSION_1) {
            return () -> QueueArguments.builder()
                .classicQueueVersion(2);
        }
        return QueueArguments::builder;
    }

    private boolean getQuorumQueuesByPass(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            return propertiesProvider.getConfiguration("configuration")
                .getBoolean(QUEUES_QUORUM_BYPASS_PROPERTY, QUEUES_QUORUM_BYPASS_DEFAULT);
        } catch (FileNotFoundException e) {
            return QUEUES_QUORUM_BYPASS_DEFAULT;
        }
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideReconnectionHandler(EventIndexerReconnectionHandler reconnectionHandler) {
        return reconnectionHandler;
    }

    @ProvidesIntoSet
    public InitializationOperation initializeContactsConsumer(EventIndexerConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventIndexerConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventEmailReconnectionHandler(EventEmailReconnectionHandler reconnectionHandler) {
        return reconnectionHandler;
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventEmailConsumer(EventEmailConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventEmailConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    public InitializationOperation provisionSabreResources(SabreResourceProvisioner provisioner) {
        return InitilizationOperationBuilder
            .forClass(SabreResourceProvisioner.class)
            .init(provisioner::provisionSabreExchanges);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventAlarmReconnectionHandler(EventAlarmReconnectionHandler reconnectionHandler) {
        return reconnectionHandler;
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventAlarmConsumer(EventAlarmConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventAlarmConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventResourceReconnectionHandler(EventResourceReconnectionHandler reconnectionHandler) {
        return reconnectionHandler;
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventResourceConsumer(EventResourceConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventResourceConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventCalendarReconnectionHandler(EventCalendarReconnectionHandler reconnectionHandler) {
        return reconnectionHandler;
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventCalendarConsumer(EventCalendarConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventCalendarConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventITIPReconnectionHandler(EventITIPReconnectionHandler reconnectionHandler) {
        return reconnectionHandler;
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventITIPConsumer(EventITIPConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventITIPConsumer.class)
            .init(instance::init);
    }

    @Provides
    @Singleton
    public EventEmailFilter provideEventEmailFilter(PropertiesProvider propertiesProvider) throws ConfigurationException {
        return EventEmailFilter.from(propertiesProvider);
    }

    @Provides
    @Singleton
    @Named("defaultCalendarPublicVisibilityEnabled")
    boolean provideDefaultCalendarPublicVisibilityEnabled(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        Configuration config = propertiesProvider.getConfiguration("configuration");
        return config.getBoolean("default.calendar.public.visibility.enabled", false);
    }

    @Provides
    @Singleton
    @Named("itipEventMessagesPrefetchCount")
    int provideITIPEventMessagesPrefetchCount(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        Configuration config = propertiesProvider.getConfiguration("configuration");
        return config.getInt("itip.event.messages.prefetch.count", DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT);
    }
}
