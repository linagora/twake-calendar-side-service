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

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.rabbitmq.QueueArguments;
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
import com.google.inject.name.Named;

public class CalendarAmqpModule extends AbstractModule {
    public static final String INJECT_KEY_DAV = "dav";

    private static final boolean FALLBACK_CLASSIC_QUEUES_VERSION_1 = Boolean.parseBoolean(System.getProperty("fallback.classic.queues.v1", "false"));
    private static final String QUEUES_QUORUM_BYPASS_PROPERTY = "dav.queues.quorum.bypass";
    private static final boolean QUEUES_QUORUM_BYPASS_DEFAULT = false;

    @Override
    protected void configure() {
        bind(EventIndexerConsumer.class).in(Scopes.SINGLETON);
        bind(EventEmailConsumer.class).in(Scopes.SINGLETON);
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
}
