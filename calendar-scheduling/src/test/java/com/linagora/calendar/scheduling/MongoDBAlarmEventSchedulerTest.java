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

import static com.linagora.calendar.scheduling.AlarmEventSchedulerConfiguration.BATCH_SIZE_DEFAULT;
import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.util.Port;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.AlarmEventLeaseProvider;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.mongodb.DockerMongoDBExtension;
import com.linagora.calendar.storage.mongodb.MongoAlarmEventLeaseProvider;
import com.linagora.calendar.storage.mongodb.MongoDBAlarmEventDAO;
import com.linagora.calendar.storage.mongodb.MongoDBAlarmEventLedgerDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

public class MongoDBAlarmEventSchedulerTest implements AlarmEventSchedulerContract {

    @RegisterExtension
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();
    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension();

    private AlarmEventScheduler scheduler;
    private UpdatableTickingClock clock;
    private MongoDBAlarmEventDAO alarmEventDAO;
    private RequestSpecification requestSpecification;

    @BeforeEach
    void setup() {
        clock = new UpdatableTickingClock(Instant.now());
        alarmEventDAO = new MongoDBAlarmEventDAO(mongo.getDb());
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
        SettingsBasedResolver settingsResolver = Mockito.mock(SettingsBasedResolver.class);
        when(settingsResolver.resolveOrDefault(any(Username.class)))
            .thenReturn(Mono.just(SettingsBasedResolver.ResolvedSettings.DEFAULT));
        FileSystemImpl fileSystem = FileSystemImpl.forTesting();
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates");
        MailTemplateConfiguration mailTemplateConfig = new MailTemplateConfiguration("file://" + templateDirectory.toAbsolutePath(),
            MaybeSender.getMailSender("no-reply@openpaas.org"));

        MongoDatabase mongoDB = mongo.getDb();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);

        MessageGenerator.Factory messageGeneratorFactory = MessageGenerator.factory(mailTemplateConfig, fileSystem, openPaaSUserDAO);

        AlarmTriggerService alarmTriggerService = new AlarmTriggerService(alarmEventDAO, clock,
            mailSenderFactory,
            settingsResolver,
            messageGeneratorFactory,
            new AlarmInstantFactory.Default(clock),
            mailTemplateConfig);

        AlarmEventSchedulerConfiguration alarmEventSchedulerConfiguration = new AlarmEventSchedulerConfiguration(
            Duration.ofSeconds(1),
            BATCH_SIZE_DEFAULT,
            Duration.ofMillis(100),
            AlarmEventSchedulerConfiguration.Mode.CLUSTER);

        AlarmEventLeaseProvider alarmEventLeaseProvider = new MongoAlarmEventLeaseProvider(
            new MongoDBAlarmEventLedgerDAO(mongo.getDb(), clock));
        scheduler = new AlarmEventScheduler(clock,
            alarmEventDAO,
            alarmEventLeaseProvider,
            alarmTriggerService,
            alarmEventSchedulerConfiguration,
            new RecordingMetricFactory());

        requestSpecification = new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    @AfterEach
    void teardown() {
        scheduler.close();
        given(requestSpecification).delete("/smtpMails").then();
        given(requestSpecification).delete("/smtpBehaviors").then();
    }

    @Override
    public AlarmEventScheduler scheduler() {
        return scheduler;
    }

    @Override
    public UpdatableTickingClock clock() {
        return clock;
    }

    @Override
    public AlarmEventDAO alarmEventDAO() {
        return alarmEventDAO;
    }

    @Override
    public JsonPath getSmtpMailbox() {
        return given(requestSpecification).get("/smtpMails").jsonPath();
    }
}
