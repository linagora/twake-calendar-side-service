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

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.util.Port;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.unsent.MemoryUnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;

import io.restassured.RestAssured;

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
        RestAssured.given()
            .body("""
                [ { "command": "RCPT TO", "condition": { "operator": "contains", "matchingValue": "%s" }, "response": { "code": "501", "message": "Bad recipient" } } ]
                """.formatted(recipient))
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
