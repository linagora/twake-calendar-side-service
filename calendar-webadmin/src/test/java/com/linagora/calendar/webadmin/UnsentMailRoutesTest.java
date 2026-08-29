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


package com.linagora.calendar.webadmin;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.SmtpSendingFailedException;
import com.linagora.calendar.storage.unsent.MemoryUnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.SendingTrial;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailId;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;
import com.linagora.calendar.webadmin.service.UnsentMailResendService;
import com.linagora.calendar.webadmin.task.UnsentMailResendTaskAdditionalInformationDTO;

import io.restassured.RestAssured;
import reactor.core.publisher.Mono;

class UnsentMailRoutesTest {
    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    private static final byte[] MIME_MESSAGE = """
        From: sender@linagora.com\r
        To: recipient@linagora.com\r
        Subject: Hi\r
        \r
        Hello!""".getBytes(StandardCharsets.UTF_8);

    private static class RecordingMailSender implements MailSender, MailSender.Factory {
        private final List<Mail> sent = new CopyOnWriteArrayList<>();
        private volatile boolean failing = false;

        @Override
        public Mono<MailSender> create() {
            return Mono.just(this);
        }

        @Override
        public Mono<Void> send(Mail mail) {
            if (failing) {
                return Mono.error(new SmtpSendingFailedException("Connection refused"));
            }
            sent.add(mail);
            return Mono.empty();
        }
    }

    private WebAdminServer webAdminServer;
    private MemoryUnsentMailRepository repository;
    private RecordingMailSender mailSender;
    private UpdatableTickingClock clock;

    private static MailAddress mailAddress(String value) throws AddressException {
        return new MailAddress(value);
    }

    @BeforeEach
    void setUp() {
        clock = new UpdatableTickingClock(NOW);
        repository = new MemoryUnsentMailRepository(clock);
        mailSender = new RecordingMailSender();
        UnsentMailResendService resendService = new UnsentMailResendService(repository, mailSender, clock);

        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(
                new UnsentMailRoutes(repository, resendService, taskManager, new JsonTransformer()),
                new TasksRoutes(taskManager, new JsonTransformer(),
                    new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                        .add(UnsentMailResendTaskAdditionalInformationDTO.module())
                        .build())))
            .start();

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    private UnsentMailId store(String sender, String recipient) throws AddressException {
        return repository.store(Optional.of(mailAddress(sender)), ImmutableList.of(mailAddress(recipient)),
            MIME_MESSAGE, new SendingTrial(NOW, "Connection refused")).block();
    }

    @Test
    void listShouldReturnEmptyWhenNoUnsentMail() {
        String response = when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThatJson(response).isEqualTo("[]");
    }

    @Test
    void listShouldReturnUnsentMailIds() throws Exception {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        String response = when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThatJson(response).isEqualTo("""
            [{"id": "%s"}]""".formatted(id.value()));
    }

    @Test
    void listShouldFilterBySender() throws Exception {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");
        store("other@linagora.com", "recipient@linagora.com");

        String response = given()
            .queryParam("sender", "sender@linagora.com")
        .when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThatJson(response).isEqualTo("""
            [{"id": "%s"}]""".formatted(id.value()));
    }

    @Test
    void listShouldFilterByRecipient() throws Exception {
        store("sender@linagora.com", "recipient@linagora.com");
        UnsentMailId id = store("sender@linagora.com", "other@linagora.com");

        String response = given()
            .queryParam("recipient", "other@linagora.com")
        .when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThatJson(response).isEqualTo("""
            [{"id": "%s"}]""".formatted(id.value()));
    }

    @Test
    void listShouldRejectInvalidSender() {
        given()
            .queryParam("sender", "bad")
        .when()
            .get("/unsentMails")
        .then()
            .statusCode(400);
    }

    @Test
    void getShouldReturnUnsentMailDetails() throws Exception {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        String response = when()
            .get("/unsentMails/" + id.value())
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThatJson(response).isEqualTo("""
            {
              "id": "%s",
              "mailFrom": "sender@linagora.com",
              "rcptTo": ["recipient@linagora.com"],
              "body": "%s",
              "createdAt": "2026-08-27T10:00:00Z",
              "sendingTrials": [
                {"date": "2026-08-27T10:00:00Z", "errorMessage": "Connection refused"}
              ]
            }""".formatted(id.value(), new String(MIME_MESSAGE, StandardCharsets.UTF_8)
                .replace("\r\n", "\\r\\n")));
    }

    @Test
    void getShouldReturnNotFoundWhenNoSuchMail() {
        when()
            .get("/unsentMails/" + UnsentMailId.generate().value())
        .then()
            .statusCode(404);
    }

    @Test
    void deleteShouldRemoveTheUnsentMail() throws Exception {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        when()
            .delete("/unsentMails/" + id.value())
        .then()
            .statusCode(204);

        assertThat(repository.read(id).blockOptional()).isEmpty();
    }

    @Test
    void deleteAllShouldRemoveEveryUnsentMail() throws Exception {
        store("sender@linagora.com", "recipient@linagora.com");
        store("other@linagora.com", "recipient@linagora.com");

        when()
            .delete("/unsentMails")
        .then()
            .statusCode(204);

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    @Test
    void resendShouldSendAndDropTheStoredMails() throws Exception {
        store("sender@linagora.com", "recipient@linagora.com");

        String taskId = given()
            .queryParam("action", "resend")
        .when()
            .post("/unsentMails")
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.sentCount", is(1))
            .body("additionalInformation.failedCount", is(0));

        assertThat(mailSender.sent).hasSize(1);
        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    @Test
    void resendShouldApplyLimit() throws Exception {
        store("sender@linagora.com", "recipient@linagora.com");
        clock.setInstant(NOW.plusSeconds(3600));
        store("sender@linagora.com", "recipient@linagora.com");

        String taskId = given()
            .queryParam("action", "resend")
            .queryParam("limit", 1)
        .when()
            .post("/unsentMails")
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.sentCount", is(1));

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).hasSize(1);
    }

    @Test
    void resendShouldRetainTheMailAndRecordTheTrialUponFailure() throws Exception {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");
        mailSender.failing = true;
        clock.setInstant(NOW.plusSeconds(3600));

        String taskId = given()
            .queryParam("action", "resend")
        .when()
            .post("/unsentMails")
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("failed"))
            .body("additionalInformation.sentCount", is(0))
            .body("additionalInformation.failedCount", is(1));

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block())
            .describedAs("The failed resend records a trial onto the very same entry, it does not store a duplicate")
            .containsExactly(id);
        assertThat(repository.read(id).block().sendingTrials()).hasSize(2);
    }

    @Test
    void resendOneShouldOnlySendThatMail() throws Exception {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");
        store("other@linagora.com", "recipient@linagora.com");

        String taskId = given()
            .queryParam("action", "resend")
        .when()
            .post("/unsentMails/" + id.value())
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("additionalInformation.sentCount", is(1))
            .body("additionalInformation.unsentMailId", is(id.value()));

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).hasSize(1);
    }

    @Test
    void resendOneShouldFailWhenNoSuchMail() {
        String taskId = given()
            .queryParam("action", "resend")
        .when()
            .post("/unsentMails/" + UnsentMailId.generate().value())
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("failed"))
            .body("additionalInformation.failedCount", is(1));
    }

    @Test
    void postShouldRejectUnknownAction() {
        given()
            .queryParam("action", "unknown")
        .when()
            .post("/unsentMails")
        .then()
            .statusCode(400);
    }
}
