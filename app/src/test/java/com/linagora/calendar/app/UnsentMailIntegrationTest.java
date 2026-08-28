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


package com.linagora.calendar.app;

import static com.linagora.calendar.app.restapi.routes.ImportRouteTest.mailSenderConfigurationFunction;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static io.restassured.RestAssured.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

public class UnsentMailIntegrationTest {

    static class MailSenderProbe implements GuiceProbe {
        private final MailSender.Factory mailSenderFactory;

        @Inject
        MailSenderProbe(MailSender.Factory mailSenderFactory) {
            this.mailSenderFactory = mailSenderFactory;
        }

        void send(String sender, String recipient, String subject) throws Exception {
            String rawMessage = "From: %s\r\nTo: %s\r\nSubject: %s\r\n\r\nHello!".formatted(sender, recipient, subject);
            Message message = new DefaultMessageBuilder()
                .parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));

            mailSenderFactory.send(new Mail(MaybeSender.of(new MailAddress(sender)),
                    ImmutableList.of(new MailAddress(recipient)), message))
                .onErrorResume(error -> Mono.empty())
                .block();
        }
    }

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            binder.bind(MailSenderConfiguration.class)
                .toInstance(mailSenderConfigurationFunction.apply(mockSmtpExtension));
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(MailSenderProbe.class);
        });

    private static final String SENDER = "sender@linagora.com";
    private static final String RECIPIENT = "recipient@linagora.com";

    static ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private MailSenderProbe mailSenderProbe;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setPort(server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort().getValue())
            .setBasePath("")
            .build();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        mailSenderProbe = server.getProbe(MailSenderProbe.class);

        given(mockSMTPRequestSpecification()).delete("/smtpMails");
        given(mockSMTPRequestSpecification()).delete("/smtpBehaviors");
        with().delete("/unsentMails");
    }

    private void rejectRecipient(String recipient) {
        given(mockSMTPRequestSpecification())
            .body("""
                [ { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "%s" }, "response": { "code": "501", "message": "Bad recipient" } } ]
                """.formatted(recipient))
            .contentType("application/json")
            .put("/smtpBehaviors");
    }

    private void acceptEveryRecipient() {
        given(mockSMTPRequestSpecification()).delete("/smtpBehaviors");
    }

    private JsonPath deliveredMails() {
        return given(mockSMTPRequestSpecification()).get("/smtpMails").jsonPath();
    }

    private String resendAll() {
        return with()
            .queryParam("action", "resend")
            .post("/unsentMails")
            .jsonPath()
            .get("taskId");
    }

    @Test
    void shouldNotRetainDeliveredMails() throws Exception {
        mailSenderProbe.send(SENDER, RECIPIENT, "Delivered");

        CALMLY_AWAIT.untilAsserted(() -> assertThat(deliveredMails().getList("")).hasSize(1));

        when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .body("", hasSize(0));
    }

    @Test
    void shouldRetainMailWhenSmtpDeliveryFails() throws Exception {
        rejectRecipient(RECIPIENT);

        mailSenderProbe.send(SENDER, RECIPIENT, "Retained");

        String id = when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .body("", hasSize(1))
            .extract()
            .jsonPath()
            .get("[0].id");

        when()
            .get("/unsentMails/" + id)
        .then()
            .statusCode(200)
            .body("id", is(id))
            .body("mailFrom", is(SENDER))
            .body("rcptTo", is(ImmutableList.of(RECIPIENT)))
            .body("body", containsString("Subject: Retained"))
            .body("sendingTrials", hasSize(1))
            .body("sendingTrials[0].errorMessage", containsString("All 'rcpt to' commands failed"));

        assertThat(deliveredMails().getList("")).isEmpty();

        when()
            .get("/healthcheck/checks/UnsentMails")
        .then()
            .body("status", is("degraded"))
            .body("cause", containsString("1 mail(s) could not be delivered"));
    }

    @Test
    void resendShouldDeliverTheRetainedMail() throws Exception {
        rejectRecipient(RECIPIENT);
        mailSenderProbe.send(SENDER, RECIPIENT, "Resent");
        acceptEveryRecipient();

        String taskId = resendAll();

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("completed"))
            .body("type", is("resend-unsent-mails"))
            .body("additionalInformation.sentCount", is(1))
            .body("additionalInformation.failedCount", is(0));

        CALMLY_AWAIT.untilAsserted(() -> {
            JsonPath delivered = deliveredMails();
            assertThat(delivered.getList("")).hasSize(1);
            assertThat(delivered.getString("[0].from")).isEqualTo(SENDER);
            assertThat(delivered.getString("[0].recipients[0].address")).isEqualTo(RECIPIENT);
            assertThat(delivered.getString("[0].message")).contains("Subject: Resent");
        });

        when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .body("", hasSize(0));
    }

    @Test
    void resendShouldRecordAnExtraTrialWhenItFailsAnew() throws Exception {
        rejectRecipient(RECIPIENT);
        mailSenderProbe.send(SENDER, RECIPIENT, "Failing anew");

        String id = when().get("/unsentMails")
            .jsonPath()
            .get("[0].id");

        String taskId = resendAll();

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .statusCode(200)
            .body("status", is("failed"))
            .body("additionalInformation.sentCount", is(0))
            .body("additionalInformation.failedCount", is(1));

        when()
            .get("/unsentMails")
        .then()
            .statusCode(200)
            .body("", hasSize(1))
            .body("[0].id", is(id));

        when()
            .get("/unsentMails/" + id)
        .then()
            .statusCode(200)
            .body("sendingTrials", hasSize(2));
    }
}
