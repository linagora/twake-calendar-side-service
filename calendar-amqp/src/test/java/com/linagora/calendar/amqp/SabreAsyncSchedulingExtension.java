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

import static com.linagora.calendar.amqp.CalendarAmqpModule.DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.rabbitmq.QueueSpecification;

public class SabreAsyncSchedulingExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    private final SabreDavExtension sabreDavExtension;
    private SimpleConnectionPool connectionPool;
    private ReactorRabbitMQChannelPool channelPool;
    private ItipLocalDeliveryConsumer itipLocalDeliveryConsumer;

    public SabreAsyncSchedulingExtension(SabreDavExtension sabreDavExtension) {
        this.sabreDavExtension = Objects.requireNonNull(sabreDavExtension);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        RabbitMQConfiguration rabbitMQConfiguration = sabreDavExtension.dockerSabreDavSetup().rabbitMQConfiguration();

        RabbitMQConnectionFactory connectionFactory = new RabbitMQConnectionFactory(rabbitMQConfiguration);
        connectionPool = new SimpleConnectionPool(connectionFactory,
            SimpleConnectionPool.Configuration.builder()
                .retries(2)
                .initialDelay(Duration.ofMillis(5)));
        channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.builder()
                .retries(2)
                .maxBorrowDelay(Duration.ofMillis(250))
                .maxChannel(10),
            new RecordingMetricFactory(),
            new NoopGaugeRegistry());
        channelPool.start();
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        itipLocalDeliveryConsumer = new ItipLocalDeliveryConsumer(channelPool,
            QueueArguments.Builder::new,
            new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING),
            localRecipientResolver(),
            DEFAULT_ITIP_EVENT_MESSAGES_PREFETCH_COUNT);
        itipLocalDeliveryConsumer.init();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) {
        try {
            if (itipLocalDeliveryConsumer != null) {
                itipLocalDeliveryConsumer.close();
            }
        } finally {
            deleteItipLocalDeliveryQueues();
            itipLocalDeliveryConsumer = null;
        }
    }

    private void deleteItipLocalDeliveryQueues() {
        channelPool.getSender().delete(QueueSpecification.queue().name(ItipLocalDeliveryConsumer.QUEUE_NAME)).block();
        channelPool.getSender().delete(QueueSpecification.queue().name(ItipLocalDeliveryConsumer.DEAD_LETTER_QUEUE)).block();
    }

    private LocalRecipientResolver localRecipientResolver() {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        return new LocalRecipientResolver(new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO),
            new MongoDBResourceDAO(mongoDB, Clock.systemUTC()),
            domainDAO);
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        if (channelPool != null) {
            channelPool.close();
        }
        if (connectionPool != null) {
            connectionPool.close();
        }
    }
}
