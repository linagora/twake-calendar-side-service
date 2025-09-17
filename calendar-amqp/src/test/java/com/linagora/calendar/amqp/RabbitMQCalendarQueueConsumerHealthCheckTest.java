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

import static org.apache.james.backends.rabbitmq.Constants.AUTO_DELETE;
import static org.apache.james.backends.rabbitmq.Constants.DIRECT_EXCHANGE;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.backends.rabbitmq.Constants.EXCLUSIVE;
import static org.apache.james.backends.rabbitmq.RabbitMQFixture.EXCHANGE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
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
import org.testcontainers.shaded.com.google.common.collect.ImmutableSet;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class RabbitMQCalendarQueueConsumerHealthCheckTest {

    @RegisterExtension
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(RabbitMQExtension.IsolationPolicy.STRONG);

    private Connection connection;
    private Channel channel;
    private RabbitMQCalendarQueueConsumerHealthCheck testee;

    @BeforeEach
    void setup(DockerRabbitMQ rabbitMQ) throws IOException, TimeoutException, URISyntaxException {
        ConnectionFactory connectionFactory = rabbitMQ.connectionFactory();
        connectionFactory.setNetworkRecoveryInterval(1000);
        connection = connectionFactory.newConnection();
        channel = connection.createChannel();

        testee = new RabbitMQCalendarQueueConsumerHealthCheck(ImmutableSet.of(),
            new SimpleConnectionPool(new RabbitMQConnectionFactory(rabbitMQ.getConfiguration()), SimpleConnectionPool.Configuration.builder().retries(1).initialDelay(Duration.ofMillis(100))));
    }

    @AfterEach
    void tearDown(DockerRabbitMQ rabbitMQ) throws Exception {
        closeQuietly(channel);
        closeQuietly(connection);
        rabbitMQ.reset();
    }

    @Test
    void healthCheckShouldReturnHealthyWhenAllConsumersExist() {
        createQueues();
        CalendarQueueUtil.getAllQueueNames().forEach(Throwing.consumer(queueName -> {
            channel.basicConsume(queueName, true, (consumerTag, delivery) -> {}, consumerTag -> {});
        }));

        assertThat(testee.check().block().isHealthy()).isTrue();
    }

    @Test
    void healthCheckShouldReturnDegradedWhenSomeConsumersDoNotExist() {
        createQueues();
        CalendarQueueUtil.getAllQueueNames().stream()
            .filter(queueName -> EventAlarmConsumer.Queue.CREATE.queueName().equals(queueName))
            .forEach(Throwing.consumer(queueName ->
                channel.basicConsume(queueName, true, (consumerTag, delivery) -> {}, consumerTag -> {})));

        assertThat(testee.check().block().isDegraded()).isTrue();
    }

    @Test
    void healthCheckShouldReturnUnhealthyWhenRabbitMQIsDown() throws Exception {
        rabbitMQExtension.getRabbitMQ().stopApp();

        assertThat(testee.check().block().isUnHealthy()).isTrue();
    }

    private void createQueues() {
        CalendarQueueUtil.getAllQueueNames().forEach(Throwing.consumer(queueName -> {
            channel.exchangeDeclare(EXCHANGE_NAME, DIRECT_EXCHANGE, DURABLE);
            channel.queueDeclare(queueName, DURABLE, !EXCLUSIVE, !AUTO_DELETE, ImmutableMap.of()).getQueue();
            channel.queueBind(queueName, EXCHANGE_NAME, EMPTY_ROUTING_KEY);
        }));
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            //ignore error
        }
    }
}
