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

package com.linagora.calendar.scheduling;

import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ALARM_SETTING_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ENABLE_ALARM;
import static com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.RetryBackoffConfiguration;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.util.Port;
import org.apache.james.utils.UpdatableTickingClock;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.MemoryAlarmEventDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmAction;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.eventsearch.EventUid;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

public class AlarmTriggerServiceTest {

    private static final RetryBackoffConfiguration RETRY_BACKOFF_CONFIGURATION = RetryBackoffConfiguration.builder()
        .maxRetries(3)
        .firstBackoff(Duration.ofMillis(5))
        .jitterFactor(0.5)
        .build();

    static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    static final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);
    public static final boolean RECURRING = true;
    static final boolean NO_RECURRING = false;

    @RegisterExtension
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    private RequestSpecification requestSpecification;
    private MemoryAlarmEventDAO alarmEventDAO;
    private UpdatableTickingClock clock;
    private SettingsBasedResolver settingsResolver;
    private AlarmTriggerService testee;

    @BeforeEach
    void setUp() {
        alarmEventDAO = new MemoryAlarmEventDAO();

        MailSenderConfiguration mailSenderConfiguration = new MailSenderConfiguration(
            "localhost",
            Port.of(mockSmtpExtension.getMockSmtp().getSmtpPort()),
            "localhost",
            Optional.empty(),
            Optional.empty(),
            false,
            false,
            false);
        MailSender.Factory mailSenderFactory = new MailSender.Factory.Default(mailSenderConfiguration);

        FileSystemImpl fileSystem = FileSystemImpl.forTesting();
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates");
        MailTemplateConfiguration mailTemplateConfig = new MailTemplateConfiguration("file://" + templateDirectory.toAbsolutePath(),
            MaybeSender.getMailSender("no-reply@openpaas.org"));
        MessageGenerator.Factory messageGeneratorFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem, new MemoryOpenPaaSUserDAO());

        settingsResolver = Mockito.mock(SettingsBasedResolver.class);
        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(SettingsBasedResolver.ResolvedSettings.DEFAULT));

        clock = new UpdatableTickingClock(Instant.now());

        testee = new AlarmTriggerService(
            alarmEventDAO,
            clock,
            mailSenderFactory,
            settingsResolver,
            messageGeneratorFactory,
            new AlarmInstantFactory.Default(clock),
            mailTemplateConfig,
            new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()), RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters())
        );

        requestSpecification = new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();

        clearSmtpMock();
    }

    private void clearSmtpMock() {
        given(requestSpecification).delete("/smtpMails").then();
        given(requestSpecification).delete("/smtpBehaviors").then();
    }

    @Test
    void shouldSendAlarmEmailWhenValidAlarmEvent() throws AddressException {
        Instant now = clock.instant();
        AlarmEvent event = new AlarmEvent(
            new EventUid("event-uid-1"),
            now.minus(10, ChronoUnit.MINUTES),
            now.plus(10, ChronoUnit.MINUTES),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("attendee@abc.com"),
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-uid-1
            DTSTART:20250801T100000Z
            DTEND:20250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        testee.processAlarmAndCleanup(event).block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponse();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo("attendee@abc.com");
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Notification: Alarm Test Event")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);

            String html = getHtml(smtpMailsResponse);

            softly.assertThat(html).contains("This event is about to begin in 10 minutes")
                .contains("Test Room")
                .contains("organizer@abc.com")
                .contains("attendee@abc.com")
                .contains("This is a test alarm event.");
        }));
    }

    @Test
    void shouldSendMultipleAlarmEmailsWhenEventHasMultipleVALARMs() throws Exception {
        EventUid eventUid = new EventUid("multi-valarm-event");
        MailAddress recipient = new MailAddress("attendee@abc.com");

        AlarmEvent event = new AlarmEvent(
            eventUid,
            parse("30250801T094500Z"),
            parse("30250801T100000Z"),
            NO_RECURRING,
            Optional.empty(),
            recipient,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:multi-valarm-event
                DTSTART:30250801T100000Z
                DTEND:30250801T110000Z
                SUMMARY:Multi-Alarm Event
                LOCATION:Test Room
                DESCRIPTION:Testing multiple VALARMs
                ORGANIZER:mailto:organizer@abc.com
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:attendee@abc.com
                BEGIN:VALARM
                TRIGGER:-PT30M
                ACTION:EMAIL
                DESCRIPTION:First Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                DESCRIPTION:Second Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        clock.setInstant(parse("30250801T083000Z"));
        testee.processAlarmAndCleanup(event).block();

        awaitAtMost.untilAsserted(() ->
            assertThat(smtpMailsResponse().getList("")).hasSize(1));

        AlarmEvent persistedEvent = alarmEventDAO.find(eventUid, recipient).block();
        assertThat(persistedEvent).isNotNull();
        assertThat(persistedEvent.alarmTime()).isEqualTo(parse("30250801T095000Z"));
        testee.processAlarmAndCleanup(persistedEvent).block();

        awaitAtMost.untilAsserted(() ->
            assertThat(smtpMailsResponse().getList("")).hasSize(2));
    }

    @Test
    void shouldSkipPastVALARMsAndSendNextFutureOne() throws Exception {
        EventUid eventUid = new EventUid("skip-past-valarm");
        MailAddress recipient = new MailAddress("attendee@abc.com");

        AlarmEvent event = new AlarmEvent(eventUid,
            parse("30250801T095500Z"),
            parse("30250801T100000Z"),
            NO_RECURRING,
            Optional.empty(),
            recipient,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:skip-past-valarm
                DTSTART:30250801T100000Z
                DTEND:30250801T110000Z
                SUMMARY:Past VALARM should be skipped
                LOCATION:Test Room
                DESCRIPTION:Skip past alarms test
                ORGANIZER:mailto:organizer@abc.com
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:attendee@abc.com
                BEGIN:VALARM
                TRIGGER:-PT30M
                ACTION:EMAIL
                DESCRIPTION:Old Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                BEGIN:VALARM
                TRIGGER:-PT5M
                ACTION:EMAIL
                DESCRIPTION:Upcoming Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        clock.setInstant(parse("30250801T095500Z"));
        testee.processAlarmAndCleanup(event).block();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponse();
        assertSoftly(Throwing.consumer(softly -> {
            String html = getHtml(smtpMailsResponse);
            softly.assertThat(html)
                .contains("This event is about to begin in 5 minutes")
                .contains("Past VALARM should be skipped")
                .contains("Test Room")
                .contains("Skip past alarms test");
        }));

        assertThat(alarmEventDAO.find(eventUid, recipient).blockOptional())
            .isEmpty();
    }

    @Test
    void shouldNotSendWhenAllVALARMsAlreadyPast() throws Exception {
        EventUid eventUid = new EventUid("all-valarms-past");
        MailAddress recipient = new MailAddress("attendee@abc.com");

        AlarmEvent event = new AlarmEvent(eventUid,
            parse("30250801T093000Z"), // alarmTime in the past
            parse("30250801T100000Z"),
            NO_RECURRING,
            Optional.empty(),
            recipient,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:all-valarms-past
                DTSTART:30250801T100000Z
                DTEND:30250801T110000Z
                SUMMARY:All VALARMs Past Event
                LOCATION:Test Room
                DESCRIPTION:All VALARMs already passed test
                ORGANIZER:mailto:organizer@abc.com
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:attendee@abc.com
                BEGIN:VALARM
                TRIGGER:-PT45M
                ACTION:EMAIL
                DESCRIPTION:Old Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                BEGIN:VALARM
                TRIGGER:-PT30M
                ACTION:EMAIL
                DESCRIPTION:Older Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        // Current time is after both -45m and -30m → all alarms past
        clock.setInstant(parse("30250801T100100Z"));

        testee.processAlarmAndCleanup(event).block();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).isEmpty());
        assertThat(alarmEventDAO.find(eventUid, recipient).blockOptional()).isEmpty();
    }

    @Test
    void shouldDeleteAlarmEventAfterSendingAlarmEmail() throws AddressException {
        Instant now = clock.instant();
        EventUid eventUid = new EventUid("event-uid-1");
        AlarmEvent event = new AlarmEvent(
            eventUid,
            now.minusSeconds(10),
            now.plusSeconds(3600),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("attendee@abc.com"),
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTART:20250801T100000Z
            DTEND:20250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid.value()),
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        testee.processAlarmAndCleanup(event).block();

        assertThat(alarmEventDAO.find(eventUid, new MailAddress("attendee@abc.com")).blockOptional()).isEmpty();
    }

    @Test
    void shouldNotSendAlarmEmailWhenEventStartTimeIsLessThanCurrentTime() throws AddressException {
        Instant now = clock.instant();
        EventUid eventUid = new EventUid("event-uid-1");
        AlarmEvent event = new AlarmEvent(
            eventUid,
            now.minus(30, ChronoUnit.MINUTES),
            now.minus(15, ChronoUnit.MINUTES),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("attendee@abc.com"),
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-uid-1
            DTSTART:20250801T100000Z
            DTEND:20250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:attendee@abc.com
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        testee.processAlarmAndCleanup(event).block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(0));

        assertThat(alarmEventDAO.find(eventUid, new MailAddress("attendee@abc.com")).blockOptional()).isEmpty();
    }

    @Test
    void shouldNotSendAlarmEmailWhenAlarmIsDisabled() throws AddressException {
        reset(settingsResolver);
        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(
                    LANGUAGE_IDENTIFIER, Locale.ENGLISH,
                    TIMEZONE_IDENTIFIER, ZoneId.of("UTC"),
                    ALARM_SETTING_IDENTIFIER, !ENABLE_ALARM
                ))));

        Instant now = clock.instant();
        AlarmEvent event = new AlarmEvent(
            new EventUid("event-uid-1"),
            now.minus(10, ChronoUnit.MINUTES),
            now.plus(10, ChronoUnit.MINUTES),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("attendee@abc.com"),
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event-uid-1
            DTSTART:20250801T100000Z
            DTEND:20250801T110000Z
            SUMMARY:Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        testee.processAlarmAndCleanup(event).block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(0));
    }

    @Test
    void shouldSendAlarmEmailWhenEventIsRecurring() throws AddressException {
        EventUid eventUid = new EventUid("recurring-event-uid-1");
        MailAddress recipient = new MailAddress("attendee@abc.com");
        AlarmEvent event = new AlarmEvent(
            eventUid,
            parse("30250801T094500Z"),
            parse("30250801T100000Z"),
            RECURRING,
            Optional.of("30250801T100000Z"),
            recipient,
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-event-uid-1
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:attendee@abc.com
            RRULE:FREQ=DAILY;COUNT=3
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:attendee@abc.com
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        clock.setInstant(parse("30250801T094500Z"));
        testee.processAlarmAndCleanup(event).block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponse();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo("attendee@abc.com");
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Notification: Recurring Alarm Test Event")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);

            String html = getHtml(smtpMailsResponse);

            softly.assertThat(html).contains("This event is about to begin in 15 minutes")
                .contains("Recurring Alarm Test Event")
                .contains("Test Room")
                .contains("organizer@abc.com")
                .contains("attendee@abc.com")
                .contains("This is a recurring test alarm event.");
        }));
    }

    @Test
    void shouldSendAlarmEmailWithTheContentOfChildEventInRecurringEvent() throws AddressException {
        EventUid eventUid = new EventUid("recurring-event-uid-1");
        MailAddress recipient = new MailAddress("attendee@abc.com");
        AlarmEvent event = new AlarmEvent(
            eventUid,
            parse("30250801T094500Z"),
            parse("30250801T100000Z"),
            RECURRING,
            Optional.of("30250801T100000Z"),
            recipient,
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-event-uid-1
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:attendee@abc.com
            RRULE:FREQ=DAILY;COUNT=3
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:attendee@abc.com
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring-event-uid-1
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event With Recurrence ID
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:attendee@abc.com
            RECURRENCE-ID:30250801T100000Z
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:attendee@abc.com
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        clock.setInstant(parse("30250801T094500Z"));
        testee.processAlarmAndCleanup(event).block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponse();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo("attendee@abc.com");
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Notification: Recurring Alarm Test Event With Recurrence ID")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);

            String html = getHtml(smtpMailsResponse);

            softly.assertThat(html).contains("This event is about to begin in 15 minutes")
                .contains("Recurring Alarm Test Event With Recurrence ID")
                .contains("Test Room")
                .contains("organizer@abc.com")
                .contains("attendee@abc.com")
                .contains("This is a recurring test alarm event.");
        }));
    }

    @Test
    void shouldUpdateRecurringAlarmForNextOccurrence() throws AddressException {
        EventUid eventUid = new EventUid("recurring-event-uid-1");
        MailAddress recipient = new MailAddress("attendee@abc.com");
        AlarmEvent event = new AlarmEvent(
            eventUid,
            parse("30250801T094500Z"),
            parse("30250801T100000Z"),
            RECURRING,
            Optional.of("30250801T100000Z"),
            recipient,
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-event-uid-1
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:attendee@abc.com
            RRULE:FREQ=DAILY;COUNT=3
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:attendee@abc.com
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        clock.setInstant(parse("30250801T094500Z"));
        testee.processAlarmAndCleanup(event).block();

        AlarmEvent actual = alarmEventDAO.find(eventUid, recipient).block();
        assertThat(actual.alarmTime()).isEqualTo(parse("30250802T094500Z"));
        assertThat(actual.eventStartTime()).isEqualTo(parse("30250802T100000Z"));
    }

    @Test
    void shouldDeleteRecurringAlarmIfNoMoreOccurrences() throws AddressException {
        EventUid eventUid = new EventUid("recurring-event-uid-1");
        MailAddress recipient = new MailAddress("attendee@abc.com");
        AlarmEvent event = new AlarmEvent(
            eventUid,
            parse("30250803T094500Z"),
            parse("30250803T100000Z"),
            RECURRING,
            Optional.of("30250805T100000Z"),
            recipient,
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:recurring-event-uid-1
            DTSTART:30250801T100000Z
            DTEND:30250801T110000Z
            SUMMARY:Recurring Alarm Test Event
            LOCATION:Test Room
            DESCRIPTION:This is a recurring test alarm event.
            ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
            ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:attendee@abc.com
            RRULE:FREQ=DAILY;COUNT=3
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:attendee@abc.com
            DESCRIPTION:Reminder
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        clock.setInstant(parse("30250803T094500Z"));
        testee.processAlarmAndCleanup(event).block();

        assertThat(alarmEventDAO.find(eventUid, recipient).blockOptional()).isEmpty();
    }

    @Test
    void shouldHandleMultipleVALARMsInRecurringEvent() throws Exception {
        EventUid eventUid = new EventUid("recurring-multi-valarm-event");
        MailAddress recipient = new MailAddress("attendee@abc.com");

        AlarmEvent event = new AlarmEvent(eventUid,
            parse("30250801T094500Z"),
            parse("30250801T100000Z"),
            RECURRING,
            Optional.of("30250801T100000Z"),
            recipient,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:recurring-multi-valarm-event
                DTSTART:30250801T100000Z
                DTEND:30250801T110000Z
                SUMMARY:Recurring Event with Multiple VALARMs
                LOCATION:Test Room
                DESCRIPTION:Test recurring event with multiple alarms
                ORGANIZER:mailto:organizer@abc.com
                ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:attendee@abc.com
                RRULE:FREQ=DAILY;COUNT=2
                BEGIN:VALARM
                TRIGGER:-PT30M
                ACTION:EMAIL
                DESCRIPTION:First Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                DESCRIPTION:Second Reminder
                ATTENDEE:mailto:attendee@abc.com
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        alarmEventDAO.create(event).block();

        // First occurrence: trigger -30m alarm
        clock.setInstant(parse("30250801T093000Z"));
        testee.processAlarmAndCleanup(event).block();
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(1));

        AlarmEvent updatedEvent = alarmEventDAO.find(eventUid, recipient).block();
        assertThat(updatedEvent)
            .isNotNull()
            .extracting(AlarmEvent::alarmTime)
            .isEqualTo(parse("30250801T095000Z"));

        // Trigger second alarm (-10m) of first occurrence
        testee.processAlarmAndCleanup(updatedEvent).block();
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(2));

        // Move to next recurrence (day 2) -> should reset to -30m
        AlarmEvent nextEvent = alarmEventDAO.find(eventUid, recipient).block();
        assertThat(nextEvent)
            .isNotNull()
            .extracting(AlarmEvent::alarmTime)
            .isEqualTo(parse("30250802T093000Z"));

        // Trigger first alarm of second occurrence
        clock.setInstant(parse("30250802T093000Z"));
        testee.processAlarmAndCleanup(nextEvent).block();
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponse().getList("")).hasSize(3));
    }

    @Test
    void shouldComputeNextVALARMForSameRecipientWhenSomeVALARMsBelongToOthers() throws Exception {
        EventUid eventUid = new EventUid("multi-recipient-valarm-event");
        MailAddress bob = new MailAddress("bob@abc.com");
        MailAddress alice = new MailAddress("alice@abc.com");

        AlarmEvent event = new AlarmEvent(eventUid,
            parse("30250801T093000Z"), // current alarm time (-30m)
            parse("30250801T100000Z"), // event start
            NO_RECURRING,
            Optional.empty(),
            bob,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:multi-recipient-valarm-event
                DTSTART:30250801T100000Z
                DTEND:30250801T110000Z
                SUMMARY:Event with mixed VALARMs
                LOCATION:Test Room
                DESCRIPTION:Test mixed recipients
                ORGANIZER:mailto:organizer@abc.com
                ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:bob@abc.com
                ATTENDEE;PARTSTAT=accepted;CN=Test Attendee:mailto:alice@abc.com
                BEGIN:VALARM
                TRIGGER:-PT30M
                ACTION:EMAIL
                DESCRIPTION:First Reminder (Bob)
                ATTENDEE:mailto:bob@abc.com
                END:VALARM
                BEGIN:VALARM
                TRIGGER:-PT20M
                ACTION:EMAIL
                DESCRIPTION:Second Reminder (Alice)
                ATTENDEE:mailto:alice@abc.com
                END:VALARM
                BEGIN:VALARM
                TRIGGER:-PT10M
                ACTION:EMAIL
                DESCRIPTION:Third Reminder (Bob)
                ATTENDEE:mailto:bob@abc.com
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """,
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);

        alarmEventDAO.create(event).block();

        // Simulate current time after -30m (alarm 1) but before -20m (alarm 2)
        clock.setInstant(parse("30250801T094000Z"));

        // Trigger service
        testee.processAlarmAndCleanup(event).block();

        // Verify that AlarmEvent is updated to the next alarm (Bob’s -10m)
        AlarmEvent persisted = alarmEventDAO.find(eventUid, bob).block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.alarmTime()).isEqualTo(parse("30250801T095000Z"));
    }

    private JsonPath smtpMailsResponse() {
        return given(requestSpecification).get("/smtpMails").jsonPath();
    }

    private String getHtml(JsonPath smtpMailsResponse) {
        String rawMessage = smtpMailsResponse.getString("[0].message");
        Pattern htmlPattern = Pattern.compile(
            "Content-Transfer-Encoding: base64\r?\nContent-Type: text/html; charset=UTF-8\r?\nContent-Language: [^\r\n]+\r?\n\r?\n([A-Za-z0-9+/=\r\n]+)\r?\n---=Part",
            java.util.regex.Pattern.DOTALL);
        Matcher matcher = htmlPattern.matcher(rawMessage);
        matcher.find();
        String base64Html = matcher.group(1).replaceAll("\\s+", "");
        return new String(Base64.getDecoder().decode(base64Html), StandardCharsets.UTF_8);
    }

    private Instant parse(String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");
        return ZonedDateTime.parse(date, formatter).toInstant();
    }
}
