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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.apache.commons.net.smtp.SMTPClient;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.util.Port;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.smtp.SmtpSendingFailedException.UnknownUser;
import com.linagora.calendar.storage.unsent.MemoryUnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;

import io.restassured.RestAssured;
import reactor.core.publisher.Mono;

class UnsentMailRetentionTest {

    @RegisterExtension
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private MemoryUnsentMailRepository repository;
    private MailSenderConfiguration smtpConfiguration;

    @BeforeEach
    void setUp() {
        smtpConfiguration = smtpConfiguration(Port.of(mockSmtpExtension.getMockSmtp().getSmtpPort()));
        repository = new MemoryUnsentMailRepository(CLOCK);

        RestAssured.baseURI = "http://localhost";
        RestAssured.port = mockSmtpExtension.getMockSmtp().getRestApiPort();
        RestAssured.delete("/smtpMails");
        RestAssured.delete("/smtpBehaviors");
    }

    private MailSenderConfiguration smtpConfiguration(Port port) {
        return new MailSenderConfiguration("localhost", port, "localhost",
            Optional.empty(), Optional.empty(), false, false, false);
    }

    private MailSender.Factory testee(MailSenderConfiguration smtpConfiguration) {
        return new MailSender.Factory.Default(smtpConfiguration, EventEmailFilter.acceptAll(), repository, CLOCK);
    }

    private MailSender.Factory testee() {
        return testee(smtpConfiguration);
    }

    private Mail mail(String sender, String recipient) throws Exception {
        return mail(sender, recipient, "Hello!");
    }

    private Mail mail(String sender, String recipient, String body) throws Exception {
        String rawMessage = "From: %s\r\nTo: %s\r\nSubject: Test\r\n\r\n%s".formatted(sender, recipient, body);
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(rawMessage.getBytes(StandardCharsets.UTF_8)));
        return new Mail(MaybeSender.of(new MailAddress(sender)), ImmutableList.of(new MailAddress(recipient)), message);
    }

    private void rejectRecipient(String recipient) {
        rejectRecipient(recipient, "501", "Bad recipient");
    }

    private void rejectRecipient(String recipient, String code, String message) {
        RestAssured.given()
            .body("""
                [ { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "RECIPIENT" }, "response": { "code": "CODE", "message": "MESSAGE" } } ]
                """.replace("RECIPIENT", recipient).replace("CODE", code).replace("MESSAGE", message))
            .contentType("application/json")
            .put("/smtpBehaviors");
    }

    @Test
    void shouldNotRetainDeliveredMails() throws Exception {
        testee().send(mail("sender@localhost", "recipient@localhost")).block();

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    @Test
    void shouldRetainMailWhenDeliveryFails() throws Exception {
        rejectRecipient("recipient@localhost");

        assertThatThrownBy(() -> testee().send(mail("sender@localhost", "recipient@localhost")).block())
            .isInstanceOf(Exception.class);

        List<UnsentMail> unsentMails = repository.search(UnsentMailQuery.ALL).collectList().block();
        assertThat(unsentMails).hasSize(1);
        assertThat(unsentMails.getFirst().mailFrom()).contains(new MailAddress("sender@localhost"));
        assertThat(unsentMails.getFirst().rcptTo()).containsExactly(new MailAddress("recipient@localhost"));
        assertThat(new String(unsentMails.getFirst().mimeMessage(), StandardCharsets.UTF_8))
            .contains("Subject: Test");
        assertThat(unsentMails.getFirst().sendingTrials()).hasSize(1);
        assertThat(unsentMails.getFirst().sendingTrials().getFirst().date()).isEqualTo(NOW);
        assertThat(unsentMails.getFirst().sendingTrials().getFirst().errorMessage())
            .contains("All 'rcpt to' commands failed");
    }

    @Test
    void shouldDiscardMailWhenAllRecipientsAreUnknown() throws Exception {
        rejectRecipient("recipient@localhost", "550", "5.1.1 Unknown user: recipient@localhost");

        testee().send(mail("sender@localhost", "recipient@localhost")).block();

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    private MailSender.Factory.Default testee(SMTPClient client) throws IOException {
        Mockito.when(client.helo(smtpConfiguration.ehlo())).thenReturn(250);
        Mockito.when(client.getReplyCode()).thenReturn(250, 550);
        Mockito.when(client.getReplyString()).thenReturn("550 5.1.1 Unknown user: recipient@localhost\r\n");
        Mockito.when(client.isConnected()).thenReturn(true);
        return new MailSender.Factory.Default(smtpConfiguration, EventEmailFilter.acceptAll(), repository, CLOCK) {
            @Override
            public Mono<MailSender> create() {
                return Mono.just(new MailSender.Default(client, smtpConfiguration, EventEmailFilter.acceptAll()));
            }
        };
    }

    @Test
    void shouldNotRetryOrRetainUnknownUserWhenLogoutFails() throws Exception {
        SMTPClient client = Mockito.mock(SMTPClient.class);
        MailSender.Factory.Default factory = Mockito.spy(testee(client));
        Mockito.when(client.logout()).thenThrow(new IOException("Connection closed during QUIT"));

        factory.send(mail("sender@localhost", "recipient@localhost")).block();

        Mockito.verify(factory).create();
        Mockito.verify(client).disconnect();
        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    @Test
    void shouldPreserveUnknownUserAndSuppressCleanupFailures() throws Exception {
        SMTPClient client = Mockito.mock(SMTPClient.class);
        MailSender.Factory.Default factory = Mockito.spy(testee(client));
        IOException logoutFailure = new IOException("Connection closed during QUIT");
        IOException disconnectFailure = new IOException("Disconnect failed");
        Mockito.when(client.logout()).thenThrow(logoutFailure);
        Mockito.doThrow(disconnectFailure).when(client).disconnect();

        assertThatThrownBy(() -> factory.sendWithoutRetention(mail("sender@localhost", "recipient@localhost")).block())
            .isInstanceOf(UnknownUser.class)
            .satisfies(error -> assertThat(error.getSuppressed()).contains(logoutFailure));

        assertThat(logoutFailure.getSuppressed()).containsExactly(disconnectFailure);
        Mockito.verify(factory).create();
        Mockito.verify(client).disconnect();
    }

    @Test
    void shouldRetainMailWhenRecipientIsRejectedForAnotherReason() throws Exception {
        rejectRecipient("recipient@localhost", "550", "5.1.1 Mailbox unavailable");

        assertThatThrownBy(() -> testee().send(mail("sender@localhost", "recipient@localhost")).block())
            .isInstanceOf(SmtpSendingFailedException.class);

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).hasSize(1);
    }

    @Test
    void shouldRetainMailWhenOnlyLastRecipientIsUnknown() throws Exception {
        RestAssured.given()
            .body("""
                [
                  { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "first@localhost" }, "response": { "code": "451", "message": "Temporary failure" } },
                  { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "last@localhost" }, "response": { "code": "550", "message": "5.1.1 Unknown user: last@localhost" } }
                ]
                """)
            .contentType("application/json")
            .put("/smtpBehaviors");
        Message message = new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(
            "From: sender@localhost\r\nTo: first@localhost, last@localhost\r\nSubject: Test\r\n\r\nHello!"
                .getBytes(StandardCharsets.UTF_8)));
        Mail mail = new Mail(MaybeSender.of(new MailAddress("sender@localhost")),
            List.of(new MailAddress("first@localhost"), new MailAddress("last@localhost")), message);

        assertThatThrownBy(() -> testee().send(mail).block())
            .isInstanceOf(SmtpSendingFailedException.class)
            .isNotInstanceOf(SmtpSendingFailedException.UnknownUser.class);

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).hasSize(1);
    }

    @Test
    void shouldRetainMailWhenTheSmtpServerCanNotBeReached() throws Exception {
        MailSender.Factory testee = testee(smtpConfiguration(closedPort()));

        assertThatThrownBy(() -> testee.send(mail("sender@localhost", "recipient@localhost")).block())
            .isInstanceOf(Exception.class);

        assertThat(repository.search(UnsentMailQuery.ALL).collectList().block())
            .flatExtracting(UnsentMail::rcptTo)
            .containsExactly(new MailAddress("recipient@localhost"));
    }

    private Port closedPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return Port.of(serverSocket.getLocalPort());
        }
    }

    @Test
    void shouldNotRetainWhenSendingWithoutRetention() throws Exception {
        rejectRecipient("recipient@localhost");

        assertThatThrownBy(() -> testee().sendWithoutRetention(mail("sender@localhost", "recipient@localhost")).block())
            .isInstanceOf(Exception.class);

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    @Test
    void shouldNotRetainMailsBiggerThanTheSizeLimit() throws Exception {
        rejectRecipient("recipient@localhost");

        String oversizedBody = "a".repeat(UnsentMail.MAX_SIZE_IN_BYTES + 1);

        assertThatThrownBy(() -> testee().send(mail("sender@localhost", "recipient@localhost", oversizedBody)).block())
            .isInstanceOf(Exception.class);

        assertThat(repository.list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }
}
