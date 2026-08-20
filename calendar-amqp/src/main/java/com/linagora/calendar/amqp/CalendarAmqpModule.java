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
import com.linagora.tmail.rabbitmq.ConsumerReconnectionHandler;

public class CalendarAmqpModule extends AbstractModule {
    public static final String INJECT_KEY_DAV = "dav";
    public static final int DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT = 16;

    private static final boolean FALLBACK_CLASSIC_QUEUES_VERSION_1 = Boolean.parseBoolean(System.getProperty("fallback.classic.queues.v1", "false"));
    private static final String QUEUES_QUORUM_BYPASS_PROPERTY = "dav.queues.quorum.bypass";
    private static final boolean QUEUES_QUORUM_BYPASS_DEFAULT = false;

    @Override
    protected void configure() {
        bind(EventIndexerConsumer.class).in(Scopes.SINGLETON);
        bind(EventEmailConsumer.class).in(Scopes.SINGLETON);
        bind(EventAlarmConsumer.class).in(Scopes.SINGLETON);
        bind(EventResourceConsumer.class).in(Scopes.SINGLETON);
        bind(EventCalendarConsumer.class).in(Scopes.SINGLETON);
        bind(EventCalendarNotificationConsumer.class).in(Scopes.SINGLETON);
        bind(EventContactNotificationConsumer.class).in(Scopes.SINGLETON);
        bind(CalendarDelegatedNotificationConsumer.class).in(Scopes.SINGLETON);
        bind(CalendarListNotificationConsumer.class).in(Scopes.SINGLETON);
        bind(CalendarListNotificationHandler.class).in(Scopes.SINGLETON);
        bind(ItipLocalDeliveryConsumer.class).in(Scopes.SINGLETON);
        bind(EventAuditLogConsumer.class).in(Scopes.SINGLETON);

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
    SimpleConnectionPool.ReconnectionHandler provideReconnectionHandler(EventIndexerConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for disconnector consumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeContactsConsumer(EventIndexerConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventIndexerConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventEmailReconnectionHandler(EventEmailConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for email consumer");
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
    SimpleConnectionPool.ReconnectionHandler provideEventAlarmReconnectionHandler(EventAlarmConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for disconnector consumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventAlarmConsumer(EventAlarmConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventAlarmConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventResourceReconnectionHandler(EventResourceConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for disconnector consumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventResourceConsumer(EventResourceConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventResourceConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideCalendarDelegatedNotificationReconnectionHandler(CalendarDelegatedNotificationConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handling reconnection for CalendarDelegatedNotificationConsumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeCalendarDelegatedNotificationConsumer(CalendarDelegatedNotificationConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(CalendarDelegatedNotificationConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventCalendarReconnectionHandler(EventCalendarConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for disconnector consumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventCalendarConsumer(EventCalendarConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventCalendarConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventCalendarNotificationReconnectionHandler(EventCalendarNotificationConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for EventCalendarNotificationConsumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventCalendarNotificationConsumer(EventCalendarNotificationConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventCalendarNotificationConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventContactNotificationReconnectionHandler(EventContactNotificationConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handling reconnection for EventContactNotificationConsumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventContactNotificationConsumer(EventContactNotificationConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventContactNotificationConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideCalendarListNotificationReconnectionHandler(CalendarListNotificationConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for CalendarListNotificationConsumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeCalendarListNotificationConsumer(CalendarListNotificationConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(CalendarListNotificationConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideItipLocalDeliveryReconnectionHandler(ItipLocalDeliveryConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handling reconnection for ItipLocalDeliveryConsumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeItipLocalDeliveryConsumer(ItipLocalDeliveryConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(ItipLocalDeliveryConsumer.class)
            .init(instance::init);
    }

    @ProvidesIntoSet
    SimpleConnectionPool.ReconnectionHandler provideEventAuditLogReconnectionHandler(EventAuditLogConsumer consumer) {
        return new ConsumerReconnectionHandler(consumer::restart, "Error while handle reconnection for audit log consumer");
    }

    @ProvidesIntoSet
    public InitializationOperation initializeEventAuditLogConsumer(EventAuditLogConsumer instance) {
        return InitilizationOperationBuilder
            .forClass(EventAuditLogConsumer.class)
            .init(instance::init);
    }

    @Provides
    @Singleton
    @Named("itipEventMessagesPrefetchCount")
    int provideITIPEventMessagesPrefetchCount(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        Configuration config = propertiesProvider.getConfiguration("configuration");
        return config.getInt("itip.event.messages.prefetch.count", DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT);
    }

}
