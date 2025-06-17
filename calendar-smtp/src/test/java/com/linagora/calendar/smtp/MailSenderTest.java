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

package com.linagora.calendar.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.util.Port;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

class MailSenderTest {

    @RegisterExtension
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    private MailSender mailSender;

    @BeforeEach
    void setUp() {
        MailSenderConfiguration config = new MailSenderConfiguration(
            "localhost",
            Port.of(mockSmtpExtension.getMockSmtp().getSmtpPort()), // Mock SMTP server's port
            "localhost",
            Optional.empty(),
            Optional.empty(),
            false,
            false,
            false
        );
        mailSender = new MailSender.Factory.Default(config).create().block();

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = mockSmtpExtension.getMockSmtp().getRestApiPort(); // Mock SMTP server's REST API port

        // Clean up mails and mocks before test
        RestAssured.delete("/smtpMails");
        RestAssured.delete("/smtpBehaviors");
    }

    @Test
    void shouldDeliverMail() throws Exception {
        String rawMessage = "From: sender@localhost\nTo: recipient@localhost\nSubject: Test\n\nHello World!";
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(
            MaybeSender.of(new MailAddress("sender@localhost")),
            Collections.singletonList(new MailAddress("recipient@localhost")),
            message
        );

        mailSender.send(mail).block();
        JsonPath response = RestAssured.get("/smtpMails").jsonPath();

        assertSoftly(Throwing.consumer(softly -> {
            assertThat(response.getList("")).hasSize(1);
            assertThat(response.getString("[0].from")).isEqualTo("sender@localhost");
            assertThat(response.getString("[0].recipients[0].address")).isEqualTo("recipient@localhost");
            assertThat(response.getString("[0].message")).containsIgnoringNewLines(rawMessage);
        }));
    }

    @Test
    void shouldDeliverMailToMultipleRecipients() throws Exception {
        RestAssured.delete("/smtpMails");
        String rawMessage = "From: sender@localhost\nTo: recipient1@localhost, recipient2@localhost\nSubject: Test\n\nHello All!";
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(
            MaybeSender.of(new MailAddress("sender@localhost")),
            ImmutableList.of(new MailAddress("recipient1@localhost"), new MailAddress("recipient2@localhost")),
            message
        );

        mailSender.send(mail).block();
        JsonPath response = RestAssured.get("/smtpMails").jsonPath();

        assertSoftly(Throwing.consumer(softly -> {
            assertThat(response.getList("")).hasSize(1);
            assertThat(response.getList("[0].recipients.address")).containsExactlyInAnyOrder("recipient1@localhost", "recipient2@localhost");
        }));
    }

    @Test
    void shouldThrowWhenAllRecipientsRejected() throws Exception {
        String rawMessage = "From: sender@localhost\nTo: rejected@rejected.com\nSubject: Test\n\nShould not deliver!";
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(
            MaybeSender.of(new MailAddress("sender@localhost")),
            ImmutableList.of(new MailAddress("rejected@rejected.com")),
            message
        );

        String behaviorJson = """
            [ { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "@rejected.com" }, "response": { "code": "501", "message": "5.1.3 Bad recipient address syntax" } } ]
            """;
        RestAssured.given().body(behaviorJson).contentType("application/json").put("/smtpBehaviors");

        assertThatThrownBy(() -> mailSender.send(mail).block())
            .isInstanceOf(SmtpSendingFailedException.class)
            .hasMessageContaining("All 'rcpt to' commands failed");
    }

    @Test
    void shouldThrowWhenMailFromCommandFail() throws Exception {
        String rawMessage = "From: sender@localhost\nTo: recipient@localhost\nSubject: Test\n\nHello World!";
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(
            MaybeSender.of(new MailAddress("sender@localhost")),
            ImmutableList.of(new MailAddress("recipient@localhost")),
            message
        );

        String behaviorJson = """
            [ { "command": "MAIL FROM", "condition": { "operator": "contains", "matchingValue": "sender@localhost" }, "response": { "code": "501", "message": "MAIL FROM failed" } } ]
            """;
        RestAssured.given().body(behaviorJson).contentType("application/json").put("/smtpBehaviors");

        assertThatThrownBy(() -> mailSender.send(mail).block())
            .isInstanceOf(SmtpSendingFailedException.class)
            .hasMessageContaining("'mail from' failed");
    }

    @Test
    void shouldThrowWhenDataCommandFail() throws Exception {
        String rawMessage = "From: sender@localhost\nTo: recipient@localhost\nSubject: Test\n\nHello World!";
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(
            MaybeSender.of(new MailAddress("sender@localhost")),
            ImmutableList.of(new MailAddress("recipient@localhost")),
            message
        );

        String behaviorJson = """
            [ { "command": "DATA", "condition": { "operator": "contains", "matchingValue": "" }, "response": { "code": "554", "message": "DATA failed" } } ]
            """;
        RestAssured.given().body(behaviorJson).contentType("application/json").put("/smtpBehaviors");

        assertThatThrownBy(() -> mailSender.send(mail).block())
            .isInstanceOf(SmtpSendingFailedException.class)
            .hasMessageContaining("'data' command failed");
    }

    @Test
    void shouldDeliverMultipleMailsInBatch() throws Exception {
        String rawMessage1 = "From: sender1@localhost\nTo: recipient1@localhost\nSubject: Test1\n\nHello 1!";
        String rawMessage2 = "From: sender2@localhost\nTo: recipient2@localhost\nSubject: Test2\n\nHello 2!";
        Message message1 = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage1.getBytes(StandardCharsets.UTF_8)));
        Message message2 = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage2.getBytes(StandardCharsets.UTF_8)));
        Mail mail1 = new Mail(MaybeSender.of(new MailAddress("sender1@localhost")), ImmutableList.of(new MailAddress("recipient1@localhost")), message1);
        Mail mail2 = new Mail(MaybeSender.of(new MailAddress("sender2@localhost")), ImmutableList.of(new MailAddress("recipient2@localhost")), message2);

        mailSender.send(java.util.List.of(mail1, mail2)).block();
        JsonPath response = RestAssured.get("/smtpMails").jsonPath();

        assertSoftly(Throwing.consumer(softly -> {
            assertThat(response.getList("")).hasSize(2);
            assertThat(response.getString("[0].from")).isEqualTo("sender1@localhost");
            assertThat(response.getString("[0].recipients[0].address")).isEqualTo("recipient1@localhost");
            assertThat(response.getString("[0].message")).containsIgnoringNewLines(rawMessage1);
            assertThat(response.getString("[1].from")).isEqualTo("sender2@localhost");
            assertThat(response.getString("[1].recipients[0].address")).isEqualTo("recipient2@localhost");
            assertThat(response.getString("[1].message")).containsIgnoringNewLines(rawMessage2);
        }));
    }

    @Test
    void shouldThrowWhenEmptyRecipientList() throws Exception {
        String rawMessage = "From: sender@localhost\nSubject: Test\n\nNo recipients!";
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(
            MaybeSender.of(new MailAddress("sender@localhost")),
            ImmutableList.of(),
            message
        );

        assertThatThrownBy(() -> mailSender.send(mail).block())
            .isInstanceOf(SmtpSendingFailedException.class)
            .hasMessageContaining("All 'rcpt to' commands failed");
    }

    @Test
    void shouldNotThrowWhenSendMultipleMailsButOneFails() throws Exception {
        String rawMessage1 = "From: sender1@localhost\nTo: recipient1@localhost\nSubject: Test1\n\nHello 1!";
        String rawMessage2 = "From: sender2@localhost\nTo: recipient2@localhost\nSubject: Test2\n\nHello 2!";
        Message message1 = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage1.getBytes(StandardCharsets.UTF_8)));
        Message message2 = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage2.getBytes(StandardCharsets.UTF_8)));
        Mail mail1 = new Mail(MaybeSender.of(new MailAddress("sender1@localhost")), ImmutableList.of(new MailAddress("recipient1@localhost")), message1);
        Mail mail2 = new Mail(MaybeSender.of(new MailAddress("sender2@localhost")), ImmutableList.of(new MailAddress("recipient2@localhost")), message2);

        // Set up a behavior to reject recipient2
        String behaviorJson = """
            [ { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "recipient2@localhost" }, "response": { "code": "501", "message": "Bad recipient" } } ]
            """;
        RestAssured.given().body(behaviorJson).contentType("application/json").put("/smtpBehaviors");

        // Should not throw, only one mail fails
        mailSender.send(java.util.List.of(mail1, mail2)).block();
        JsonPath response = RestAssured.get("/smtpMails").jsonPath();

        assertSoftly(Throwing.consumer(softly -> {
            assertThat(response.getList("")).hasSize(1);
            assertThat(response.getString("[0].from")).isEqualTo("sender1@localhost");
            assertThat(response.getString("[0].recipients[0].address")).isEqualTo("recipient1@localhost");
            assertThat(response.getString("[0].message")).containsIgnoringNewLines(rawMessage1);
        }));
    }

    @Test
    void shouldThrowWhenAllMailsFailInBatch() throws Exception {
        String rawMessage1 = "From: sender1@localhost\nTo: recipient1@localhost\nSubject: Test1\n\nHello 1!";
        String rawMessage2 = "From: sender2@localhost\nTo: recipient2@localhost\nSubject: Test2\n\nHello 2!";
        Message message1 = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage1.getBytes(StandardCharsets.UTF_8)));
        Message message2 = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage2.getBytes(StandardCharsets.UTF_8)));
        Mail mail1 = new Mail(MaybeSender.of(new MailAddress("sender1@localhost")), ImmutableList.of(new MailAddress("recipient1@localhost")), message1);
        Mail mail2 = new Mail(MaybeSender.of(new MailAddress("sender2@localhost")), ImmutableList.of(new MailAddress("recipient2@localhost")), message2);

        String behaviorJson = """
            [
                { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "recipient1@localhost" }, "response": { "code": "501", "message": "Bad recipient 1" } },
                { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "recipient2@localhost" }, "response": { "code": "501", "message": "Bad recipient 2" } }
            ]
            """;
        RestAssured.given().body(behaviorJson).contentType("application/json").put("/smtpBehaviors");

        assertThatThrownBy(() -> mailSender.send(ImmutableList.of(mail1, mail2)).block())
            .isInstanceOf(SmtpSendingFailedException.class)
            .hasMessageContaining("All 'rcpt to' commands failed");
    }
}
