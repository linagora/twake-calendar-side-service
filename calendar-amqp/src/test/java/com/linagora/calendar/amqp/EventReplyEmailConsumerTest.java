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

import static com.linagora.calendar.amqp.EventInviteEmailConsumerTest.INTERNAL_USER;
import static com.linagora.calendar.amqp.TestFixture.extractSubject;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.user.api.UsersRepository;
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
import com.linagora.calendar.api.EventParticipationActionLinkFactory;
import com.linagora.calendar.api.Participation;
import com.linagora.calendar.api.ParticipationTokenSigner;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

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

    private static final SettingsBasedResolver settingsResolver = mock(SettingsBasedResolver.class);
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

        davTestHelper = new DavTestHelper(dockerSabreDavSetup.davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBResourceDAO resourceDAO;
    private OpenPaaSUser organizer;
    private OpenPaaSUser attendee;
    private Sender sender;

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();

        when(settingsResolver.resolveOrDefault(any(Username.class), any(Username.class)))
            .thenReturn(Mono.just(SettingsBasedResolver.ResolvedSettings.DEFAULT));
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

        Mockito.reset(settingsResolver);
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

        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.systemUTC());

        MessageGenerator.Factory messageFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem, openPaaSUserDAO);
        EventInCalendarLinkFactory linkFactory = new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL());

        UsersRepository usersRepository = mock(UsersRepository.class);
        when(usersRepository.containsReactive(any())).thenReturn(Mono.just(INTERNAL_USER));
        ParticipationTokenSigner participationTokenSigner = mock(ParticipationTokenSigner.class);
        when(participationTokenSigner.signAsJwt(any(Participation.class)))
            .thenReturn(Mono.just("signedToken"));

        EventParticipationActionLinkFactory actionLinkFactory = new EventParticipationActionLinkFactory(
            participationTokenSigner,
            URI.create("http://localhost:8888/").toURL());
        EventMailHandler mailHandler = new EventMailHandler(mailSenderFactory,
            messageFactory,
            linkFactory,
            usersRepository, resourceDAO, domainDAO,
            settingsResolver,
            actionLinkFactory);

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

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DECLINED", "TENTATIVE"})
    void shouldSendEmailWhenAttendeeRepliesToEvent(String partStatValue) {
        when(settingsResolver.resolveOrDefault(any(Username.class), any(Username.class)))
            .thenReturn(Mono.just(SettingsBasedResolver.ResolvedSettings.DEFAULT));
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
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        // Then: Received mail
        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        Function<String, String> partStatDisplayFunction = partStat -> switch (partStat) {
            case "ACCEPTED" -> "Accepted";
            case "DECLINED" -> "Declined";
            case "TENTATIVE" -> "Maybe";
            default -> partStat;
        };

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[1].from")).isEqualTo(attendee.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].recipients[0].address")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].message"))
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
    void shouldDisplayUserFullNameInEmailWhenAttendeeCNIsEmpty() {
        String eventUid = UUID.randomUUID().toString();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

        String sampleCalendarData = """
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
            UID:{eventUid}
            DTSTAMP:{dtStamp}Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{startDateTime}
            DTEND;TZID=Asia/Ho_Chi_Minh:{endDateTime}
            SUMMARY:Sprint planning #04
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat}:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.username().asString())
            .replace("{attendeeEmail}", attendee.username().asString())
            .replace("{startDateTime}", LocalDateTime.now().plusDays(3).format(dateTimeFormatter))
            .replace("{endDateTime}", LocalDateTime.now().plusDays(3).plusHours(1).format(dateTimeFormatter))
            .replace("{dtStamp}", LocalDateTime.now().format(dateTimeFormatter))
            .replace("{partStat}", PartStat.NEEDS_ACTION.getValue());

        davTestHelper.upsertCalendar(organizer, sampleCalendarData, eventUid);

        String eventDavIdOnAttendee = waitForEventCreation(attendee);
        String replyCalendarData = sampleCalendarData.replace(PartStat.NEEDS_ACTION.getValue(), PartStat.ACCEPTED.getValue());

        davTestHelper.upsertCalendar(attendee, replyCalendarData, eventDavIdOnAttendee);

        awaitAtMost
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[1].from")).isEqualTo(attendee.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].recipients[0].address")).isEqualTo(organizer.username().asString());

            String subject = extractSubject(smtpMailsResponse.getString("[1].message"));
            softly.assertThat(subject)
                .isEqualTo("Accepted: Sprint planning #04 (%s)".formatted(attendee.fullName()));
        }));
    }

    @Test
    void shouldSendLocalizedEmailAccordingToUserLanguageSetting() {
        when(settingsResolver.resolveOrDefault(any(Username.class), any(Username.class)))
            .thenReturn(Mono.just( new SettingsBasedResolver.ResolvedSettings(Map.of(
                LANGUAGE_IDENTIFIER, Locale.FRENCH,
                SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER, ZoneId.of("UTC")
            ))));

        JsonPath smtpMailsResponse = simulateAcceptedReplyAndWaitForEmail();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[1].from")).isEqualTo(attendee.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].recipients[0].address")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].message"))
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
        assertThat(smtpMailsResponse.getString("[1].message"))
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
        when(eventEmailFilter.shouldProcess(any(MailAddress.class)))
            .thenThrow(new RuntimeException("Temporary exception"));

        String replyDataFirst = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.ACCEPTED);
        davTestHelper.upsertCalendar(attendee, replyDataFirst, eventDavIdOnAttendee);

        Thread.sleep(1000); // Wait for the exception to be processed

        // Recover
        Mockito.reset(eventEmailFilter);
        when(eventEmailFilter.shouldProcess(any(MailAddress.class)))
            .thenReturn(true);

        String replyDataSecond = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.DECLINED);
        davTestHelper.upsertCalendar(attendee, replyDataSecond, eventDavIdOnAttendee);

        awaitAtMost
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        assertThat(smtpMailsResponseSupplier.get().getString("[1].message"))
            .contains("Subject: =?ISO-8859-1?Q?Declined:_Twake_Calendar")
            .containsIgnoringNewLines("""
                    Content-Type: text/calendar; charset=UTF-8; method=REPLY""");
    }

    @Test
    void shouldNotSendEmailWhenRecipientIsNotInWhitelist() throws InterruptedException {
        when(eventEmailFilter.shouldProcess(any(MailAddress.class)))
            .thenReturn(false);

        // Given: Organizer creates calendar event with attendee
        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.NEEDS_ACTION);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);

        // When: Attendee replies to the event
        String eventDavIdOnAttendee = waitForEventCreation(attendee);
        String replyCalendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            PartStat.ACCEPTED);
        davTestHelper.upsertCalendar(attendee, replyCalendarData, eventDavIdOnAttendee);

        Thread.sleep(1000); // Wait for the message to be processed
        assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty();
    }

    @Test
    void shouldNotSendReplyWhenUpdatePartStatOfResource() throws Exception {
        ResourceAdministrator administrator = new ResourceAdministrator(organizer.id(), "user");
        OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(organizer.username().getDomainPart().get()).block();
        ResourceInsertRequest insertRequest = new ResourceInsertRequest(
            List.of(administrator),
            administrator.refId(),
            "This is a projector made in China",
            openPaaSDomain.id(),
            "projector",
            "Projector 1");

        ResourceId resourceId = resourceDAO.insert(insertRequest).block();
        String resourceEmail = Username.fromLocalPartWithDomain(resourceId.value(), openPaaSDomain.domain()).asString();
        String eventUid = UUID.randomUUID().toString();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String startDateTime = LocalDateTime.now().plusDays(3).format(dateTimeFormatter);
        String endDateTime = LocalDateTime.now().plusDays(3).plusHours(1).format(dateTimeFormatter);

        String icalData = """
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
            UID:{eventUid}
            DTSTAMP:{dtStamp}Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{startDateTime}
            DTEND;TZID=Asia/Ho_Chi_Minh:{endDateTime}
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;
             CN=Resource;SCHEDULE-STATUS=5.1:mailto:{resourceEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.username().asString())
            .replace("{resourceEmail}", resourceEmail)
            .replace("{startDateTime}", startDateTime)
            .replace("{endDateTime}", endDateTime)
            .replace("{dtStamp}", LocalDateTime.now().format(dateTimeFormatter))
            .replace("{partStat}", PartStat.NEEDS_ACTION.getValue());

        davTestHelper.upsertCalendar(organizer, icalData, eventUid);
        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(davTestHelper.findFirstEventId(resourceId, openPaaSDomain.id()))
                .withFailMessage("Event not created for resource: " + resourceId.value())
                .isPresent());

        String eventPathId = davTestHelper.findFirstEventId(resourceId, openPaaSDomain.id()).get();
        calDavEventRepository().updatePartStat(openPaaSDomain, resourceId, eventPathId, PartStat.ACCEPTED).block();


        Thread.sleep(2000); // Wait for the message to be processed
        assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty();
    }

    private CalDavEventRepository calDavEventRepository() throws SSLException {
        CalDavClient calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        return new CalDavEventRepository(calDavClient);
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
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

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
