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
import static com.linagora.calendar.amqp.TestFixture.extractSubject;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.commons.io.IOUtils;
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
import org.apache.james.util.Port;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.rabbitmq.client.Channel;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

public class CalendarDelegatedNotificationConsumerTest {

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
    private static Channel channel;

    private CalDavClient calDavClient;
    private CalendarDelegatedNotificationConsumer consumer;

    private OpenPaaSUser bob;
    private OpenPaaSUser alice;
    private OpenPaaSDomain domain;

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
        channel = connectionPool.getResilientConnection().block().createChannel();
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    @BeforeEach
    public void setUp() throws Exception {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        bob = sabreDavExtension.newTestUser(Optional.of("Bob"));
        alice = sabreDavExtension.newTestUser(Optional.of("Alice"));
        domain = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB())
            .retrieve(bob.username().getDomainPart().get()).block();

        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(LANGUAGE_IDENTIFIER, Locale.ENGLISH))));
        setupConsumer();
        clearSmtpMock();
    }

    @AfterEach
    void afterEach() {
        if (consumer != null) {
            consumer.close();
        }

        Mockito.reset(settingsResolver);
        Mockito.reset(eventEmailFilter);
    }


    private void setupConsumer() throws Exception {
        MailSenderConfiguration mailSenderConfiguration = new MailSenderConfiguration(
            "localhost", Port.of(mockSmtpExtension.getMockSmtp().getSmtpPort()),
            "localhost", Optional.empty(), Optional.empty(),
            false, false, false);

        MailSender.Factory mailSenderFactory = new MailSender.Factory.Default(mailSenderConfiguration, EventEmailFilter.acceptAll());
        FileSystemImpl fileSystem = FileSystemImpl.forTesting();
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates");

        MailTemplateConfiguration mailTemplateConfig = new MailTemplateConfiguration("file://" + templateDirectory.toAbsolutePath(),
            MaybeSender.getMailSender("no-reply@openpaas.org"));

        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        MessageGenerator.Factory messageFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem, openPaaSUserDAO);

        URL spaCalendarURL = URI.create("https://localhost/").toURL();
        Path logoPath = Paths.get("/Users/tungtv/workplace/twake-calendar-side-service/calendar-rest-api/src/main/resources/assets/calendar/logo.png");
        byte[] calendarLogo;
        try (InputStream is = Files.newInputStream(logoPath)) {
            calendarLogo = IOUtils.toByteArray(is);
        }

        DelegatedCalendarNotificationHandler notificationHandler = new DelegatedCalendarNotificationHandler(
            openPaaSUserDAO, calDavClient, settingsResolver, mailSenderFactory, mailTemplateConfig, messageFactory,
            eventEmailFilter, spaCalendarURL, calendarLogo);

        consumer = new CalendarDelegatedNotificationConsumer(channelPool, QueueArguments.Builder::new, notificationHandler);
        consumer.init();
    }

    private final Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
        .get("/smtpMails")
        .jsonPath();

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
    void shouldSendMailWhenCalendarIsDelegated() {
        String calendarName = "Bob Private1";
        NewCalendar bobCalendar = new NewCalendar(UUID.randomUUID().toString(),
            calendarName, "#123456", "Calendar for testing");
        calDavClient.createNewCalendar(bob.username(), bob.id(), bobCalendar).block();

        calDavClient.patchReadWriteDelegations(domain.id(), new CalendarURL(bob.id(), new OpenPaaSId(bobCalendar.id())),
                List.of(alice.username()), List.of())
            .block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));
        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(alice.username().asString());
            String subject = extractSubject(smtpMailsResponse.getString("[0].message"));
            softly.assertThat(subject)
                .isEqualTo("%s shared the “%s” calendar with you".formatted(bob.fullName(), calendarName));
        }));
    }

    @Test
    void shouldGenerateMailUsingRecipientLocale() {
        String calendarName = "Bob Private1";
        // Override settingsResolver to return Locale.FRENCH for Alice
        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(LANGUAGE_IDENTIFIER, Locale.FRENCH))));

        NewCalendar bobCalendar = new NewCalendar(UUID.randomUUID().toString(),
            calendarName, "#123456", "Calendar for testing");
        calDavClient.createNewCalendar(bob.username(), bob.id(), bobCalendar).block();

        calDavClient.patchReadWriteDelegations(domain.id(), new CalendarURL(bob.id(), new OpenPaaSId(bobCalendar.id())),
                List.of(alice.username()), List.of())
            .block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));
        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(alice.username().asString());
            String subject = extractSubject(smtpMailsResponse.getString("[0].message"));
            // Expected French subject: "<Bob full name> a partagé le calendrier « <calendar name> » avec vous"
            String expectedSubject = "%s a partagé le calendrier « %s » avec vous".formatted(bob.fullName(), calendarName);
            softly.assertThat(subject)
                .isEqualTo(expectedSubject);
        }));
    }


    @Test
    void shouldNotCrashWhenReceivingInvalidAMQPMessage() throws Exception {
        // Step 1: Publish an invalid AMQP message payload directly to the queue
        String queueName = "tcalendar:calendar:created";
        byte[] invalidBody = "not a json".getBytes();
        channel.basicPublish("", queueName, null, invalidBody);

        // Step 2: Publish a valid delegated calendar creation flow: Bob creates a calendar and delegates to Alice
        String calendarName = "Bob Private InvalidTest";
        NewCalendar bobCalendar = new NewCalendar(UUID.randomUUID().toString(),
            calendarName, "#654321", "Calendar for invalid message test");
        calDavClient.createNewCalendar(bob.username(), bob.id(), bobCalendar).block();

        calDavClient.patchReadWriteDelegations(domain.id(), new CalendarURL(bob.id(), new OpenPaaSId(bobCalendar.id())),
                List.of(alice.username()), List.of())
            .block();

        // Step 3: Await until exactly ONE mail is received
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));
        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        // Step 4: Assert: One mail exists, recipient is Alice
        assertThat(smtpMailsResponse.getList("")).hasSize(1);
        assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(alice.username().asString());
    }

    @Test
    void shouldNotSendMailWhenEventEmailFilterRejects() throws InterruptedException {
        when(eventEmailFilter.shouldProcess(any())).thenReturn(false);

        String calendarName = "Bob Private FilterTest";
        NewCalendar bobCalendar = new NewCalendar(UUID.randomUUID().toString(), calendarName,
            "#abcdef", "Calendar for event email filter test");

        // Bob creates a calendar
        calDavClient.createNewCalendar(bob.username(), bob.id(), bobCalendar).block();

        // Bob delegates the calendar to Alice
        calDavClient.patchReadWriteDelegations(domain.id(), new CalendarURL(bob.id(), new OpenPaaSId(bobCalendar.id())),
            List.of(alice.username()), List.of()).block();

        Thread.sleep(1000);
        // Then: no mail should be sent
        awaitAtMost.untilAsserted(() ->
            assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());
    }

    @Test
    void shouldNotSendMailWhenSelfSubscribedToSharedCalendar() throws InterruptedException {
        // Given: Bob shares calendar with read access
        davTestHelper.updateCalendarAcl(bob, CalendarURL.from(bob.id()).asUri(), "{DAV:}read");

        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id().value())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        // When: Alice subscribes to the shared calendar herself
        davTestHelper.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        Thread.sleep(1000);
        // Then: No notification email is sent
        awaitAtMost.untilAsserted(() ->
            assertThat(smtpMailsResponseSupplier.get().getList("")).isEmpty());
    }
}
