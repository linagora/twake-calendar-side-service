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

import static com.linagora.calendar.amqp.TestFixture.awaitAtMost;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ALARM_SETTING_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ENABLE_ALARM;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Username;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.MemoryAlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class AlarmEventCancellationTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static final SettingsBasedResolver settingsResolver = mock(SettingsBasedResolver.class);

    private static OpenPaaSUserDAO openPaaSUserDAO;
    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;

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
        MongoDatabase mongoDB = dockerSabreDavSetup.getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    private OpenPaaSUser organizer;
    private OpenPaaSUser attendee;
    private OpenPaaSUser attendee2;
    private Sender sender;

    private AlarmEventDAO alarmEventDAO;
    private CalDavClient calDavClient;
    private UpdatableTickingClock clock;
    private CalDavEventRepository calDavEventRepository;

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();
        attendee2 = sabreDavExtension.newTestUser();

        alarmEventDAO = new MemoryAlarmEventDAO();
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavEventRepository = new CalDavEventRepository(calDavClient);
        clock = new UpdatableTickingClock(Instant.now().minus(60, MINUTES));

        when(settingsResolver.readSavedSettings(any()))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(ALARM_SETTING_IDENTIFIER, ENABLE_ALARM))));

        setupEventAlarmConsumer();
    }

    @AfterEach
    void afterEach() {
        Arrays.stream(EventAlarmConsumer.Queue
                .values())
            .map(EventAlarmConsumer.Queue::queueName)
            .forEach(queueName -> sender.delete(QueueSpecification.queue().name(queueName))
                .block());

        Mockito.reset(settingsResolver);
    }

    private void setupEventAlarmConsumer() {
        AlarmInstantFactory alarmInstantFactory = new AlarmInstantFactory.Default(clock);
        EventAlarmHandler eventAlarmHandler = new EventAlarmHandler(
            alarmInstantFactory, alarmEventDAO,
            calDavClient,
            openPaaSUserDAO,
            settingsResolver,
            new SimpleSessionProvider(new RandomMailboxSessionIdGenerator()),
            EventEmailFilter.acceptAll());

        EventAlarmConsumer consumer = new EventAlarmConsumer(channelPool,
            QueueArguments.Builder::new,
            eventAlarmHandler);
        consumer.init();

        sender = channelPool.getSender();
    }

    @Test
    void shouldRemoveOrganizerAlarmWhenOrganizerDeletesEvent() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        awaitAlarmEventCreated(eventUid, organizer.username());

        // When
        davTestHelper.deleteCalendar(organizer, eventUid);

        // Then
        awaitAtMost.untilAsserted(() ->
            assertThat(alarmEventDAO.find(eventUid, organizer.username().asMailAddress()).blockOptional())
                .isEmpty());
    }

    @Test
    void shouldRemoveAttendeeAlarmWhenOrganizerDeletesEvent() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        attendeeAcceptsEvent(attendee, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());

        // When
        davTestHelper.deleteCalendar(organizer, eventUid);

        // Then
        awaitAtMost.untilAsserted(() ->
            assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional())
                .isEmpty());
    }

    @Test
    void shouldRemoveAllAttendeeAlarmsWhenOrganizerDeletesEvent() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);

        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());
        awaitAlarmEventCreated(eventUid, attendee2.username());

        // When
        davTestHelper.deleteCalendar(organizer, eventUid);

        // Then: both attendees' AlarmEvents should be removed
        awaitAtMost.untilAsserted(() -> {
            assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional())
                .isEmpty();
            assertThat(alarmEventDAO.find(eventUid, attendee2.username().asMailAddress()).blockOptional())
                .isEmpty();
        });
    }

    static Stream<PartStat> nonAcceptedPartStats() {
        return Stream.of(PartStat.DECLINED, PartStat.TENTATIVE);
    }

    @ParameterizedTest
    @MethodSource("nonAcceptedPartStats")
    void shouldRemoveAttendeeAlarmWhenAttendeeChangesFromAcceptedTo(PartStat newPartStat) {
        EventUid eventUid = createEventWithVALARM(attendee);
        attendeeAcceptsEvent(attendee, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());

        // When: the attendee changes their partstat to a non-accepted state
        calDavEventRepository.updatePartStat(attendee.username(), attendee.id(), eventUid, newPartStat).block();

        // Then
        awaitAtMost.untilAsserted(() ->
            assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional())
                .isEmpty());
    }

    @Test
    void shouldKeepOtherAttendeeAlarmWhenPeerDeclinesAfterAcceptance() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);
        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());
        awaitAlarmEventCreated(eventUid, attendee2.username());

        // When: attendee #1 changes their partstat from ACCEPTED to DECLINED
        calDavEventRepository
            .updatePartStat(attendee.username(), attendee.id(), eventUid, PartStat.DECLINED)
            .block();

        // Then: attendee #1's alarm is removed, while attendee #2's alarm remains intact
        awaitAtMost.untilAsserted(() -> assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional()).isEmpty();
            softly.assertThat(alarmEventDAO.find(eventUid, attendee2.username().asMailAddress()).blockOptional()).isPresent();
        })));
    }

    @Test
    void shouldRemoveAllParticipantAlarmsWhenOrganizerRemovesVALARMFromEvent() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);

        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);
        awaitAlarmEventCreated(eventUid, organizer.username());
        awaitAlarmEventCreated(eventUid, attendee.username());
        awaitAlarmEventCreated(eventUid, attendee2.username());

        // When: organizer updates event, NO VALARM (sequence++)
        String withoutAlarm = generateEventWithValarm(eventUid.value(), organizer.username().asString(),
            List.of(attendee.username().asString(), attendee2.username().asString()),
            PartStat.NEEDS_ACTION, "");

        davTestHelper.upsertCalendar(organizer, withoutAlarm, eventUid.value());

        // Then: both attendees' alarms (and organizer's) are removed
        awaitAtMost.untilAsserted(() -> assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional()).isEmpty();
            softly.assertThat(alarmEventDAO.find(eventUid, attendee2.username().asMailAddress()).blockOptional()).isEmpty();
            softly.assertThat(alarmEventDAO.find(eventUid, organizer.username().asMailAddress()).blockOptional()).isEmpty();
        })));
    }

    @Test
    void shouldRemoveOnlyDisinvitedAttendeeAlarm() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);
        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());
        awaitAlarmEventCreated(eventUid, attendee2.username());

        // When: organizer updates the event to remove attendee2 from ATTENDEE list
        String icsWithOneAttendee = generateEventWithValarm(
            eventUid.value(),
            organizer.username().asString(),
            List.of(attendee.username().asString()), // only attendee1 remains
            PartStat.ACCEPTED,
            """
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                ATTENDEE:mailto:%s
                SUMMARY:Meeting Reminder
                DESCRIPTION:This is an automatic alarm
                END:VALARM""".formatted(organizer.username().asString()))
            .replaceAll("(?m)^SEQUENCE:.*$", "")
            .replaceFirst("(?m)^(DTSTAMP:.*$)", "$1\nSEQUENCE:2");

        davTestHelper.upsertCalendar(organizer, icsWithOneAttendee, eventUid);

        // Then: attendee2's alarm should be removed, attendee1's alarm should still exist
        awaitAtMost.untilAsserted(() -> assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(alarmEventDAO.find(eventUid, attendee2.username().asMailAddress()).blockOptional()).isEmpty();
            softly.assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional()).isPresent();
        })));
    }

    @Test
    void shouldRecreateAttendeeAlarmWhenTogglingAcceptedToDeclinedAndBack() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        attendeeAcceptsEvent(attendee, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());

        // When: attendee changes from ACCEPTED to DECLINED
        calDavEventRepository
            .updatePartStat(attendee.username(), attendee.id(), eventUid, PartStat.DECLINED)
            .block();

        // Then: attendee alarm should be removed
        awaitAtMost.untilAsserted(() ->
            assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional())
                .isEmpty());

        // When: attendee changes back from DECLINED to ACCEPTED
        calDavEventRepository
            .updatePartStat(attendee.username(), attendee.id(), eventUid, PartStat.ACCEPTED)
            .block();

        // Then: attendee alarm should be created again
        awaitAtMost.untilAsserted(() -> assertThat(
            alarmEventDAO.find(eventUid, attendee.username().asMailAddress())
                .blockOptional())
            .isPresent());
    }

    @Test
    void shouldRemoveAttendeeAlarmWhenAttendeeDeletesOwnEventAfterAcceptance() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        attendeeAcceptsEvent(attendee, eventUid);
        awaitAlarmEventCreated(eventUid, attendee.username());

        String attendeeEventResourceId = davTestHelper.findFirstEventId(attendee).get();
        // When: attendee deletes their copy of the event
        davTestHelper.deleteCalendar(attendee, attendeeEventResourceId);

        // Then: attendee's AlarmEvent should be removed
        awaitAtMost.untilAsserted(() ->
            assertThat(alarmEventDAO.find(eventUid, attendee.username().asMailAddress()).blockOptional())
                .isEmpty());
    }

    private EventUid createEventWithVALARM(OpenPaaSUser... attendees) {
        String eventUid = UUID.randomUUID().toString();
        String organizerEmail = organizer.username().asString();

        List<String> attendeeEmails = Arrays.stream(attendees)
            .map(u -> u.username().asString())
            .collect(Collectors.toList());

        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT10M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Meeting Reminder
            DESCRIPTION:This is an automatic alarm
            END:VALARM""".formatted(organizerEmail);

        String ics = generateEventWithValarm(
            eventUid,
            organizerEmail,
            attendeeEmails,
            PartStat.NEEDS_ACTION,
            vAlarm);

        davTestHelper.upsertCalendar(organizer, ics, eventUid);
        return new EventUid(eventUid);
    }

    private void attendeeAcceptsEvent(OpenPaaSUser attendee, EventUid eventUid) {
        waitForFirstEventCreation(attendee);
        awaitAtMost.untilAsserted(() -> assertThatCode(() -> calDavEventRepository.updatePartStat(attendee.username(),
            attendee.id(), eventUid, PartStat.ACCEPTED).block())
            .doesNotThrowAnyException());
    }

    private void awaitAlarmEventCreated(EventUid eventUid, Username username) {
        awaitAtMost.untilAsserted(() -> assertThat(
            alarmEventDAO.find(eventUid, username.asMailAddress())
                .blockOptional())
            .isPresent());
    }

    private String generateEventWithValarm(String eventUid, String organizerEmail, List<String> attendeeEmails,
                                           PartStat partStat, String vAlarm) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        LocalDateTime baseTime = clock.instant().atZone(clock.getZone()).plusDays(3).toLocalDateTime();
        String startDateTime = baseTime.format(dateTimeFormatter);
        String endDateTime = baseTime.plusHours(1).format(dateTimeFormatter);
        String dtStamp = baseTime.minusDays(3).format(dateTimeFormatter);

        String attendeeLines = attendeeEmails.stream()
            .map(email -> String.format("ATTENDEE;PARTSTAT=%s:mailto:%s", partStat.getValue(), email))
            .collect(Collectors.joining("\n"));

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:%sZ
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:%s
            DTEND;TZID=Asia/Ho_Chi_Minh:%s
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:%s
            %s
            %s
            END:VEVENT
            END:VCALENDAR
            """.formatted(
            eventUid,
            dtStamp,
            startDateTime,
            endDateTime,
            organizerEmail, organizerEmail,
            attendeeLines,
            vAlarm);
    }

    private String waitForFirstEventCreation(OpenPaaSUser user) {
        AtomicReference<String> idRef = new AtomicReference<>();

        awaitAtMost.untilAsserted(() -> {
            Optional<String> firstEventId = davTestHelper.findFirstEventId(user);
            assertThat(firstEventId).isPresent();
            idRef.set(firstEventId.get());
        });

        return idRef.get();
    }
}
