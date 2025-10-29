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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class EventCalendarConsumerTest {

    private static final boolean DEFAULT_CALENDAR_PUBLIC_VISIBILITY_ENABLED = true;

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(20, TimeUnit.SECONDS);

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;

    private CalDavClient calDavClient;

    @BeforeAll
    static void beforeAll(DockerSabreDavSetup dockerSabreDavSetup) throws Exception {
        RabbitMQConfiguration rabbitMQConfiguration = dockerSabreDavSetup.rabbitMQConfiguration();

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

        davTestHelper = new DavTestHelper(dockerSabreDavSetup.davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    public void setUp() throws SSLException {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    private void setupConsumer(boolean defaultCalendarPublicVisibilityEnabled) {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);

        EventCalendarConsumer consumer = new EventCalendarConsumer(channelPool, QueueArguments.Builder::new,
            new EventCalendarHandler(openPaaSUserDAO, calDavClient, defaultCalendarPublicVisibilityEnabled));
        consumer.init();
    }

    @Test
    void shouldSetDefaultCalendarPubliclyVisible(DockerSabreDavSetup dockerSabreDavSetup) {
        setupConsumer(DEFAULT_CALENDAR_PUBLIC_VISIBILITY_ENABLED);

        OpenPaaSUser user = sabreDavExtension.newTestUser();

        awaitAtMost.untilAsserted(() -> {
            String actual = davTestHelper.getCalendarMetadata(user).block();

            assertThat(actual).contains("{\"privilege\":\"{DAV:}read\",\"principal\":\"{DAV:}authenticated\",\"protected\":true}");
        });
    }

    @Test
    void shouldNotSetNonDefaultCalendarPubliclyVisible(DockerSabreDavSetup dockerSabreDavSetup) throws InterruptedException {
        setupConsumer(DEFAULT_CALENDAR_PUBLIC_VISIBILITY_ENABLED);

        OpenPaaSUser user = sabreDavExtension.newTestUser();

        String newCalendarId = UUID.randomUUID().toString();
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            newCalendarId,
            "Test Calendar",
            "#97c3c1",
            "A test calendar"
        );
        calDavClient.createNewCalendar(user.username(), user.id(), newCalendar).block();

        Thread.sleep(3000); // wait for the consumer to process the message

        awaitAtMost.untilAsserted(() -> {
            String actual = davTestHelper.getCalendarMetadata(user, new OpenPaaSId(newCalendarId)).block();

            assertThat(actual).doesNotContain("{\"privilege\":\"{DAV:}read\",\"principal\":\"{DAV:}authenticated\",\"protected\":true}");
            assertThat(actual).contains("{\"privilege\":\"{urn:ietf:params:xml:ns:caldav}read-free-busy\",\"principal\":\"{DAV:}authenticated\",\"protected\":true}");
        });
    }

    @Test
    void shouldNotSetDefaultCalendarPubliclyVisibleWhenItIsNotEnabledInConfig(DockerSabreDavSetup dockerSabreDavSetup) throws InterruptedException {
        setupConsumer(!DEFAULT_CALENDAR_PUBLIC_VISIBILITY_ENABLED);

        OpenPaaSUser user = sabreDavExtension.newTestUser();

        Thread.sleep(3000); // wait for the consumer to process the message

        awaitAtMost.untilAsserted(() -> {
            String actual = davTestHelper.getCalendarMetadata(user).block();

            assertThat(actual).doesNotContain("{\"privilege\":\"{DAV:}read\",\"principal\":\"{DAV:}authenticated\",\"protected\":true}");
            assertThat(actual).contains("{\"privilege\":\"{urn:ietf:params:xml:ns:caldav}read-free-busy\",\"principal\":\"{DAV:}authenticated\",\"protected\":true}");
        });
    }
}

