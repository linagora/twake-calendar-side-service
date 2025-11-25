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

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;

public class EventCalendarNotificationReconnectionHandler implements SimpleConnectionPool.ReconnectionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventCalendarNotificationReconnectionHandler.class);

    private final EventCalendarNotificationConsumer eventCalendarNotificationConsumer;

    @Inject
    public EventCalendarNotificationReconnectionHandler(EventCalendarNotificationConsumer eventResourceConsumer) {
        this.eventCalendarNotificationConsumer = eventResourceConsumer;
    }

    @Override
    public Publisher<Void> handleReconnection(Connection connection) {
        return Mono.fromRunnable(eventCalendarNotificationConsumer::restart)
            .doOnError(error -> LOGGER.error("Error while handle reconnection for disconnector consumer", error))
            .then();
    }
}
