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


package com.linagora.calendar.storage.unsent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UnsentMailRepository {

    record UnsentMailId(String value) {

        public static UnsentMailId generate() {
            return new UnsentMailId(UUID.randomUUID().toString());
        }

        public UnsentMailId {
            Preconditions.checkArgument(StringUtils.isNotBlank(value), "UnsentMailId must not be empty");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    record SendingTrial(Instant date, String errorMessage) {
        public static final int MAX_ERROR_MESSAGE_LENGTH = 4096;

        public static SendingTrial from(Instant date, Throwable error) {
            return new SendingTrial(date, Throwables.getStackTraceAsString(error));
        }

        public SendingTrial {
            errorMessage = StringUtils.abbreviate(errorMessage, MAX_ERROR_MESSAGE_LENGTH);
        }
    }

    record UnsentMail(UnsentMailId id,
                      Optional<MailAddress> mailFrom,
                      List<MailAddress> rcptTo,
                      byte[] mimeMessage,
                      Instant createdAt,
                      List<SendingTrial> sendingTrials) {

        public static final int MAX_RETAINED_TRIALS = 10;
        public static final int MAX_SIZE_IN_BYTES = 1024 * 1024;

        public UnsentMail {
            Preconditions.checkArgument(!rcptTo.isEmpty(), "'rcptTo' must not be empty");
            Preconditions.checkArgument(mimeMessage.length > 0, "'mimeMessage' must not be empty");
            rcptTo = ImmutableList.copyOf(rcptTo);
            sendingTrials = ImmutableList.copyOf(sendingTrials);
        }

        public UnsentMail withTrial(SendingTrial trial) {
            List<SendingTrial> trials = ImmutableList.<SendingTrial>builder()
                .addAll(sendingTrials)
                .add(trial)
                .build();

            return new UnsentMail(id, mailFrom, rcptTo, mimeMessage, createdAt,
                trials.subList(Math.max(0, trials.size() - MAX_RETAINED_TRIALS), trials.size()));
        }
    }

    record UnsentMailQuery(Optional<MailAddress> sender,
                           Optional<MailAddress> recipient,
                           Optional<Integer> limit) {

        public static final UnsentMailQuery ALL = new UnsentMailQuery(Optional.empty(), Optional.empty(), Optional.empty());

        public static class Builder {
            private Optional<MailAddress> sender = Optional.empty();
            private Optional<MailAddress> recipient = Optional.empty();
            private Optional<Integer> limit = Optional.empty();

            public Builder sender(Optional<MailAddress> sender) {
                this.sender = sender;
                return this;
            }

            public Builder sender(MailAddress sender) {
                return sender(Optional.of(sender));
            }

            public Builder recipient(Optional<MailAddress> recipient) {
                this.recipient = recipient;
                return this;
            }

            public Builder recipient(MailAddress recipient) {
                return recipient(Optional.of(recipient));
            }

            public Builder limit(Optional<Integer> limit) {
                this.limit = limit;
                return this;
            }

            public Builder limit(int limit) {
                return limit(Optional.of(limit));
            }

            public UnsentMailQuery build() {
                return new UnsentMailQuery(sender, recipient, limit);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public UnsentMailQuery {
            limit.ifPresent(value -> Preconditions.checkArgument(value > 0, "'limit' must be strictly positive"));
        }

        public boolean matches(UnsentMail unsentMail) {
            return sender.map(value -> unsentMail.mailFrom().map(value::equals).orElse(false)).orElse(true)
                && recipient.map(unsentMail.rcptTo()::contains).orElse(true);
        }
    }

    Mono<UnsentMailId> store(Optional<MailAddress> mailFrom, List<MailAddress> rcptTo,
                             byte[] mimeMessage, SendingTrial firstTrial);

    Mono<UnsentMail> read(UnsentMailId id);

    Flux<UnsentMailId> list(UnsentMailQuery query);

    Flux<UnsentMail> search(UnsentMailQuery query);

    Mono<Void> appendTrial(UnsentMailId id, SendingTrial trial);

    Mono<Void> delete(UnsentMailId id);

    Mono<Void> deleteAll();

    Mono<Long> count(UnsentMailQuery query);
}
