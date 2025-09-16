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

import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;

import com.github.fge.lambdas.Throwing;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class RabbitMQCalendarQueueConsumerHealthCheck implements HealthCheck {
    public static final ComponentName COMPONENT_NAME = new ComponentName("CalendarQueueConsumers");

    private final Set<SimpleConnectionPool.ReconnectionHandler> reconnectionHandlers;
    private final SimpleConnectionPool connectionPool;

    @Inject
    public RabbitMQCalendarQueueConsumerHealthCheck(Set<SimpleConnectionPool.ReconnectionHandler> reconnectionHandlers, SimpleConnectionPool connectionPool) {
        this.reconnectionHandlers = reconnectionHandlers;
        this.connectionPool = connectionPool;
    }

    @Override
    public ComponentName componentName() {
        return COMPONENT_NAME;
    }

    @Override
    public Mono<Result> check() {
        return connectionPool.getResilientConnection()
            .flatMap(connection -> Mono.using(connection::createChannel,
                channel -> check(connection, channel),
                Throwing.consumer(Channel::close)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Result> check(Connection connection, Channel channel) {
        try {
            boolean queueWithoutConsumers = CalendarQueueUtil.getAllQueueNames().stream()
                .anyMatch(Throwing.predicate(queue -> channel.consumerCount(queue) == 0));
            if (queueWithoutConsumers) {
                return Mono.fromRunnable(() -> reconnectionHandlers.forEach(r -> r.handleReconnection(connection)))
                    .thenReturn(Result.degraded(COMPONENT_NAME, "No consumers"));
            } else {
                return Mono.just(Result.healthy(COMPONENT_NAME));
            }
        } catch (Exception e) {
            return Mono.just(Result.unhealthy(COMPONENT_NAME, "Error checking RabbitMQCalendarQueueConsumerHealthCheck", e));
        }
    }
}
