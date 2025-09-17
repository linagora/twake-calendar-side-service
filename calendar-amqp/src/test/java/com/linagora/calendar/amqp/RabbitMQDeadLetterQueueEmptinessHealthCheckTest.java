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

import static com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN;
import static org.apache.james.backends.rabbitmq.Constants.AUTO_DELETE;
import static org.apache.james.backends.rabbitmq.Constants.DIRECT_EXCHANGE;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.backends.rabbitmq.Constants.EXCLUSIVE;
import static org.apache.james.backends.rabbitmq.RabbitMQFixture.EXCHANGE_NAME;
import static org.apache.james.backends.rabbitmq.RabbitMQFixture.ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.apache.james.backends.rabbitmq.DockerRabbitMQ;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class RabbitMQDeadLetterQueueEmptinessHealthCheckTest {

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.STRONG);

    private Connection connection;
    private Channel channel;
    private RabbitMQDeadLetterQueueEmptinessHealthCheck testee;

    @BeforeEach
    void setup(DockerRabbitMQ rabbitMQ) throws IOException, TimeoutException, URISyntaxException {
        ConnectionFactory connectionFactory = rabbitMQ.connectionFactory();
        connectionFactory.setNetworkRecoveryInterval(1000);
        connection = connectionFactory.newConnection();
        channel = connection.createChannel();

        testee = new RabbitMQDeadLetterQueueEmptinessHealthCheck(
            new SimpleConnectionPool(new RabbitMQConnectionFactory(rabbitMQ.getConfiguration()), SimpleConnectionPool.Configuration.builder().retries(1).initialDelay(Duration.ofMillis(100))));
    }

    @AfterEach
    void tearDown(DockerRabbitMQ rabbitMQ) throws Exception {
        closeQuietly(channel);
        closeQuietly(connection);
        rabbitMQ.reset();
    }

    @Test
    void healthCheckShouldReturnHealthyWhenAllDeadLetterQueuesAreEmpty() {
        createQueues();

        assertThat(testee.check().block().isHealthy()).isTrue();
    }

    @Test
    void healthCheckShouldReturnDegradedWhenSomeDeadLetterQueuesAreNotEmpty() throws IOException {
        createQueues();
        publishAMessage();

        assertThat(testee.check().block().isDegraded()).isTrue();
    }

    @Test
    void healthCheckShouldReturnUnhealthyWhenRabbitMQIsDown() throws Exception {
        rabbitMQExtension.getRabbitMQ().stopApp();

        assertThat(testee.check().block().isUnHealthy()).isTrue();
    }

    private void createQueues() {
        CalendarQueueUtil.getAllDeadLetterQueueNames().forEach(Throwing.consumer(queueName -> {
            channel.exchangeDeclare(EXCHANGE_NAME, DIRECT_EXCHANGE, DURABLE);
            channel.queueDeclare(queueName, DURABLE, !EXCLUSIVE, !AUTO_DELETE, ImmutableMap.of()).getQueue();
            channel.queueBind(queueName, EXCHANGE_NAME, EMPTY_ROUTING_KEY);
        }));
    }

    private void publishAMessage() throws IOException {
        AMQP.BasicProperties basicProperties = new AMQP.BasicProperties.Builder()
            .deliveryMode(PERSISTENT_TEXT_PLAIN.getDeliveryMode())
            .priority(PERSISTENT_TEXT_PLAIN.getPriority())
            .contentType(PERSISTENT_TEXT_PLAIN.getContentType())
            .build();

        channel.basicPublish(EXCHANGE_NAME, EMPTY_ROUTING_KEY, basicProperties, "Hello, world!".getBytes(StandardCharsets.UTF_8));
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            //ignore error
        }
    }
}
