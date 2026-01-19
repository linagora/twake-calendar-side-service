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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventModifier;
import com.linagora.calendar.dav.CalendarEventUpdatePatch;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.AlarmEventFactory;
import com.linagora.calendar.storage.MemoryAlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Location;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class AlarmEventUpdateTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static final SettingsBasedResolver settingsResolver = mock(SettingsBasedResolver.class);

    private static OpenPaaSUserDAO openPaaSUserDAO;
    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;
    private static CalDavEventRepository calDavEventRepository;

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

        davTestHelper = new DavTestHelper(dockerSabreDavSetup.davConfiguration(),TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavEventRepository = new CalDavEventRepository(new CalDavClient(dockerSabreDavSetup.davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING));
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

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser(Optional.of("organizer_"));
        attendee = sabreDavExtension.newTestUser(Optional.of("attendee_"));
        attendee2 = sabreDavExtension.newTestUser(Optional.of("attendee2_"));

        alarmEventDAO = new MemoryAlarmEventDAO();
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavEventRepository = new CalDavEventRepository(calDavClient);
        clock = new UpdatableTickingClock(Instant.now().minus(60, MINUTES));

        when(settingsResolver.resolveOrDefault(any(Username.class)))
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
            new AlarmEventFactory.Default(),
            EventEmailFilter.acceptAll());

        EventAlarmConsumer consumer = new EventAlarmConsumer(channelPool,
            QueueArguments.Builder::new,
            eventAlarmHandler);
        consumer.init();

        sender = channelPool.getSender();
    }

    record EventUpdateStartTimePatch(Instant newStartTime) implements CalendarEventUpdatePatch {

        @Override
        public boolean apply(VEvent vEvent) {
            vEvent.getDateTimeStart()
                .setDate(newStartTime.atZone(ZoneId.of("Asia/Ho_Chi_Minh")));
            return true;
        }
    }

    record EventUpdateAlarmTriggerPatch(Duration beforeStart) implements CalendarEventUpdatePatch {

        @Override
        public boolean apply(VEvent vEvent) {
            vEvent.getAlarms().forEach(alarm -> alarm.getTrigger()
                .ifPresent(trigger -> trigger.setDuration(beforeStart.negated())));
            return true;
        }
    }

    record EventUpdateDescriptionTitleAndLocationPatch(String title, String description,
                                                       String location) implements CalendarEventUpdatePatch {

        @Override
        public boolean apply(VEvent vEvent) {
            vEvent.getSummary().setValue(title);
            Optional.ofNullable(vEvent.getDescription())
                .ifPresentOrElse(descriptionProp -> descriptionProp.setValue(description),
                    () -> vEvent.add(new Description(description)));
            Optional.ofNullable(vEvent.getLocation())
                .ifPresentOrElse(locationProp -> locationProp.setValue(location),
                    () -> vEvent.add(new Location(location)));
            return true;
        }
    }

    @Test
    void shouldUpdateAlarmTimeWhenOrganizerChangeStarDateEarlier() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        AlarmEvent initial = awaitAlarmEventCreated(eventUid, organizer.username());
        Duration delta = Duration.ofMinutes(20);
        Instant newStart = initial.eventStartTime().minus(delta);

        // When: organizer updates the start time earlier by 20 minutes
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateStartTimePatch(newStart))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updated = alarmEventDAO
                .find(eventUid, organizer.username().asMailAddress())
                .block();

            assertThat(updated)
                .describedAs("Alarm should be recomputed after moving start earlier by %s", delta)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initial.alarmTime().minus(delta), newStart);
        });
    }

    @Test
    void shouldUpdateAlarmTimeWhenOrganizerMovesStartLater() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        AlarmEvent initial = awaitAlarmEventCreated(eventUid, organizer.username());
        Duration delta = Duration.ofMinutes(20);
        Instant newStart = initial.eventStartTime().plus(delta);

        // When: organizer moves start later by 20 minutes
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateStartTimePatch(newStart))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updated = alarmEventDAO
                .find(eventUid, organizer.username().asMailAddress())
                .block();

            assertThat(updated)
                .describedAs("Alarm should be recomputed after moving start later by %s", delta)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initial.alarmTime().plus(delta), newStart);
        });
    }

    @Test
    void shouldUpdateAlarmTimeWhenOrganizerChangesAlarmTriggerDuration() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        AlarmEvent initial = awaitAlarmEventCreated(eventUid, organizer.username());

        Duration delta = Duration.ofMinutes(20);
        Duration currentTriggerBeforeStart = Duration.between(initial.alarmTime(), initial.eventStartTime());
        Duration newTriggerBeforeStart = currentTriggerBeforeStart.plus(delta);

        // When:
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateAlarmTriggerPatch(newTriggerBeforeStart))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updated = alarmEventDAO
                .find(eventUid, organizer.username().asMailAddress())
                .block();

            assertThat(updated)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initial.alarmTime().minus(delta), initial.eventStartTime());
        });
    }

    @Test
    void shouldUpdateIcsPayloadWhenOrganizerChangeEvent() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        AlarmEvent initial = awaitAlarmEventCreated(eventUid, organizer.username());
        String initialIcs = initial.ics();

        String updateTitle = "Updated Meeting Title";
        String updateDescription = "Updated Meeting Description";
        String updateLocation = "Updated Meeting Location";

        // When
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateDescriptionTitleAndLocationPatch(updateTitle,
                updateDescription, updateLocation))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updated = alarmEventDAO
                .find(eventUid, organizer.username().asMailAddress())
                .block();

            assertThat(updated).isNotNull();
            assertThat(updated.ics())
                .describedAs("ICS payload should be updated in DB")
                .isNotBlank()
                .isNotEqualTo(initialIcs);

            assertThat(updated.ics())
                .contains(updateTitle, updateDescription, updateLocation);
        });
    }

    @Test
    void shouldRemoveAlarmEventWhenOrganizerChangeStarDateToPast() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee);
        awaitAlarmEventCreated(eventUid, organizer.username());
        Instant newStart = clock.instant().minus(Duration.ofMinutes(10000));

        // When: organizer updates the start time earlier by 20 minutes
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateStartTimePatch(newStart))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updated = alarmEventDAO
                .find(eventUid, organizer.username().asMailAddress())
                .block();

            assertThat(updated).isNull();
        });
    }

    @Test
    void shouldUpdateAlarmTimeOfAttendeeWhenOrganizerChangeStartDateEarlier() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);
        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);

        AlarmEvent initialAttendee = awaitAlarmEventCreated(eventUid, attendee.username());
        AlarmEvent initialAttendee2 = awaitAlarmEventCreated(eventUid, attendee2.username());

        Duration delta = Duration.ofMinutes(20);
        Instant newStart = initialAttendee.eventStartTime().minus(delta);

        // When: organizer updates the start time earlier by 20 minutes
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateStartTimePatch(newStart))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updatedAttendee1 = alarmEventDAO
                .find(eventUid, attendee.username().asMailAddress())
                .block();
            AlarmEvent updatedAttendee2 = alarmEventDAO
                .find(eventUid, attendee2.username().asMailAddress())
                .block();

            assertThat(updatedAttendee1)
                .describedAs("Alarm for attendee 1 should be recomputed after moving start by %s", delta)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initialAttendee.alarmTime().minus(delta), newStart);

            assertThat(updatedAttendee2)
                .describedAs("Alarm for attendee 2 should be recomputed after moving start by %s", delta)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initialAttendee2.alarmTime().minus(delta), newStart);
        });
    }

    @Test
    void shouldUpdateAlarmTimeOfAttendeeWhenOrganizerChangeStartDateLater() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);
        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);

        AlarmEvent initialAttendee1 = awaitAlarmEventCreated(eventUid, attendee.username());
        AlarmEvent initialAttendee2 = awaitAlarmEventCreated(eventUid, attendee2.username());

        Duration delta = Duration.ofMinutes(20);
        Instant newStart = initialAttendee1.eventStartTime().plus(delta);

        // When: organizer updates the start time later by 20 minutes
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateStartTimePatch(newStart))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent updatedAttendee1 = alarmEventDAO
                .find(eventUid, attendee.username().asMailAddress())
                .block();
            AlarmEvent updatedAttendee2 = alarmEventDAO
                .find(eventUid, attendee2.username().asMailAddress())
                .block();

            assertThat(updatedAttendee1)
                .describedAs("Alarm for attendee 1 should be recomputed after moving start later by %s", delta)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initialAttendee1.alarmTime().plus(delta), newStart);

            assertThat(updatedAttendee2)
                .describedAs("Alarm for attendee 2 should be recomputed after moving start later by %s", delta)
                .isNotNull()
                .extracting(AlarmEvent::alarmTime, AlarmEvent::eventStartTime)
                .containsExactly(initialAttendee2.alarmTime().plus(delta), newStart);
        });
    }

    @Test
    void shouldUpdateIcsPayloadOfAttendeeWhenOrganizerChangeEvent() {
        // Given
        EventUid eventUid = createEventWithVALARM(attendee, attendee2);
        attendeeAcceptsEvent(attendee, eventUid);
        attendeeAcceptsEvent(attendee2, eventUid);

        AlarmEvent initialAttendee = awaitAlarmEventCreated(eventUid, attendee.username());
        String initialIcs = initialAttendee.ics();

        String updateTitle = "Updated Meeting Title";
        String updateDescription = "Updated Meeting Description";
        String updateLocation = "Updated Meeting Location";

        // When
        calDavEventRepository.updateEvent(
            organizer.username(),
            organizer.id(),
            eventUid,
            CalendarEventModifier.of(new EventUpdateDescriptionTitleAndLocationPatch(updateTitle,
                updateDescription, updateLocation))).block();

        // Then
        awaitAtMost.untilAsserted(() -> {
            AlarmEvent update = alarmEventDAO
                .find(eventUid, organizer.username().asMailAddress())
                .block();

            assertThat(update).isNotNull();
            assertThat(update.ics())
                .describedAs("ICS payload for attendee 2 should be updated in DB")
                .isNotBlank()
                .isNotEqualTo(initialIcs);

            assertThat(update.ics())
                .contains(updateTitle, updateDescription, updateLocation);
        });
    }

    @Nested
    class RecurrenceEventTest {
        @Test
        void shouldUseOverrideTimesForRecurringEvent() {
            // Given (fixed times; easier to read and reason about)
            // Recurrence: 01/10 11:00–12:00, 02/10 11:00–12:00, 03/10 11:00–12:00
            // 2nd occurrence is overridden to 02/10 14:00–15:00
            String startFirstInstanceLocal = "20251001T110000";
            String endFirstInstanceLocal = "20251001T120000";
            String startSecondOverrideLocal = "20251002T140000";
            String endSecondOverrideLocal = "20251002T150000";
            String originalSecondStartUtcRecId = "20251002T040000Z"; // 11:00 ICT -> 04:00Z
            String dtStampUtc = "20250930T000000Z";

            EventUid eventUid = new EventUid(UUID.randomUUID().toString());

            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                CALSCALE:GREGORIAN
                PRODID:-//App//ICS//EN
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
                UID:{eventUid}
                DTSTART;TZID=Asia/Ho_Chi_Minh:{startFirst}
                DTEND;TZID=Asia/Ho_Chi_Minh:{endFirst}
                SUMMARY:Recurrence Meeting
                RRULE:FREQ=DAILY;COUNT=3
                ORGANIZER;CN={organizer}:mailto:{organizer}
                ATTENDEE;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:{organizer}
                DTSTAMP:{dtStamp}
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                ATTENDEE:mailto:{organizer}
                SUMMARY:Recurrence Meeting
                DESCRIPTION:Starts in 10 minutes
                END:VALARM
                END:VEVENT
                BEGIN:VEVENT
                UID:{eventUid}
                DTSTART;TZID=Asia/Ho_Chi_Minh:{startSecondOverride}
                DTEND;TZID=Asia/Ho_Chi_Minh:{endSecondOverride}
                SUMMARY:Recurrence Meeting
                ORGANIZER;CN={organizer}:mailto:{organizer}
                DTSTAMP:{dtStamp}
                RECURRENCE-ID:{recIdUtc}
                ATTENDEE;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:{organizer}
                SEQUENCE:1
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                ATTENDEE:mailto:{organizer}
                SUMMARY:Recurrence Meeting (Override)
                DESCRIPTION:Starts in 10 minutes
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """
                .replace("{eventUid}", eventUid.value())
                .replace("{organizer}", organizer.username().asString())
                .replace("{startFirst}", startFirstInstanceLocal)
                .replace("{endFirst}", endFirstInstanceLocal)
                .replace("{startSecondOverride}", startSecondOverrideLocal)
                .replace("{endSecondOverride}", endSecondOverrideLocal)
                .replace("{recIdUtc}", originalSecondStartUtcRecId)
                .replace("{dtStamp}", dtStampUtc);

            // Set "now" to before 2nd occurrence
            clock.setInstant(ZonedDateTime.parse("2025-10-02T00:01:00+07:00[Asia/Ho_Chi_Minh]").toInstant());

            // When: organizer creates the recurring event
            davTestHelper.upsertCalendar(organizer, ics, eventUid);

            // Then: next alarm should come from the overridden 2nd occurrence (14:00 - 10' = 13:50 ICT)
            Instant expectedAlarm = ZonedDateTime.parse("2025-10-02T13:50:00+07:00[Asia/Ho_Chi_Minh]").toInstant();

            awaitAtMost.untilAsserted(() -> {
                AlarmEvent alarmEvent = alarmEventDAO
                    .find(eventUid, organizer.username().asMailAddress())
                    .block();

                assertThat(alarmEvent).isNotNull();
                assertThat(alarmEvent.alarmTime()).isEqualTo(expectedAlarm);

                // Also assert recurrence-id matches the overridden instance
                assertThat(alarmEvent.recurrenceId())
                    .contains(originalSecondStartUtcRecId);
            });
        }

        @Test
        void shouldIgnoreDeclinedOccurrenceAndTriggerAlarmForNextAvailable() {
            // Given (fixed times for readability)
            // Recurrence (master): 01/10 11:00–12:00, 02/10 11:00–12:00, 03/10 11:00–12:00 (ICT)
            // 2nd occurrence is overridden (time 14:00–15:00) but ATTENDEE is DECLINED
            String startFirstInstanceLocal = "20251001T110000";
            String endFirstInstanceLocal = "20251001T120000";
            String startSecondOverrideLocal = "20251002T140000";
            String endSecondOverrideLocal = "20251002T150000";
            String originalSecondStartUtcRecId = "20251002T040000Z"; // 11:00 ICT -> 04:00Z
            String dtStampUtc = "20250930T000000Z";

            EventUid eventUid = new EventUid(UUID.randomUUID().toString());

            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                CALSCALE:GREGORIAN
                PRODID:-//App//ICS//EN
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
                UID:{eventUid}
                DTSTART;TZID=Asia/Ho_Chi_Minh:{startFirst}
                DTEND;TZID=Asia/Ho_Chi_Minh:{endFirst}
                SUMMARY:Recurrence Meeting
                RRULE:FREQ=DAILY;COUNT=3
                ORGANIZER;CN={organizer}:mailto:{organizer}
                ATTENDEE;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:{organizer}
                DTSTAMP:{dtStamp}
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                ATTENDEE:mailto:{organizer}
                SUMMARY:Recurrence Meeting
                DESCRIPTION:Starts in 10 minutes
                END:VALARM
                END:VEVENT
                BEGIN:VEVENT
                UID:{eventUid}
                DTSTART;TZID=Asia/Ho_Chi_Minh:{startSecondOverride}
                DTEND;TZID=Asia/Ho_Chi_Minh:{endSecondOverride}
                SUMMARY:Recurrence Meeting
                ORGANIZER;CN={organizer}:mailto:{organizer}
                DTSTAMP:{dtStamp}
                RECURRENCE-ID:{recIdUtc}
                ATTENDEE;PARTSTAT=DECLINED;ROLE=CHAIR:mailto:{organizer}
                SEQUENCE:1
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                ATTENDEE:mailto:{organizer}
                SUMMARY:Recurrence Meeting (Override)
                DESCRIPTION:Starts in 10 minutes
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """
                .replace("{eventUid}", eventUid.value())
                .replace("{organizer}", organizer.username().asString())
                .replace("{startFirst}", startFirstInstanceLocal)
                .replace("{endFirst}", endFirstInstanceLocal)
                .replace("{startSecondOverride}", startSecondOverrideLocal)
                .replace("{endSecondOverride}", endSecondOverrideLocal)
                .replace("{recIdUtc}", originalSecondStartUtcRecId)
                .replace("{dtStamp}", dtStampUtc);

            // Set "now" to day-2 00:01 ICT (well before original 11:00 ICT)
            clock.setInstant(ZonedDateTime.parse("2025-10-02T00:01:00+07:00[Asia/Ho_Chi_Minh]").toInstant());

            // When: organizer creates the recurring event
            davTestHelper.upsertCalendar(organizer, ics, eventUid);

            // Then: because the 2nd is DECLINED for the attendee, the next alarm should be the 3rd occurrence
            // 3rd occurrence starts at 2025-10-03 11:00 ICT -> alarm at 10:50 ICT
            Instant expectedAlarm = ZonedDateTime
                .parse("2025-10-03T10:50:00+07:00[Asia/Ho_Chi_Minh]")
                .toInstant();

            awaitAtMost.untilAsserted(() -> {
                AlarmEvent alarmEvent = alarmEventDAO
                    .find(eventUid, organizer.username().asMailAddress())
                    .block();

                assertThat(alarmEvent).isNotNull();
                assertThat(alarmEvent.alarmTime()).isEqualTo(expectedAlarm);
                assertThat(alarmEvent.recurrenceId()).contains("20251003T040000Z");
            });
        }
    }

    private EventUid createEventWithVALARM(OpenPaaSUser... attendees) {
        String eventUid = UUID.randomUUID().toString();
        String organizerEmail = organizer.username().asString();

        List<String> attendeeEmails = Arrays.stream(attendees)
            .map(u -> u.username().asString())
            .collect(Collectors.toList());

        String ics = generateEventWithValarm(
            eventUid,
            organizerEmail,
            attendeeEmails,
            PartStat.NEEDS_ACTION,
            """
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                ATTENDEE:mailto:%s
                SUMMARY:Meeting Reminder
                DESCRIPTION:This is an automatic alarm
                END:VALARM""".formatted(organizerEmail));

        davTestHelper.upsertCalendar(organizer, ics, eventUid);
        return new EventUid(eventUid);
    }

    private AlarmEvent awaitAlarmEventCreated(EventUid eventUid, Username username) {
        AtomicReference<AlarmEvent> reference = new AtomicReference<>();

        awaitAtMost.untilAsserted(() -> {
            Optional<AlarmEvent> alarmEventOptional = alarmEventDAO.find(eventUid, username.asMailAddress())
                .blockOptional();
            assertThat(alarmEventOptional).isPresent();
            reference.set(alarmEventOptional.get());
        });
        return reference.get();
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
            organizerEmail,
            organizerEmail,
            attendeeLines,
            vAlarm);
    }

    private void attendeeAcceptsEvent(OpenPaaSUser attendee, EventUid eventUid) {
        waitForFirstEventCreation(attendee);
        awaitAtMost.untilAsserted(() -> assertThatCode(() -> calDavEventRepository.updatePartStat(attendee.username(),
            attendee.id(), eventUid, PartStat.ACCEPTED).block())
            .doesNotThrowAnyException());
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
