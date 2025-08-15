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

import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ALARM_SETTING_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ENABLE_ALARM;
import static com.linagora.calendar.amqp.TestFixture.awaitAtMost;
import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.MailAddress;
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
import org.mockito.Mockito;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.MemoryAlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class AlarmEventCreateTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);
    private static final SettingsBasedResolver settingsResolver = mock(SettingsBasedResolver.class);
    private static final EventEmailFilter eventEmailFilter = spy(EventEmailFilter.acceptAll());

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

        davTestHelper = new DavTestHelper(dockerSabreDavSetup.davConfiguration());
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
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
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
        Mockito.reset(eventEmailFilter);
    }

    private void setupEventAlarmConsumer() {
        AlarmInstantFactory alarmInstantFactory = new AlarmInstantFactory.Default(clock);
        EventAlarmHandler eventAlarmHandler = new EventAlarmHandler(
            alarmInstantFactory, alarmEventDAO,
            calDavClient,
            openPaaSUserDAO,
            settingsResolver,
            new SimpleSessionProvider(new RandomMailboxSessionIdGenerator()),
            eventEmailFilter);

        EventAlarmConsumer consumer = new EventAlarmConsumer(channelPool,
            QueueArguments.Builder::new,
            eventAlarmHandler);
        consumer.init();

        sender = channelPool.getSender();
    }

    @Test
    void shouldCreateAlarmEventWhenOrganizerCreatesEventWithVALARM() throws Exception {
        // Given
        String eventUid = UUID.randomUUID().toString();
        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Test
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".trim().formatted(organizer.username().asString());
        String calendarData = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(attendee.username().asString()),
            PartStat.NEEDS_ACTION,
            vAlarm);
        // When: Organizer creates an event with VALARM
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        // Then: An AlarmEvent should be created for the organizer
        awaitAlarmEventCreated(eventUid, organizer.username());

        AlarmEvent alarmEvent = alarmEventDAO.find(new EventUid(eventUid), organizer.username().asMailAddress()).block();

        Instant eventStartTime = extractStartTime(calendarData);
        Instant alarmTime = eventStartTime.minus(15, MINUTES);

        assertSoftly(softly -> {
            softly.assertThat(alarmEvent.eventUid().value()).isEqualTo(eventUid);
            softly.assertThat(alarmEvent.alarmTime()).isEqualTo(alarmTime);
            softly.assertThat(alarmEvent.eventStartTime()).isEqualTo(eventStartTime);
            softly.assertThat(alarmEvent.recurring()).isFalse();
            softly.assertThat(alarmEvent.recipient().asString()).isEqualTo(organizer.username().asString());
        });
    }

    @Test
    void shouldCreateAlarmEventForAttendeeAfterAcceptingEventWithVALARM() {
        // Given
        String eventUid = UUID.randomUUID().toString();
        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT10M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Meeting Reminder
            DESCRIPTION:You have a meeting
            END:VALARM""".formatted(organizer.username().asString());

        // Organizer creates a calendar event; attendee has not accepted yet
        String initialCalendar = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(attendee.username().asString()),
            PartStat.NEEDS_ACTION,
            vAlarm);

        davTestHelper.upsertCalendar(organizer, initialCalendar, eventUid);

        Instant eventStartTime = extractStartTime(initialCalendar);
        Instant alarmTime = eventStartTime.minus(10, MINUTES);

        // When
        // Attendee receives and accepts the invitation
        attendeeAcceptsEvent(attendee, eventUid);

        // Then
        // An AlarmEvent should be created for the attendee
        awaitAtMost.atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                Optional<AlarmEvent> alarmEventOpt = alarmEventDAO
                    .find(new EventUid(eventUid), attendee.username().asMailAddress())
                    .blockOptional();

                assertThat(alarmEventOpt).isPresent();
                AlarmEvent alarmEvent = alarmEventOpt.get();

                assertSoftly(softly -> {
                    softly.assertThat(alarmEvent.eventUid().value()).isEqualTo(eventUid);
                    softly.assertThat(alarmEvent.alarmTime()).isEqualTo(alarmTime);
                    softly.assertThat(alarmEvent.eventStartTime()).isEqualTo(eventStartTime);
                    softly.assertThat(alarmEvent.recurring()).isFalse();
                    softly.assertThat(alarmEvent.recipient().asString()).isEqualTo(attendee.username().asString());
                });
            });
    }

    @Test
    void shouldCreateAlarmEventForAllAcceptedAttendees() throws Exception {
        // Given
        String eventUid = UUID.randomUUID().toString();
        OpenPaaSUser attendee1 = attendee;
        String attendee1Email = attendee1.username().asString();
        String attendee2Email = attendee2.username().asString();

        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT10M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Meeting Reminder
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".formatted(organizer.username().asString());

        String calendarData = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(attendee1Email, attendee2Email),
            PartStat.NEEDS_ACTION,
            vAlarm);
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        // When: Both attendees accept the invitation
        attendeeAcceptsEvent(attendee1, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);

        // Then: AlarmEvent should be created for both attendees
        awaitAlarmEventCreated(eventUid, attendee1.username());
        awaitAlarmEventCreated(eventUid, attendee2.username());

        AlarmEvent alarmEvent1 = alarmEventDAO.find(new EventUid(eventUid), attendee1.username().asMailAddress()).block();
        AlarmEvent alarmEvent2 = alarmEventDAO.find(new EventUid(eventUid), attendee2.username().asMailAddress()).block();

        Instant eventStartTime = extractStartTime(calendarData);
        Instant expectedAlarmTime = eventStartTime.minus(10, MINUTES);

        assertSoftly(softly -> {
            softly.assertThat(alarmEvent1).isNotNull();
            softly.assertThat(alarmEvent1.eventUid().value()).isEqualTo(eventUid);
            softly.assertThat(alarmEvent1.alarmTime()).isEqualTo(expectedAlarmTime);
            softly.assertThat(alarmEvent1.recipient().asString()).isEqualTo(attendee1Email);

            softly.assertThat(alarmEvent2).isNotNull();
            softly.assertThat(alarmEvent2.eventUid().value()).isEqualTo(eventUid);
            softly.assertThat(alarmEvent2.alarmTime()).isEqualTo(expectedAlarmTime);
            softly.assertThat(alarmEvent2.recipient().asString()).isEqualTo(attendee2Email);
        });
    }

    @Test
    void shouldCreateAlarmEventOnlyForAcceptedAttendee() throws Exception {
        // Given
        String eventUid = UUID.randomUUID().toString();
        OpenPaaSUser attendee1 = attendee;

        String attendee1Email = attendee1.username().asString();
        String attendee2Email = attendee2.username().asString();

        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT10M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Meeting Reminder
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".formatted(organizer.username().asString());

        String calendarData = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(attendee1Email, attendee2Email),
            PartStat.NEEDS_ACTION,
            vAlarm);

        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        // When
        // Only attendee1 accepts the invitation
        attendeeAcceptsEvent(attendee1, eventUid);

        // Then
        awaitAlarmEventCreated(eventUid, attendee1.username());

        AlarmEvent alarmEvent1 = alarmEventDAO.find(new EventUid(eventUid), attendee1.username().asMailAddress()).block();
        AlarmEvent alarmEvent2 = alarmEventDAO.find(new EventUid(eventUid), attendee2.username().asMailAddress()).block();

        Instant eventStartTime = extractStartTime(calendarData);
        Instant alarmTime = eventStartTime.minus(10, MINUTES);

        assertSoftly(softly -> {
            softly.assertThat(alarmEvent1).isNotNull();
            softly.assertThat(alarmEvent1.eventUid().value()).isEqualTo(eventUid);
            softly.assertThat(alarmEvent1.alarmTime()).isEqualTo(alarmTime);
            softly.assertThat(alarmEvent1.recipient().asString()).isEqualTo(attendee1Email);

            softly.assertThat(alarmEvent2).isNull(); // Attendee2 didn't accept, so no alarm should be created
        });
    }

    @Test
    void shouldCreateAlarmEventForOrganizerAndAcceptedAttendeeWithRecurringEvent() {
        // Given
        String eventUid = UUID.randomUUID().toString();
        String attendeeEmail = attendee.username().asString();
        String organizerEmail = organizer.username().asString();

        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT5M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Recurring Meeting Reminder
            DESCRIPTION:This is an automatic recurring alarm
            END:VALARM""".formatted(organizerEmail);

        String calendarData = generateRecurringEventWithValarm(
            eventUid,
            organizerEmail,
            List.of(attendeeEmail),
            PartStat.NEEDS_ACTION,
            vAlarm);

        // When: Organizer creates the recurring event
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        // And: Attendee accepts the event
        attendeeAcceptsEvent(attendee, eventUid);

        // Then: Alarm events should be created for both organizer and attendee
        Instant firstEventStartTime = extractStartTime(calendarData);
        Instant firstAlarmTime = firstEventStartTime.minus(5, MINUTES);

        awaitAtMost.atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                List<AlarmEvent> allAlarms = alarmEventDAO.findAlarmsToTrigger(firstAlarmTime.plusSeconds(1))
                    .collectList()
                    .block();

                assertThat(allAlarms).hasSize(2);

                List<AlarmEvent> organizerAlarms = allAlarms.stream()
                    .filter(alarm -> alarm.eventUid().equals(new EventUid(eventUid)))
                    .filter(alarm -> alarm.recipient().asString().equalsIgnoreCase(organizer.username().asString()))
                    .collect(Collectors.toList());

                List<AlarmEvent> attendeeAlarms = allAlarms.stream()
                    .filter(alarm -> alarm.eventUid().equals(new EventUid(eventUid)))
                    .filter(alarm -> alarm.recipient().asString().equalsIgnoreCase(attendee.username().asString()))
                    .collect(Collectors.toList());

                assertThat(organizerAlarms).hasSize(1);
                assertThat(attendeeAlarms).hasSize(1);

                AlarmEvent organizerAlarm = organizerAlarms.getFirst();
                AlarmEvent attendeeAlarm = attendeeAlarms.getFirst();

                assertSoftly(softly -> {
                    // Organizer alarm
                    softly.assertThat(organizerAlarm.eventUid().value()).as("organizer - eventUid").isEqualTo(eventUid);
                    softly.assertThat(organizerAlarm.recurring()).as("organizer - recurring").isTrue();
                    softly.assertThat(organizerAlarm.recurrenceId()).as("organizer - recurrenceId").isPresent();
                    softly.assertThat(organizerAlarm.alarmTime()).as("organizer - alarmTime").isEqualTo(firstAlarmTime);
                    softly.assertThat(organizerAlarm.eventStartTime()).as("organizer - eventStartTime").isEqualTo(firstEventStartTime);

                    // Attendee alarm
                    softly.assertThat(attendeeAlarm.eventUid().value()).as("attendee - eventUid").isEqualTo(eventUid);
                    softly.assertThat(attendeeAlarm.recurring()).as("attendee - recurring").isTrue();
                    softly.assertThat(attendeeAlarm.recurrenceId()).as("attendee - recurrenceId").isPresent();
                    softly.assertThat(attendeeAlarm.alarmTime()).as("attendee - alarmTime").isEqualTo(firstAlarmTime);
                    softly.assertThat(attendeeAlarm.eventStartTime()).as("attendee - eventStartTime").isEqualTo(firstEventStartTime);
                });
            });
    }

    @Test
    void shouldNotCreateAlarmEventWhenUserSettingAlarmIsDisabled() throws Exception {
        // Given
        Mockito.reset(settingsResolver);

        when(settingsResolver.readSavedSettings(any()))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(ALARM_SETTING_IDENTIFIER, !ENABLE_ALARM))));

        String eventUid = UUID.randomUUID().toString();
        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Test
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".trim().formatted(organizer.username().asString());
        String calendarData = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(attendee.username().asString()),
            PartStat.NEEDS_ACTION,
            vAlarm);

        // When: Organizer creates an event with VALARM
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        Thread.sleep(1000);
        // Then: No AlarmEvent should be created for the organizer
        assertThat(alarmEventDAO.find(new EventUid(eventUid),
            organizer.username().asMailAddress()).blockOptional())
            .isEmpty();
    }


    @Test
    void shouldNotCreateAlarmEventWhenRecipientIsNotInWhitelist() throws Exception {
        when(eventEmailFilter.shouldProcess(any(MailAddress.class)))
            .thenReturn(false);

        String eventUid = UUID.randomUUID().toString();
        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Test
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".trim().formatted(organizer.username().asString());

        String calendarData = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(attendee.username().asString()),
            PartStat.NEEDS_ACTION,
            vAlarm);

        // When: Organizer creates an event with VALARM
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        Thread.sleep(1000);
        // Then: No AlarmEvent should be created for the organizer
        assertThat(alarmEventDAO.find(new EventUid(eventUid),
            organizer.username().asMailAddress()).blockOptional())
            .isEmpty();
    }

    @Test
    void shouldNotCreateAlarmEventForExternalAttendee() throws Exception {
        // Given: An external attendee (outside our system domain)
        String externalAttendeeEmail = UUID.randomUUID() + "@gmail.com";
        String eventUid = UUID.randomUUID().toString();

        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Test
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            """.formatted(organizer.username().asString()).trim();

        // Event with VALARM for organizer, but attendee list contains only the external user
        String calendarData = generateEventWithValarm(
            eventUid,
            organizer.username().asString(),
            List.of(externalAttendeeEmail),
            PartStat.ACCEPTED,
            vAlarm);

        // When: Organizer creates the event in CalDAV
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        Thread.sleep(1000);

        // Then: No AlarmEvent should be created for the external attendee
        assertThat(alarmEventDAO.find(new EventUid(eventUid), new MailAddress(externalAttendeeEmail))
            .blockOptional())
            .isEmpty();
    }


    private void attendeeAcceptsEvent(OpenPaaSUser attendee, String eventUid) {
        waitForFirstEventCreation(attendee);
        awaitAtMost.untilAsserted(() -> assertThatCode(() -> calDavEventRepository.updatePartStat(attendee.username(),
            attendee.id(), new EventUid(eventUid), PartStat.ACCEPTED).block())
            .doesNotThrowAnyException());
    }

    private void awaitAlarmEventCreated(String eventUid, Username username) {
        awaitAtMost.atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(
                alarmEventDAO.find(new EventUid(eventUid), username.asMailAddress())
                    .blockOptional())
                .isPresent());
    }

    private String generateEventWithValarm(String eventUid, String organizerEmail, List<String> attendeeEmails,
                                           PartStat partStat, String vAlarm) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        LocalDateTime baseTime = clock.instant().atZone(clock.getZone()).plusDays(3).toLocalDateTime();
        String startDateTime = baseTime.format(dateTimeFormatter);
        String endDateTime = baseTime.plusHours(1).format(dateTimeFormatter);
        String dtStamp = baseTime.format(dateTimeFormatter);

        String attendeeLines = attendeeEmails.stream()
            .map(email -> String.format("ATTENDEE;PARTSTAT=%s:mailto:%s", partStat.getValue(), email))
            .collect(Collectors.joining("\n"));

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
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
            vAlarm
        );
    }

    private String generateRecurringEventWithValarm(String eventUid, String organizerEmail, List<String> attendeeEmails,
                                                    PartStat partStat, String vAlarm) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        LocalDateTime baseTime = clock.instant().atZone(clock.getZone()).plusDays(3).toLocalDateTime();
        String startDateTime = baseTime.format(dateTimeFormatter);
        String endDateTime = baseTime.plusHours(1).format(dateTimeFormatter);
        String dtStamp = baseTime.format(dateTimeFormatter);

        String attendeeLines = attendeeEmails.stream()
            .map(email -> String.format("ATTENDEE;PARTSTAT=%s:mailto:%s", partStat.getValue(), email))
            .collect(Collectors.joining("\n"));

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
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
            RRULE:FREQ=WEEKLY;COUNT=4
            SUMMARY:Twake Calendar - Recurring Standup
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
            vAlarm
        );
    }

    private Instant extractStartTime(String calendarData) {
        Calendar calendar = CalendarUtil.parseIcs(calendarData);
        return EventParseUtils.getStartTime((VEvent) calendar.getComponent("VEVENT").get())
            .toInstant();
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
