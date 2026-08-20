/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *                                                                  *
 *  This file is subject to The Affero General Public License       *
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

import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;

/** Restarts a consumer after a RabbitMQ reconnection. */
public class ConsumerReconnectionHandler implements SimpleConnectionPool.ReconnectionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerReconnectionHandler.class);

    private final Runnable restart;
    private final String errorMessage;

    public ConsumerReconnectionHandler(Runnable restart, String errorMessage) {
        this.restart = restart;
        this.errorMessage = errorMessage;
    }

    @Override
    public Publisher<Void> handleReconnection(Connection connection) {
        return Mono.fromRunnable(restart)
            // Handlers are chained: let later consumers restart even if this one fails.
            .onErrorResume(error -> {
                LOGGER.error(errorMessage, error);
                return Mono.empty();
            })
            .then();
    }
}
