/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.amqp;

import static com.linagora.calendar.amqp.TestFixture.RETRY_BACKOFF_CONFIGURATION;
import static com.linagora.calendar.amqp.TestFixture.awaitAtMost;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLException;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.events.EventBus;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.storage.CalendarListChangedEvent;
import com.linagora.calendar.storage.CalendarListChangedEvent.ChangeType;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UsernameRegistrationKey;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class CalendarListNotificationConsumerTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;

    private EventBus eventBus;
    private CalDavClient calDavClient;
    private CalendarListNotificationConsumer consumer;

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
        eventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()), RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters());
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);

        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);

        CalendarListNotificationHandler handler = new CalendarListNotificationHandler(eventBus, openPaaSUserDAO);
        consumer = new CalendarListNotificationConsumer(channelPool, QueueArguments.Builder::new, handler);
        consumer.init();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void shouldDispatchCreatedWhenCalendarCreated() {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> eventsReceived = new CopyOnWriteArrayList<>();
        registerListener(user, eventsReceived);

        String calendarId = UUID.randomUUID().toString();
        NewCalendar newCalendar = new NewCalendar(calendarId,
            "Test Calendar", "#97c3c1", "A test calendar");

        calDavClient.createNewCalendar(user.username(), user.id(), newCalendar).block();

        CalendarURL expectedCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(calendarId));

        awaitAtMost.untilAsserted(() -> assertThat(eventsReceived)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(user.username());
                assertThat(event.calendarURL()).isEqualTo(expectedCalendarURL);
                assertThat(event.changeType()).isEqualTo(ChangeType.CREATED);
            }));
    }

    @Test
    void shouldDispatchOnlyToRegisteredUserWhenOtherUserIsListening() {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        OpenPaaSUser otherUser = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> userEvents = new CopyOnWriteArrayList<>();
        List<CalendarListChangedEvent> otherUserEvents = new CopyOnWriteArrayList<>();
        registerListener(user, userEvents);
        registerListener(otherUser, otherUserEvents);

        String calendarId = UUID.randomUUID().toString();
        NewCalendar newCalendar = new NewCalendar(calendarId,
            "Test Calendar", "#97c3c1", "A test calendar");

        calDavClient.createNewCalendar(user.username(), user.id(), newCalendar).block();

        CalendarURL expectedCalendarURL = new CalendarURL(user.id(), new OpenPaaSId(calendarId));

        awaitAtMost.untilAsserted(() -> {
            assertThat(userEvents)
                .anySatisfy(event -> {
                    assertThat(event.username()).isEqualTo(user.username());
                    assertThat(event.calendarURL()).isEqualTo(expectedCalendarURL);
                    assertThat(event.changeType()).isEqualTo(ChangeType.CREATED);
                });

            assertThat(otherUserEvents)
                .noneSatisfy(event -> {
                    assertThat(event.username()).isEqualTo(otherUser.username());
                    assertThat(event.calendarURL()).isEqualTo(expectedCalendarURL);
                    assertThat(event.changeType()).isEqualTo(ChangeType.CREATED);
                });
        });
    }

    @Test
    void shouldDispatchUpdatedWhenCalendarUpdated() {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> eventsReceived = new CopyOnWriteArrayList<>();
        registerListener(user, eventsReceived);

        String calendarId = UUID.randomUUID().toString();
        NewCalendar newCalendar = new NewCalendar(calendarId,
            "Test Calendar", "#97c3c1", "A test calendar");

        calDavClient.createNewCalendar(user.username(), user.id(), newCalendar).block();

        CalendarURL calendarURL = new CalendarURL(user.id(), new OpenPaaSId(calendarId));
        String payload = """
            <d:propertyupdate xmlns:d="DAV:">
              <d:set>
                <d:prop>
                  <d:displayname>Archival-update</d:displayname>
                </d:prop>
              </d:set>
            </d:propertyupdate>
            """;

        davTestHelper.updateCalendarDisplayName(user, calendarURL, payload);

        awaitAtMost.untilAsserted(() -> assertThat(eventsReceived)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(user.username());
                assertThat(event.calendarURL()).isEqualTo(calendarURL);
                assertThat(event.changeType()).isEqualTo(ChangeType.UPDATED);
            }));
    }

    @Test
    void shouldDispatchDeletedWhenCalendarDeleted() {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> eventsReceived = new CopyOnWriteArrayList<>();
        registerListener(user, eventsReceived);

        String calendarId = UUID.randomUUID().toString();
        NewCalendar newCalendar = new NewCalendar(calendarId,
            "Test Calendar", "#97c3c1", "A test calendar");
        calDavClient.createNewCalendar(user.username(), user.id(), newCalendar).block();

        CalendarURL calendarURL = new CalendarURL(user.id(), new OpenPaaSId(calendarId));
        calDavClient.deleteCalendar(user.username(), calendarURL).block();

        awaitAtMost.untilAsserted(() -> assertThat(eventsReceived)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(user.username());
                assertThat(event.calendarURL()).isEqualTo(calendarURL);
                assertThat(event.changeType()).isEqualTo(ChangeType.DELETED);
            }));
    }

    @Test
    void shouldDispatchSubscribedWhenCalendarSubscribed() {
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser subscriber = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> eventsReceived = new CopyOnWriteArrayList<>();
        registerListener(subscriber, eventsReceived);

        URI ownerCalendarUri = CalendarURL.from(owner.id()).asUri();
        davTestHelper.updateCalendarAcl(owner, ownerCalendarUri, "{DAV:}read");

        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(owner.id().value())
            .name("Owner shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        davTestHelper.subscribeToSharedCalendar(subscriber, subscribedCalendarRequest);

        CalendarURL subscribedCalendar = new CalendarURL(subscriber.id(), new OpenPaaSId(subscribedCalendarRequest.id()));

        awaitAtMost.untilAsserted(() -> assertThat(eventsReceived)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(subscriber.username());
                assertThat(event.calendarURL()).isEqualTo(subscribedCalendar);
                assertThat(event.changeType()).isEqualTo(ChangeType.SUBSCRIBED);
            }));
    }

    @Test
    void shouldDispatchDelegatedWhenCalendarDelegated() {
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> eventsReceived = new CopyOnWriteArrayList<>();
        registerListener(delegate, eventsReceived);

        OpenPaaSDomain domain = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB())
            .retrieve(owner.username().getDomainPart().get())
            .block();

        String calendarId = UUID.randomUUID().toString();
        NewCalendar newCalendar = new NewCalendar(calendarId,
            "Owner Calendar", "#00AAFF", "Calendar to delegate");
        calDavClient.createNewCalendar(owner.username(), owner.id(), newCalendar).block();

        CalendarURL calendarURL = new CalendarURL(owner.id(), new OpenPaaSId(calendarId));
        calDavClient.patchReadWriteDelegations(domain.id(), calendarURL, List.of(delegate.username()), List.of()).block();

        awaitAtMost.untilAsserted(() -> assertThat(eventsReceived)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(delegate.username());
                assertThat(event.calendarURL().base()).isEqualTo(delegate.id());
                assertThat(event.changeType()).isEqualTo(ChangeType.DELEGATED);
            }));
    }

    @Disabled("Not yet implemented: https://github.com/linagora/esn-sabre/issues/261")
    @Test
    void shouldDispatchRightsRevokedWhenDelegationRemoved() {
        OpenPaaSUser owner = sabreDavExtension.newTestUser();
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        List<CalendarListChangedEvent> eventsReceived = new CopyOnWriteArrayList<>();
        registerListener(delegate, eventsReceived);

        OpenPaaSDomain domain = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB())
            .retrieve(owner.username().getDomainPart().get())
            .block();

        String calendarId = UUID.randomUUID().toString();
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(calendarId,
            "Owner Calendar", "#00AAFF", "Calendar to delegate");
        calDavClient.createNewCalendar(owner.username(), owner.id(), newCalendar).block();

        CalendarURL calendarURL = new CalendarURL(owner.id(), new OpenPaaSId(calendarId));
        // add right
        calDavClient.patchReadWriteDelegations(domain.id(), calendarURL, List.of(delegate.username()), List.of()).block();
        // revoke right
        calDavClient.patchReadWriteDelegations(domain.id(), calendarURL, List.of(), List.of(delegate.username())).block();

        awaitAtMost.untilAsserted(() -> assertThat(eventsReceived)
            .anySatisfy(event -> {
                assertThat(event.username()).isEqualTo(delegate.username());
                assertThat(event.calendarURL().base()).isEqualTo(delegate.id());
                assertThat(event.changeType()).isEqualTo(ChangeType.RIGHTS_REVOKED); // ChangeType.DELETED is ok?
            }));
    }

    private void registerListener(OpenPaaSUser user, List<CalendarListChangedEvent> eventsReceived) {
        Mono.from(eventBus.register(event -> {
            if (event instanceof CalendarListChangedEvent calendarListChangedEvent) {
                eventsReceived.add(calendarListChangedEvent);
            }
        }, new UsernameRegistrationKey(user.username()))).block();
    }
}
