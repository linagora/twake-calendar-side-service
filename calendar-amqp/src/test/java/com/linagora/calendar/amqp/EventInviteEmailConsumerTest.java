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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.util.Port;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class EventInviteEmailConsumerTest {
    static final boolean INTERNAL_USER = true;
    static final boolean EXTERNAL_USER = false;

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
    private static final EventEmailFilter eventEmailFilter = spy(EventEmailFilter.acceptAll());
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
    private UsersRepository usersRepository;

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();

        when(settingsLocator.getLanguageUserSetting(any(), any())).thenReturn(Mono.just(Locale.ENGLISH));

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
        Mockito.reset(eventEmailFilter);
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

        usersRepository = mock(UsersRepository.class);
        when(usersRepository.containsReactive(any())).thenReturn(Mono.just(INTERNAL_USER));

        EventMailHandler mailHandler = new EventMailHandler(mailSenderFactory,
            settingsLocator,
            messageFactory,
            linkFactory,
            new SimpleSessionProvider(new RandomMailboxSessionIdGenerator()),
            usersRepository);

        EventEmailConsumer consumer = new EventEmailConsumer(channelPool, QueueArguments.Builder::new, mailHandler,
            eventEmailFilter);
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

    @Test
    void shouldSendInviteEmailWhenNewEventIsCreated() {
        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.NEEDS_ACTION);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(attendee.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: New event from Van Tung TRAN: Twake Calendar - Sprint planning #04")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """)
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/calendar; charset=UTF-8; method=REQUEST
                    """)
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: application/ics; name="meeting.ics"
                    Content-Disposition: attachment; filename="meeting.ics"
                    """);

            String html = getHtml(smtpMailsResponse);

            assertThat(html).contains("Van Tung TRAN")
                .contains("has invited you in to a meeting")
                .contains("Monday, 11 April 3025 10:00 - 11:00")
                .contains("See in Calendar")
                .contains("Asia/Ho_Chi_Minh")
                .contains("Twake Meeting Room")
                .contains(organizer.username().asString())
                .contains(attendee.username().asString())
                .contains("This is a meeting to discuss the sprint planning for the next week.");
        }));
    }

    @Test
    void shouldNotIncludeSeeInCalendarLinkEmailWhenRecipientIsNotInternal() {
        when(usersRepository.containsReactive(Username.of("externaluser@gg.com"))).thenReturn(Mono.just(EXTERNAL_USER));

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            "externaluser@gg.com",
            PartStat.NEEDS_ACTION);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo("externaluser@gg.com");
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: New event from Van Tung TRAN: Twake Calendar - Sprint planning #04")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """)
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/calendar; charset=UTF-8; method=REQUEST
                    """)
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: application/ics; name="meeting.ics"
                    Content-Disposition: attachment; filename="meeting.ics"
                    """);

            String html = getHtml(smtpMailsResponse);

            assertThat(html).contains("Van Tung TRAN")
                .contains("has invited you in to a meeting")
                .contains("Monday, 11 April 3025 10:00 - 11:00")
                .contains("Asia/Ho_Chi_Minh")
                .contains("Twake Meeting Room")
                .contains(organizer.username().asString())
                .contains("externaluser@gg.com")
                .contains("This is a meeting to discuss the sprint planning for the next week.")
                .doesNotContain("See in Calendar");
        }));
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

    private static final Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
        .get("/smtpMails")
        .jsonPath();

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        PartStat partStat) {
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
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Twake Calendar - Sprint planning #04
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{partStat}", partStat.getValue());
    }
}

