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

import java.io.IOException;
import java.io.Writer;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;

import javax.net.ssl.X509TrustManager;

import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.net.smtp.AuthenticatingSMTPClient;
import org.apache.commons.net.smtp.SMTPClient;
import org.apache.commons.net.smtp.SMTPReply;
import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;

import reactor.core.publisher.Mono;

public interface MailSender {
    Logger LOGGER = LoggerFactory.getLogger(MailSender.class);

    Mono<Void> send(Mail mail);

    Mono<Void> send(Collection<Mail> mails);

    interface Factory {
        Mono<MailSender> create();

        class Default implements Factory {
            private static final String DEFAULT_PROTOCOL = "TLS";
            private static final String UTF_8_ENCODING = "UTF-8";
            public static final X509TrustManager TRUST_ALL = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {

                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {

                }
            };

            private final MailSenderConfiguration configuration;

            public Default(MailSenderConfiguration configuration) {
                this.configuration = configuration;
            }

            public Mono<MailSender> create() {
                return Mono.fromCallable(() -> {
                    AuthenticatingSMTPClient authClient = new AuthenticatingSMTPClient(DEFAULT_PROTOCOL,
                        configuration.sslEnabled(),
                        UTF_8_ENCODING);

                    if (configuration.trustAllCerts()) {
                        authClient.setTrustManager(TRUST_ALL);
                    }

                    // Connect
                    authClient.connect(configuration.host(), configuration.port().getValue());

                    // StartTLS if needed
                    if (configuration.startTlsEnabled()) {
                        authClient.execTLS();
                        if (!SMTPReply.isPositiveCompletion(authClient.getReplyCode())) {
                            throw new RuntimeException("'starttls' failed: " + authClient.getReplyString());
                        }
                    }
                    // AUTH
                    configuration.username().ifPresent(Throwing.consumer(username -> {
                        String password = configuration.password().get();
                        authClient.auth(AuthenticatingSMTPClient.AUTH_METHOD.PLAIN, username.asString(), password);
                        if (!SMTPReply.isPositiveCompletion(authClient.getReplyCode())) {
                            throw new RuntimeException("'auth' failed: " + authClient.getReplyString());
                        }
                    }));
                    return new MailSender.Default(authClient, configuration);
                });
            }
        }
    }

    class Default implements MailSender {
        private final SMTPClient client;
        private final MailSenderConfiguration configuration;

        public Default(SMTPClient client, MailSenderConfiguration configuration) {
            this.client = client;
            this.configuration = configuration;
        }

        @Override
        public Mono<Void> send(Mail mail) {
            return Mono.fromRunnable(Throwing.runnable(() -> {
                sendMailTransaction(mail);
                disconnect();
            }));
        }

        @Override
        public Mono<Void> send(Collection<Mail> mails) {
            return Mono.fromRunnable(Throwing.runnable(() -> {
                ArrayList<Exception> exceptions = new ArrayList<>();

                mails.forEach(Throwing.consumer(mail -> {
                    try {
                        sendMailTransaction(mail);
                    } catch (Exception e) {
                        LOGGER.warn("Sendering email failed", e);
                        exceptions.add(e);
                    }
                    boolean reset = client.reset();
                    if (!reset) {
                        throw new RuntimeException("Failure to reset SMTP client: " + client.getReplyString());
                    }
                }));
                disconnect();

                if (exceptions.size() == mails.size()) {
                    throw exceptions.getFirst();
                }
            }));
        }

        private void disconnect() throws IOException {
            if (client.isConnected()) {
                client.logout();
                client.disconnect();
            }
        }

        private void sendMailTransaction(Mail mail) throws IOException {
            int heloCode = client.helo(configuration.ehlo());
            if (SMTPReply.isPositiveCompletion(heloCode)) {
                throw new RuntimeException("'helo' failed: " + client.getReplyString());
            }
            client.setSender(mail.sender().asString(""));
            if (!SMTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new RuntimeException("'mail from' failed: " + client.getReplyString());
            }
            int successfullRecipientCount = 0;
            for (MailAddress recipient : mail.recipients()) {
                client.addRecipient(recipient.asString());
                if (!SMTPReply.isPositiveCompletion(client.getReplyCode())) {
                    LOGGER.warn("'rcpr to' command failed for {}: {}", recipient.asString(), client.getReplyString());
                } else {
                    successfullRecipientCount++;
                }
            }
            if (successfullRecipientCount == 0) {
                throw new RuntimeException("All 'rcpt to' commands failed: " + client.getReplyString());
            }
            DefaultMessageWriter defaultMessageWriter = new DefaultMessageWriter();

            try (Writer writer = client.sendMessageData()) {
                defaultMessageWriter.writeMessage(mail.message(), new WriterOutputStream(writer));
            }
            if (!client.completePendingCommand()) {
                throw new RuntimeException("'data' command failed: " + client.getReplyString());
            }
        }
    }
}