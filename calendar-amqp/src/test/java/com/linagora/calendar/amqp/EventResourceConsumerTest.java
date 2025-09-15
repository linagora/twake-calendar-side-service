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

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.linagora.calendar.storage.configuration.EntryIdentifier.LANGUAGE_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader.TIMEZONE_IDENTIFIER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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
import com.linagora.calendar.api.JwtSigner;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
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
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class EventResourceConsumerTest {

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(20, TimeUnit.SECONDS);

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

    private ResourceDAO resourceDAO;
    private CalDavEventRepository calDavEventRepository;

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
    private OpenPaaSUser resourceAdmin;
    private Sender sender;

    @BeforeEach
    public void setUp() throws Exception {
        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();
        resourceAdmin = sabreDavExtension.newTestUser();

        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(new SettingsBasedResolver.ResolvedSettings(
                Map.of(
                    LANGUAGE_IDENTIFIER, Locale.ENGLISH,
                    TIMEZONE_IDENTIFIER, ZoneId.of("Asia/Ho_Chi_Minh")))));
        setupEventResourceConsumer();
        clearSmtpMock();

        CalDavClient calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        calDavEventRepository = new CalDavEventRepository(calDavClient);
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

    private void setupEventResourceConsumer() throws MalformedURLException {
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
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.systemUTC());

        MessageGenerator.Factory messageFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem, openPaaSUserDAO);

        JwtSigner jwtSigner = mock(JwtSigner.class);
        when(jwtSigner.generate(anyMap()))
            .thenReturn(Mono.just("jwtSecret"));

        EventResourceHandler eventResourceHandler = new EventResourceHandler(resourceDAO,
            mailSenderFactory,
            messageFactory,
            openPaaSUserDAO,
            settingsResolver,
            eventEmailFilter,
            mailTemplateConfig,
            URI.create("https://calendar.linagora.local").toURL(),
            jwtSigner);

        EventResourceConsumer consumer = new EventResourceConsumer(channelPool, QueueArguments.Builder::new, eventResourceHandler);
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
    void shouldSendResourceRequestEmailWhenNewEventIsCreated(DockerSabreDavSetup dockerSabreDavSetup) {
        OpenPaaSDomain domain = dockerSabreDavSetup.getOpenPaaSProvisioningService().getDomain().block();
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(resourceAdmin.id(), "user")),
            resourceAdmin.id(),
            "Test resource description",
            domain.id(),
            "icon.png",
            "Projector");
        ResourceId resourceId = resourceDAO.insert(request).block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resourceId.value());
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        String resourceEventId = awaitAtMost.until(() -> davTestHelper.findFirstEventId(resourceId, domain.id()), Optional::isPresent).get();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(resourceAdmin.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: A user booked the resource Projector")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);

            String html = getHtml(smtpMailsResponse.getString("[0].message"));

            assertThat(html).contains("Van Tung TRAN")
                .contains("has requested to book the resource")
                .contains("Projector")
                .contains("Monday, 11 April 3025 10:00 - 11:00")
                .contains("Asia/Ho_Chi_Minh")
                .contains("Twake Meeting Room")
                .contains(organizer.username().asString())
                .contains(attendee.username().asString())
                .contains("Projector")
                .contains("This is a meeting to discuss the sprint planning for the next week.")
                .contains("https://calendar.linagora.local/calendar/api/resources/" + resourceId.value() + "/" + resourceEventId + "/participation?status=ACCEPTED&amp;referrer=email&amp;jwt=jwtSecret")
                .contains("https://calendar.linagora.local/calendar/api/resources/" + resourceId.value() + "/" + resourceEventId + "/participation?status=DECLINED&amp;referrer=email&amp;jwt=jwtSecret");
        }));
    }

    @Test
    void shouldNotSendResourceRequestEmailWhenNoAdmin(DockerSabreDavSetup dockerSabreDavSetup) throws InterruptedException {
        OpenPaaSDomain domain = dockerSabreDavSetup.getOpenPaaSProvisioningService().getDomain().block();
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(),
            resourceAdmin.id(),
            "Test resource description",
            domain.id(),
            "icon.png",
            "Projector");
        ResourceId resourceId = resourceDAO.insert(request).block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resourceId.value());
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        Thread.sleep(10000); // Wait a bit to ensure no email is sent

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(0));
    }

    @Test
    void shouldNotSendResourceRequestEmailWhenResourceHasBeenDeleted(DockerSabreDavSetup dockerSabreDavSetup) throws InterruptedException {
        OpenPaaSDomain domain = dockerSabreDavSetup.getOpenPaaSProvisioningService().getDomain().block();
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(resourceAdmin.id(), "user")),
            resourceAdmin.id(),
            "Test resource description",
            domain.id(),
            "icon.png",
            "Projector");
        ResourceId resourceId = resourceDAO.insert(request).block();
        resourceDAO.softDelete(resourceId).block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resourceId.value());
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        Thread.sleep(5000); // Wait a bit to ensure no email is sent

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(0));
    }

    @Test
    void shouldSendResourceReplyEmailWhenResourceRequestIsAccepted(DockerSabreDavSetup dockerSabreDavSetup) {
        OpenPaaSDomain domain = dockerSabreDavSetup.getOpenPaaSProvisioningService().getDomain().block();
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(resourceAdmin.id(), "user")),
            resourceAdmin.id(),
            "Test resource description",
            domain.id(),
            "icon.png",
            "Projector");
        ResourceId resourceId = resourceDAO.insert(request).block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resourceId.value());
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        String resourceEventId = awaitAtMost.until(() -> davTestHelper.findFirstEventId(resourceId, domain.id()), Optional::isPresent).get();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        calDavEventRepository.updatePartStat(domain, resourceId, resourceEventId, PartStat.ACCEPTED).block();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[1].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[1].recipients[0].address")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].message"))
                .contains("Subject: The reservation of resource Projector has been accepted")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);

            String html = getHtml(smtpMailsResponse.getString("[1].message"));

            assertThat(html).contains("The booking request has been accepted for the resource")
                .contains("Projector")
                .contains("Monday, 11 April 3025 10:00 - 11:00")
                .contains("Asia/Ho_Chi_Minh")
                .contains("Twake Meeting Room")
                .contains(organizer.username().asString())
                .contains(attendee.username().asString())
                .contains("Projector")
                .contains("This is a meeting to discuss the sprint planning for the next week.");
        }));
    }

    @Test
    void shouldSendResourceReplyEmailWhenResourceRequestIsDeclined(DockerSabreDavSetup dockerSabreDavSetup) {
        OpenPaaSDomain domain = dockerSabreDavSetup.getOpenPaaSProvisioningService().getDomain().block();
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(resourceAdmin.id(), "user")),
            resourceAdmin.id(),
            "Test resource description",
            domain.id(),
            "icon.png",
            "Projector");
        ResourceId resourceId = resourceDAO.insert(request).block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            organizer.username().asString(),
            attendee.username().asString(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resourceId.value());
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        String resourceEventId = awaitAtMost.until(() -> davTestHelper.findFirstEventId(resourceId, domain.id()), Optional::isPresent).get();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        calDavEventRepository.updatePartStat(domain, resourceId, resourceEventId, PartStat.DECLINED).block();

        awaitAtMost.untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(2));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[1].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[1].recipients[0].address")).isEqualTo(organizer.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[1].message"))
                .contains("Subject: The reservation of resource Projector has been declined")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);

            String html = getHtml(smtpMailsResponse.getString("[1].message"));

            assertThat(html).contains("The booking request has been declined for the resource")
                .contains("Projector")
                .contains("Monday, 11 April 3025 10:00 - 11:00")
                .contains("Asia/Ho_Chi_Minh")
                .contains("Twake Meeting Room")
                .contains(organizer.username().asString())
                .contains(attendee.username().asString())
                .contains("Projector")
                .contains("This is a meeting to discuss the sprint planning for the next week.");
        }));
    }

    private String getHtml(String rawMessage) {
        Pattern htmlPattern = Pattern.compile(
            "Content-Transfer-Encoding: base64\r?\nContent-Type: text/html; charset=UTF-8\r?\nContent-Language: [^\r\n]+\r?\n\r?\n([A-Za-z0-9+/=\r\n]+)\r?\n---=Part",
            Pattern.DOTALL);
        Matcher matcher = htmlPattern.matcher(rawMessage);
        matcher.find();
        String base64Html = matcher.group(1).replaceAll("\\s+", "");
        return new String(Base64.getDecoder().decode(base64Html), StandardCharsets.UTF_8);
    }

    private static final Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
        .get("/smtpMails")
        .jsonPath();

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String resourceId) {
        return generateCalendarData(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, resourceId, "TENTATIVE");
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String resourceId,
                                        String partStat) {

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
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            LOCATION:{location}
            DESCRIPTION:{description}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=Projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary)
            .replace("{location}", location)
            .replace("{description}", description)
            .replace("{dtstart}", dtstart)
            .replace("{dtend}", dtend)
            .replace("{partStat}", partStat)
            .replace("{resourceId}", resourceId);
    }
}

