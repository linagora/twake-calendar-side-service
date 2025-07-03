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

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.MaybeSender;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.util.Port;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedLocator;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class EventReplyEmailConsumerTest {
    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    private static final SettingsBasedLocator settingsLocator = mock(SettingsBasedLocator.class);
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
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    private OpenPaaSUser organizer;
    private OpenPaaSUser attendee;
    private Sender sender;

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();

        when(settingsLocator.getLanguageUserSetting(any())).thenReturn(Mono.just(Locale.ENGLISH));

        setupEventEmailConsumer();
        clearSmtpMock();
    }

    @AfterEach
    void afterEach() {
        Arrays.stream(EventIndexerConsumer.Queue
                .values())
            .map(EventIndexerConsumer.Queue::queueName)
            .forEach(queueName -> sender.delete(QueueSpecification.queue().name(queueName))
                .block());

        Mockito.reset(settingsLocator);
    }

    private void setupEventEmailConsumer() throws Exception {
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

        MessageGenerator.Factory messageFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem);
        EventInCalendarLinkFactory linkFactory = new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL());

        EventMailHandler mailHandler = new EventMailHandler(mailSenderFactory,
            mailTemplateConfig,
            settingsLocator,
            messageFactory,
            linkFactory,
            new SimpleSessionProvider(new RandomMailboxSessionIdGenerator()));

        EventEmailConsumer consumer = new EventEmailConsumer(channelPool, QueueArguments.Builder::new, mailHandler);
        consumer.init();

        sender = channelPool.getSender();
    }

    private void clearSmtpMock() {
        given(mockSMTPRequestSpecification()).delete("/smtpMails").then();
        given(mockSMTPRequestSpecification()).delete("/smtpBehaviors").then();
    }

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DECLINED", "TENTATIVE"})
    void shouldSendEmailWhenAttendeeRepliesToEvent(String partStatValue) {
        when(settingsLocator.getLanguageUserSetting(any())).thenReturn(Mono.just(Locale.ENGLISH));
        // Ensure no event exists initially for attendee
        assertThat(davTestHelper.findFirstEventId(attendee)).isEmpty();

        // Given: Organizer creates calendar event with attendee
        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.NEEDS_ACTION);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);

        // When: Attendee replies to the event (e.g. accepts the invitation)
        String eventDavIdOnAttendee = waitForEventCreation(attendee);
        String replyCalendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            new PartStat(partStatValue));
        davTestHelper.upsertCalendar(attendee, replyCalendarData, eventDavIdOnAttendee);

        // Wait for the mail to be received via mock SMTP
        awaitAtMost
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        // Then: Received mail
        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        Function<String, String> partStatDisplayFunction = partStat -> switch (partStat) {
            case "ACCEPTED" -> "Accepted";
            case "DECLINED" -> "Declined";
            case "TENTATIVE" -> "Maybe";
            default -> partStat;
        };

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: =?ISO-8859-1?Q?" + partStatDisplayFunction.apply(partStatValue) + ":_Twake_Calendar")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8""")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/calendar; charset=UTF-8; method=REPLY""")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: application/ics; name="meeting.ics"
                    Content-Disposition: attachment; filename="meeting.ics""");
        }));
    }

    @Test
    void shouldSendLocalizedEmailAccordingToUserLanguageSetting() {
        when(settingsLocator.getLanguageUserSetting(any()))
            .thenReturn(Mono.just(Locale.FRENCH));

        JsonPath smtpMailsResponse = simulateAcceptedReplyAndWaitForEmail();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .containsIgnoringNewLines("""
                    Content-Type: text/calendar; charset=UTF-8; method=REPLY""");
        }));
    }

    @Test
    void consumeShouldNotCrashOnMalformedMessage() {
        channelPool.getSender()
            .send(Mono.just(new OutboundMessage("calendar:event:notificationEmail:send",
                EMPTY_ROUTING_KEY,
                "BAD_PAYLOAD".getBytes(UTF_8))))
            .block();

        JsonPath smtpMailsResponse = simulateAcceptedReplyAndWaitForEmail();
        assertThat(smtpMailsResponse.getString("[0].message"))
            .containsIgnoringNewLines("""
                    Content-Type: text/calendar; charset=UTF-8; method=REPLY""");
    }

    @Test
    void shouldRecoverWhenEventHandlerHasTemporaryException() throws InterruptedException {
        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.NEEDS_ACTION);
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);
        String eventDavIdOnAttendee = waitForEventCreation(attendee);

        // Mock exception
        when(settingsLocator.getLanguageUserSetting(any()))
            .thenReturn(Mono.defer(() -> Mono.error(new RuntimeException("Temporary exception"))));

        String replyDataFirst = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.ACCEPTED);
        davTestHelper.upsertCalendar(attendee, replyDataFirst, eventDavIdOnAttendee);

        Thread.sleep(1000); // Wait for the exception to be processed

        // Recover by returning a valid language setting
        when(settingsLocator.getLanguageUserSetting(any()))
            .thenReturn(Mono.just(Locale.ENGLISH));

        String replyDataSecond = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.DECLINED);
        davTestHelper.upsertCalendar(attendee, replyDataSecond, eventDavIdOnAttendee);

        awaitAtMost
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        assertThat(smtpMailsResponseSupplier.get().getString("[0].message"))
            .contains("Subject: =?ISO-8859-1?Q?Declined")
            .containsIgnoringNewLines("""
                    Content-Type: text/calendar; charset=UTF-8; method=REPLY""");
    }

    private JsonPath simulateAcceptedReplyAndWaitForEmail() {
        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.NEEDS_ACTION);
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);
        String eventDavIdOnAttendee = waitForEventCreation(attendee);
        String replyData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.ACCEPTED);
        davTestHelper.upsertCalendar(attendee, replyData, eventDavIdOnAttendee);

        awaitAtMost
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        return smtpMailsResponseSupplier.get();
    }

    private static final Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
        .get("/smtpMails")
        .jsonPath();

    private String waitForEventCreation(OpenPaaSUser user) {
        awaitAtMost.untilAsserted(() ->
            assertThat(davTestHelper.findFirstEventId(user)).isPresent());

        return davTestHelper.findFirstEventId(user).get();
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        PartStat partStat) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String startDateTime = LocalDateTime.now().plusDays(3).format(dateTimeFormatter);
        String endDateTime = LocalDateTime.now().plusDays(3).plusHours(1).format(dateTimeFormatter);

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
            UID:{eventUid}
            DTSTAMP:{dtStamp}Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{startDateTime}
            DTEND;TZID=Asia/Ho_Chi_Minh:{endDateTime}
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{startDateTime}", startDateTime)
            .replace("{endDateTime}", endDateTime)
            .replace("{dtStamp}", LocalDateTime.now().format(dateTimeFormatter))
            .replace("{partStat}", partStat.getValue());
    }
}
