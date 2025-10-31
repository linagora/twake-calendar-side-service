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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.time.Duration;
import java.time.Instant;
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
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class EventITIPConsumerTest {

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
    private EventITIPConsumer consumer;

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
    void setUp() throws SSLException {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        setupConsumer();
    }

    private void setupConsumer() {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);

        consumer = new EventITIPConsumer(channelPool, QueueArguments.Builder::new, calDavClient, openPaaSUserDAO, new UpdatableTickingClock(Instant.now()));
        consumer.init();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void shouldUpdateAttendeeStatus(DockerSabreDavSetup dockerSabreDavSetup) {
        OpenPaaSUser alice = sabreDavExtension.newTestUser();
        OpenPaaSUser bob = sabreDavExtension.newTestUser();
        OpenPaaSUser cedric = sabreDavExtension.newTestUser();

        // Given Alice creates an event with Bob and Cedric as attendees
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting from Alice
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.username().asString(), bob.username().asString(), cedric.username().asString());
        davTestHelper.upsertCalendar(alice, ics, eventUid);

        // When Bob accepts the invitation
        String updatedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting from Alice
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:%s
            ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.username().asString(), bob.username().asString());
        davTestHelper.upsertCalendar(bob, updatedIcs, eventUid);

        // Then Cedric sees that Bob's participation status is updated to ACCEPTED
        awaitAtMost.untilAsserted(() -> {
            String actual = calDavClient.calendarReportByUid(cedric.username(), cedric.id(), eventUid).block().value().toString();

            assertThatJson(actual)
                .whenIgnoringPaths("_embedded.dav:item[0].data[2][0][2]")
                .isArray()
                .contains("""
                [
                    "attendee",
                    {
                        "cn": "Bob",
                        "partstat": "ACCEPTED",
                    },
                    "cal-address",
                    "mailto:user_06c5bff0-1b24-4674-a98c-fd4963d1bfe4@open-paas.org"
                ]
                """);
        });
    }
}

