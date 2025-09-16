/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

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
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Result> check(Channel channel) {
        try {
            boolean notEmptyQueues = CalendarQueueUtil.getAllDeadLetterQueueNames().stream()
                .anyMatch(Throwing.predicate(queue -> channel.messageCount(queue) > 0));
            if (notEmptyQueues) {
                return Mono.just(Result.degraded(COMPONENT_NAME, "RabbitMQ dead letter queues contain messages. This might indicate transient failure."));
            } else {
                return Mono.just(Result.healthy(COMPONENT_NAME));
            }
        } catch (Exception e) {
            return Mono.just(Result.unhealthy(COMPONENT_NAME, "Error checking RabbitMQDeadLetterQueueEmptinessHealthCheck", e));
        }
    }
}
