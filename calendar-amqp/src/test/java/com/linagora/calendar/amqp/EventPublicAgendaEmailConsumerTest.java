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
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
import org.mockito.Mockito;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.linagora.calendar.api.EventParticipationActionLinkFactory;
import com.linagora.calendar.api.Participation;
import com.linagora.calendar.api.ParticipationTokenSigner;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
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

public class EventPublicAgendaEmailConsumerTest {
    private static final String X_PUBLICLY_CREATED_HEADER = "X-PUBLICLY-CREATED:true";
    private static final String X_PUBLICLY_CREATOR_HEADER = "X-PUBLICLY-CREATOR:%s";
    private static final int INITIAL_SEQUENCE = 1;
    private static final int UPDATED_SEQUENCE = INITIAL_SEQUENCE + 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory calmlyAwaitDuringNoEmail = calmlyAwait.during(2, TimeUnit.SECONDS);
    private final ConditionFactory awaitAtMostForEmailDelivery = calmlyAwait.atMost(Duration.ofSeconds(20));

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

    private OpenPaaSUser organizer;
    private OpenPaaSUser attendee;
    private Sender sender;
    private UsersRepository usersRepository;
    private EventEmailConsumer consumer;

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();

        when(settingsResolver.resolveOrDefault(any(Username.class), any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(
                    LANGUAGE_IDENTIFIER, Locale.ENGLISH,
                    TIMEZONE_IDENTIFIER, ZoneId.of("Asia/Ho_Chi_Minh")))));

        setupEventEmailConsumer();
        clearSmtpMock();
    }

    @AfterEach
    void afterEach() {
        if (consumer != null) {
            consumer.close();
        }

        Arrays.stream(EventIndexerConsumer.Queue.values())
            .map(EventIndexerConsumer.Queue::queueName)
            .forEach(queueName -> sender.delete(QueueSpecification.queue().name(queueName)).block());

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

        MailSender.Factory mailSenderFactory = new MailSender.Factory.Default(mailSenderConfiguration, EventEmailFilter.acceptAll());
        FileSystemImpl fileSystem = FileSystemImpl.forTesting();

        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates");
        MailTemplateConfiguration mailTemplateConfig = new MailTemplateConfiguration("file://" + templateDirectory.toAbsolutePath(),
            MaybeSender.getMailSender("no-reply@openpaas.org"));

        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        ResourceDAO resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.systemUTC());

        MessageGenerator.Factory messageFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem, openPaaSUserDAO);
        EventInCalendarLinkFactory linkFactory = new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL());

        usersRepository = mock(UsersRepository.class);
        when(usersRepository.containsReactive(any())).thenReturn(Mono.just(INTERNAL_USER));

        ParticipationTokenSigner participationTokenSigner = mock(ParticipationTokenSigner.class);
        when(participationTokenSigner.signAsJwt(any(Participation.class)))
            .thenAnswer(invocation -> {
                Participation arg = invocation.getArgument(0);
                return Mono.just("mocked-jwt-token-" + arg.action().name().toLowerCase());
            });

        EventParticipationActionLinkFactory actionLinkFactory = new EventParticipationActionLinkFactory(
            participationTokenSigner,
            URI.create("http://localhost:8888/").toURL());
        EventMailHandler mailHandler = new EventMailHandler(mailSenderFactory,
            messageFactory,
            linkFactory,
            usersRepository, resourceDAO, domainDAO,
            settingsResolver,
            actionLinkFactory);

        consumer = new EventEmailConsumer(channelPool, QueueArguments.Builder::new, mailHandler,
            eventEmailFilter, new RecordingMetricFactory());
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
    void shouldNotSendEmailWhenPublicAgendaIsCreatedWithOrganizerNeedsAction() {
        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, INITIAL_SEQUENCE);

        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);

        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());
    }

    @Test
    void shouldSendEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedOnPublicAgenda() {
        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, INITIAL_SEQUENCE);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);
        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());

        clearSmtpMock();
        String updatedCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE);
        davTestHelper.upsertCalendar(organizer, updatedCalendarData, eventUid);

        awaitAtMostForEmailDelivery
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();
        String message = smtpMailsResponse.getString("[0].message");

        assertThat(extractDecodedPart(message, "text/html; charset=UTF-8"))
            .contains("Van Tung TRAN")
            .contains("has accepted your event proposal")
            .contains("(Proposer)")
            .doesNotContain("Will you attend this event?")
            .doesNotContain("Resources");
        assertThat(message)
            .contains("Subject: Van Tung TRAN has accepted your event proposal")
            .contains("Content-Type: text/html; charset=UTF-8")
            .contains("Content-Type: text/calendar; charset=UTF-8; method=REQUEST")
            .contains("Content-Type: application/ics")
            .contains("filename=\"meeting.ics\"")
            .doesNotContain("Public agenda event notification.");
        assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo(organizer.username().asString());
        assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(attendee.username().asString());
    }

    @Test
    void shouldNotSendEmailWhenOrganizerStillNeedsActionAfterPublicAgendaUpdate() {
        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, INITIAL_SEQUENCE);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);
        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());

        clearSmtpMock();
        String updatedCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, UPDATED_SEQUENCE);
        davTestHelper.upsertCalendar(organizer, updatedCalendarData, eventUid);

        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());
    }

    @Test
    void shouldSendLocalizedBookingConfirmedEmailAccordingToRecipientLanguage() {
        when(settingsResolver.resolveOrDefault(any(Username.class), any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(
                    LANGUAGE_IDENTIFIER, Locale.FRENCH,
                    TIMEZONE_IDENTIFIER, ZoneId.of("Europe/Paris")))));

        String eventUid = UUID.randomUUID().toString();

        String initialCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, INITIAL_SEQUENCE);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);
        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());

        clearSmtpMock();
        String updatedCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE);
        davTestHelper.upsertCalendar(organizer, updatedCalendarData, eventUid);

        awaitAtMostForEmailDelivery
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        String message = smtpMailsResponseSupplier.get().getString("[0].message");

        assertThat(extractDecodedPart(message, "text/html; charset=UTF-8"))
            .contains("Heure")
            .contains("(Organisateur)")
            .contains("(Proposant)");
        assertThat(message)
            .contains("Content-Language: fr")
            .contains("Subject: =?ISO-8859-1?Q?Van_Tung_TRAN_a_accept");
    }

    @Test
    void shouldSendEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedOnPublicAgendaWithExternalAttendee() {
        // Given: a public agenda event with one additional external attendee.
        String externalAttendeeEmail = "external-attendee-" + UUID.randomUUID() + "@external-domain.com";

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, INITIAL_SEQUENCE, externalAttendeeEmail);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);
        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());

        // When: organizer updates participation from NEEDS_ACTION to ACCEPTED.
        clearSmtpMock();
        String updatedCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE, externalAttendeeEmail);
        davTestHelper.upsertCalendar(organizer, updatedCalendarData, eventUid);

        // Then: booking-confirmed email is delivered to external attendee with external-specific subject/body.
        awaitAtMostForEmailDelivery
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();
        int externalMailIndex = smtpMailsResponse.getList("recipients[0].address", String.class).indexOf(externalAttendeeEmail);
        String message = smtpMailsResponse.getString("[" + externalMailIndex + "].message");

        assertThat(extractDecodedPart(message, "text/html; charset=UTF-8"))
            .contains("has accepted Bob event proposal")
            .contains("Bob")
            .contains("(Proposer)");
        assertThat(message)
            .contains("Subject: Van Tung TRAN has accepted Bob event proposal")
            .contains("Content-Type: text/html; charset=UTF-8")
            .doesNotContain("Public agenda event notification.");
        assertThat(smtpMailsResponse.getString("[" + externalMailIndex + "].from")).isEqualTo(organizer.username().asString());
        assertThat(smtpMailsResponse.getString("[" + externalMailIndex + "].recipients[0].address")).isEqualTo(externalAttendeeEmail);
    }

    @Test
    void shouldSendEmailToCreatorAndAdditionalAttendeeWhenOrganizerAcceptsPublicAgenda() {
        // Given: a public agenda event where both creator and additional attendee should be notified.
        String additionalAttendeeEmail = "additional-attendee-" + UUID.randomUUID() + "@external-domain.com";

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.NEEDS_ACTION, INITIAL_SEQUENCE, additionalAttendeeEmail);
        davTestHelper.upsertCalendar(organizer, initialCalendarData, eventUid);
        calmlyAwaitDuringNoEmail
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());

        // When: organizer accepts the booking.
        clearSmtpMock();
        String updatedCalendarData = generatePublicAgendaCalendar(eventUid, organizer.username().asString(),
            attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE, additionalAttendeeEmail);
        davTestHelper.upsertCalendar(organizer, updatedCalendarData, eventUid);

        // Then: both recipients receive their own booking-confirmed subject.
        awaitAtMostForEmailDelivery
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();
        Map<String, String> recipientToMessage = new HashMap<>();
        recipientToMessage.put(smtpMailsResponse.getString("[0].recipients[0].address"), smtpMailsResponse.getString("[0].message"));
        recipientToMessage.put(smtpMailsResponse.getString("[1].recipients[0].address"), smtpMailsResponse.getString("[1].message"));

        assertThat(recipientToMessage.keySet())
            .containsExactlyInAnyOrder(attendee.username().asString(), additionalAttendeeEmail);

        assertThat(recipientToMessage.get(attendee.username().asString()))
            .contains("Subject: Van Tung TRAN has accepted your event proposal");
        assertThat(recipientToMessage.get(additionalAttendeeEmail))
            .contains("Subject: Van Tung TRAN has accepted Bob event proposal");
    }

    @Test
    void shouldRecoverWhenProposerIsNotPresentInAttendees() throws Exception {
        publishNotificationEmail(attendee.username().asString(), generatePublicAgendaCalendarWithCustomCreator(UUID.randomUUID().toString(),
            organizer.username().asString(), attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE,
            "unknown-proposer@" + UUID.randomUUID() + ".com", null));

        publishNotificationEmail(attendee.username().asString(), generatePublicAgendaCalendar(UUID.randomUUID().toString(),
            organizer.username().asString(), attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE));

        awaitAtMostForEmailDelivery
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        assertThat(smtpMailsResponseSupplier.get().getString("[0].message"))
            .contains("Subject: Van Tung TRAN has accepted your event proposal");
    }

    @Test
    void shouldRecoverAfterInvalidPubliclyCreatorEmail() throws Exception {
        publishNotificationEmail(attendee.username().asString(), generatePublicAgendaCalendarWithCustomCreator(UUID.randomUUID().toString(),
            organizer.username().asString(), attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE,
            "not-an-email", null));

        publishNotificationEmail(attendee.username().asString(), generatePublicAgendaCalendar(UUID.randomUUID().toString(),
            organizer.username().asString(), attendee.username().asString(), PartStat.ACCEPTED, UPDATED_SEQUENCE));

        awaitAtMostForEmailDelivery
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        assertThat(smtpMailsResponseSupplier.get().getString("[0].message"))
            .contains("Subject: Van Tung TRAN has accepted your event proposal");
    }

    private void publishNotificationEmail(String recipientEmail, String calendarData) throws Exception {
        String payload = """
            {
              "senderEmail": "%s",
              "recipientEmail": "%s",
              "method": "REQUEST",
              "event": %s,
              "notify": true,
              "calendarURI": "calendar-uri",
              "eventPath": "/calendars/%s/events/%s.ics",
              "changes": {
                "summary": {
                  "previous": "Previous summary",
                  "current": "Publicly created meeting"
                }
              },
              "isNewEvent": false
            }
            """.formatted(
            organizer.username().asString(),
            recipientEmail,
            OBJECT_MAPPER.writeValueAsString(calendarData),
            recipientEmail,
            UUID.randomUUID());

        sender.send(Mono.just(new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, "",
            payload.getBytes(StandardCharsets.UTF_8))))
            .block();
    }

    private String extractDecodedPart(String mimeMessage, String contentType) {
        Pattern pattern = Pattern.compile("Content-Type: " + Pattern.quote(contentType) + ".*?\\R\\R([A-Za-z0-9+/=\\r\\n]+?)\\R--", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(mimeMessage);
        assertThat(matcher.find()).isTrue();
        String encoded = matcher.group(1).replaceAll("\\R", "");
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static final Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
        .get("/smtpMails")
        .jsonPath();

    private String generatePublicAgendaCalendar(String eventUid, String organizerEmail, String requesterEmail,
                                                PartStat organizerPartStat, int sequence) {
        return generatePublicAgendaCalendar(eventUid, organizerEmail, requesterEmail, organizerPartStat, sequence, null);
    }

    private String generatePublicAgendaCalendar(String eventUid, String organizerEmail, String requesterEmail,
                                                PartStat organizerPartStat, int sequence,
                                                String additionalAttendeeEmail) {
        return generatePublicAgendaCalendarWithCustomCreator(eventUid, organizerEmail, requesterEmail,
            organizerPartStat, sequence, requesterEmail, additionalAttendeeEmail);
    }

    private String generatePublicAgendaCalendarWithCustomCreator(String eventUid, String organizerEmail, String requesterEmail,
                                                                 PartStat organizerPartStat, int sequence,
                                                                 String creatorEmail, String additionalAttendeeEmail) {
        String additionalAttendee = Optional.ofNullable(additionalAttendeeEmail)
            .map(email -> "\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Additional attendee:mailto:" + email)
            .orElse("");
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
            DTSTAMP:30250411T022032Z
            SEQUENCE:{sequence}
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting
            DESCRIPTION:This is a publicly created meeting. Visio: https://meet.example.com/abc-xyzx-dkm
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={organizerPartStat};RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;CN=Bob:mailto:{requesterEmail}
            {xPubliclyCreated}
            {xPubliclyCreator}{additionalAttendee}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{requesterEmail}", requesterEmail)
            .replace("{organizerPartStat}", organizerPartStat.getValue())
            .replace("{sequence}", Integer.toString(sequence))
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER)
            .replace("{xPubliclyCreator}", X_PUBLICLY_CREATOR_HEADER.formatted(creatorEmail))
            .replace("{additionalAttendee}", additionalAttendee);
    }
}
