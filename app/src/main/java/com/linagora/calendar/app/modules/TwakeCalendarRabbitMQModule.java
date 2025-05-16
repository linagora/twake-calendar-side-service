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

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.modules.queue.rabbitmq.RabbitMQModule;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.app.modules.ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration;

public class TwakeCalendarRabbitMQModule extends AbstractModule {

    @Override
    protected void configure() {
        install(new RabbitMQModule());
    }

    @Provides
    ScheduledReconnectionHandlerConfiguration configuration(PropertiesProvider propertiesProvider) throws ConfigurationException {
        return ScheduledReconnectionHandlerConfiguration.parse(propertiesProvider);
    }

    @ProvidesIntoSet
    InitializationOperation start(ScheduledReconnectionHandler scheduledReconnectionHandler) {
        return InitilizationOperationBuilder
            .forClass(ScheduledReconnectionHandler.class)
            .init(scheduledReconnectionHandler::start);
    }
}