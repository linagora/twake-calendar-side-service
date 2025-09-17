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
import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;

import com.github.fge.lambdas.Throwing;
import com.rabbitmq.client.Channel;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class RabbitMQDeadLetterQueueEmptinessHealthCheck implements HealthCheck {
    public static final ComponentName COMPONENT_NAME = new ComponentName("RabbitMQDeadLetterQueueEmptiness");

    private final SimpleConnectionPool connectionPool;

    @Inject
    public RabbitMQDeadLetterQueueEmptinessHealthCheck(SimpleConnectionPool connectionPool) {
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
                this::check,
                Throwing.consumer(Channel::close)))
            .onErrorResume(e -> Mono.just(Result.unhealthy(COMPONENT_NAME, "Error checking RabbitMQDeadLetterQueueEmptinessHealthCheck", e)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Result> check(Channel channel) {
        boolean notEmptyQueues = CalendarQueueUtil.getAllDeadLetterQueueNames().stream()
            .anyMatch(Throwing.predicate(queue -> channel.messageCount(queue) > 0));
        if (notEmptyQueues) {
            return Mono.just(Result.degraded(COMPONENT_NAME, "RabbitMQ dead letter queues contain messages. This might indicate transient failure."));
        } else {
            return Mono.just(Result.healthy(COMPONENT_NAME));
        }
    }
}
