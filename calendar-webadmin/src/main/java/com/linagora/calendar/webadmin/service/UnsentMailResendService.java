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


package com.linagora.calendar.webadmin.service;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.MimeMessageSerializer;
import com.linagora.calendar.storage.unsent.UnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.SendingTrial;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailId;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;

import reactor.core.publisher.Mono;

public class UnsentMailResendService {

    public static class Context {
        public record Snapshot(long sentCount, long failedCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("sentCount", sentCount)
                    .add("failedCount", failedCount)
                    .toString();
            }
        }

        private final AtomicLong sentCount = new AtomicLong();
        private final AtomicLong failedCount = new AtomicLong();

        void incrementSent() {
            sentCount.incrementAndGet();
        }

        void incrementFailed() {
            failedCount.incrementAndGet();
        }

        public Snapshot snapshot() {
            return new Snapshot(sentCount.get(), failedCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UnsentMailResendService.class);

    private final UnsentMailRepository repository;
    private final MailSender.Factory mailSenderFactory;
    private final Clock clock;

    @Inject
    public UnsentMailResendService(UnsentMailRepository repository,
                                   MailSender.Factory mailSenderFactory,
                                   Clock clock) {
        this.repository = repository;
        this.mailSenderFactory = mailSenderFactory;
        this.clock = clock;
    }

    public Mono<Task.Result> resend(UnsentMailQuery query, Context context) {
        return repository.search(query)
            .concatMap(unsentMail -> resend(unsentMail, context))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    public Mono<Task.Result> resend(UnsentMailId id, Context context) {
        return repository.read(id)
            .flatMap(unsentMail -> resend(unsentMail, context))
            .switchIfEmpty(Mono.fromCallable(() -> {
                LOGGER.info("Unsent mail {} could not be resent: it is no longer stored", id.value());
                context.incrementFailed();
                return Task.Result.PARTIAL;
            }));
    }

    private Mono<Task.Result> resend(UnsentMail unsentMail, Context context) {
        return Mono.fromCallable(() -> MimeMessageSerializer.toMail(unsentMail))
            .flatMap(mail -> mailSenderFactory.createWithoutRetention()
                .flatMap(mailSender -> mailSender.send(mail)))
            .then(repository.delete(unsentMail.id()))
            .then(Mono.fromCallable(() -> {
                context.incrementSent();
                return Task.Result.COMPLETED;
            }))
            .onErrorResume(error -> {
                LOGGER.warn("Resending unsent mail {} failed", unsentMail.id().value(), error);
                context.incrementFailed();
                return repository.appendTrial(unsentMail.id(), SendingTrial.from(clock.instant(), error))
                    .thenReturn(Task.Result.PARTIAL);
            });
    }
}
