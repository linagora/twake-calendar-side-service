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
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.X509TrustManager;

import jakarta.inject.Inject;

import org.apache.commons.net.smtp.AuthenticatingSMTPClient;
import org.apache.commons.net.smtp.SMTPClient;
import org.apache.commons.net.smtp.SMTPReply;
import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.unsent.UnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.SendingTrial;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

public interface MailSender {
    Logger LOGGER = LoggerFactory.getLogger(MailSender.class);

    Mono<Void> send(Mail mail);

    interface Factory {
        Mono<MailSender> create();

        Mono<Void> send(Mail mail);

        // Resending an already retained mail relies on it: a failed resend appends a trial rather than storing a duplicate.
        default Mono<Void> sendWithoutRetention(Mail mail) {
            return send(mail);
        }

        class Default implements Factory {
            private static final String DEFAULT_PROTOCOL = "TLS";
            private static final String UTF_8_ENCODING = "UTF-8";
            static final String SMTP_SEND_MAX_RETRIES_PROPERTY = "twake.calendar.smtp.max-retries";
            private static final int MAX_SMTP_SEND_RETRIES = maxSmtpSendRetries();
            private static final Duration SMTP_SEND_RETRY_BACKOFF = Duration.ofMillis(100);
            private static final Retry RETRY_SEND =
                Retry.backoff(MAX_SMTP_SEND_RETRIES, SMTP_SEND_RETRY_BACKOFF)
                    .maxBackoff(Duration.ofSeconds(5))
                    .doBeforeRetry(retrySignal -> LOGGER.warn("Retrying SMTP mail send after failure (attempt {}/{})",
                        retrySignal.totalRetries() + 1, MAX_SMTP_SEND_RETRIES, retrySignal.failure()))
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure());
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
            private final EventEmailFilter eventEmailFilter;
            private final Optional<UnsentMailRepository> unsentMailRepository;
            private final Clock clock;

            @Inject
            public Default(MailSenderConfiguration configuration, EventEmailFilter eventEmailFilter,
                           UnsentMailRepository unsentMailRepository, Clock clock) {
                this(configuration, eventEmailFilter, Optional.of(unsentMailRepository), clock);
            }

            public Default(MailSenderConfiguration configuration, EventEmailFilter eventEmailFilter) {
                this(configuration, eventEmailFilter, Optional.empty(), Clock.systemUTC());
            }

            private Default(MailSenderConfiguration configuration, EventEmailFilter eventEmailFilter,
                            Optional<UnsentMailRepository> unsentMailRepository, Clock clock) {
                this.configuration = configuration;
                this.eventEmailFilter = eventEmailFilter;
                this.unsentMailRepository = unsentMailRepository;
                this.clock = clock;
            }

            public Mono<MailSender> create() {
                return Mono.<MailSender>fromCallable(() -> {
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
                            throw new SmtpSendingFailedException("'starttls' failed: " + authClient.getReplyString());
                        }
                    }
                    // AUTH
                    configuration.username().ifPresent(Throwing.consumer(username -> {
                        String password = configuration.password().get();
                        authClient.auth(AuthenticatingSMTPClient.AUTH_METHOD.PLAIN, username.asString(), password);
                        if (!SMTPReply.isPositiveCompletion(authClient.getReplyCode())) {
                            throw new SmtpSendingFailedException("'auth' failed: " + authClient.getReplyString());
                        }
                    }));
                    return new MailSender.Default(authClient, configuration, eventEmailFilter);
                })
                .subscribeOn(Schedulers.boundedElastic());
            }

            @Override
            public Mono<Void> send(Mail mail) {
                return sendWithoutRetention(mail)
                    .onErrorResume(error -> retain(mail, asException(error))
                        .then(Mono.error(error)));
            }

            @Override
            public Mono<Void> sendWithoutRetention(Mail mail) {
                return Mono.defer(() -> create()
                        .flatMap(mailSender -> mailSender.send(mail)))
                    .retryWhen(RETRY_SEND);
            }

            static int maxSmtpSendRetries() {
                return Integer.getInteger(SMTP_SEND_MAX_RETRIES_PROPERTY, 3);
            }

            private Mono<Void> retain(Mail mail, Exception error) {
                return unsentMailRepository.map(repository -> retain(repository, mail, error))
                    .orElse(Mono.empty());
            }

            private Mono<Void> retain(UnsentMailRepository repository, Mail mail, Exception error) {
                return Mono.fromCallable(() -> MimeMessageSerializer.asBytes(mail.message()))
                    .flatMap(mimeMessage -> {
                        if (mimeMessage.length > UnsentMail.MAX_SIZE_IN_BYTES) {
                            LOGGER.warn("Not retaining an unsent mail to {}: its size ({} bytes) exceeds {} bytes",
                                mail.recipients(), mimeMessage.length, UnsentMail.MAX_SIZE_IN_BYTES);
                            return Mono.empty();
                        }
                        return repository.store(mail.sender().asOptional(),
                                List.copyOf(mail.recipients()),
                                mimeMessage,
                                SendingTrial.from(clock.instant(), error))
                            .doOnSuccess(id -> LOGGER.info("Retained unsent mail {} for recipients {}", id, mail.recipients()))
                            .then();
                    })
                    .onErrorResume(retentionError -> {
                        LOGGER.error("Failed retaining an unsent mail for recipients {}", mail.recipients(), retentionError);
                        return Mono.empty();
                    });
            }

            private Exception asException(Throwable throwable) {
                if (throwable instanceof Exception exception) {
                    return exception;
                }
                return new RuntimeException(throwable);
            }
        }
    }

    class Default implements MailSender {
        private final SMTPClient client;
        private final MailSenderConfiguration configuration;
        private final EventEmailFilter eventEmailFilter;

        public Default(SMTPClient client, MailSenderConfiguration configuration, EventEmailFilter eventEmailFilter) {
            this.client = client;
            this.configuration = configuration;
            this.eventEmailFilter = eventEmailFilter;
        }

        @Override
        public Mono<Void> send(Mail mail) {
            return Mono.<Void>fromRunnable(Throwing.runnable(() -> {
                try {
                    eventEmailFilter.filterRecipients(mail).ifPresent(Throwing.consumer(this::sendMailTransaction));
                } finally {
                    disconnect();
                }
            })).subscribeOn(Schedulers.boundedElastic());
        }

        private void disconnect() throws IOException {
            if (client.isConnected()) {
                client.logout();
                client.disconnect();
            }
        }

        private void sendMailTransaction(Mail mail) throws IOException {
            int heloCode = client.helo(configuration.ehlo());
            if (!SMTPReply.isPositiveCompletion(heloCode)) {
                throw new SmtpSendingFailedException("'helo' failed: " + client.getReplyString());
            }

            client.setSender(mail.sender().asString(""));
            if (!SMTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new SmtpSendingFailedException("'mail from' failed: " + client.getReplyString());
            }

            addRecipients(mail);
            sendMessageData(mail);

            if (!client.completePendingCommand()) {
                throw new SmtpSendingFailedException("'data' command failed: " + client.getReplyString());
            }
        }

        private void sendMessageData(Mail mail) throws IOException {
            DefaultMessageWriter defaultMessageWriter = new DefaultMessageWriter();
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                defaultMessageWriter.writeMessage(mail.message(), baos);
                try (Writer writer = client.sendMessageData()) {
                    writer.write(baos.toString(StandardCharsets.UTF_8));
                }
            }
        }

        private void addRecipients(Mail mail) throws IOException {
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
                throw new SmtpSendingFailedException("All 'rcpt to' commands failed: " + client.getReplyString());
            }
        }
    }
}