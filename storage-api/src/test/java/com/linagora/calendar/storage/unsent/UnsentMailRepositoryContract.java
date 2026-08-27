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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.SendingTrial;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailId;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;

public interface UnsentMailRepositoryContract {

    Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    byte[] MIME_MESSAGE = "From: sender@linagora.com\r\nTo: recipient@linagora.com\r\nSubject: Hi\r\n\r\nHello!"
        .getBytes(StandardCharsets.UTF_8);

    UnsentMailRepository testee();

    UpdatableTickingClock clock();

    static MailAddress mailAddress(String value) {
        try {
            return new MailAddress(value);
        } catch (AddressException e) {
            throw new RuntimeException(e);
        }
    }

    default SendingTrial trial(String errorMessage) {
        return new SendingTrial(clock().instant(), errorMessage);
    }

    default UnsentMailId store(String sender, String recipient) {
        return testee().store(Optional.of(mailAddress(sender)), ImmutableList.of(mailAddress(recipient)),
            MIME_MESSAGE, trial("Connection refused")).block();
    }

    @Test
    default void readShouldReturnEmptyWhenNoSuchMail() {
        assertThat(testee().read(UnsentMailId.generate()).blockOptional()).isEmpty();
    }

    @Test
    default void readShouldReturnTheStoredMail() {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        UnsentMail unsentMail = testee().read(id).block();

        assertSoftly(softly -> {
            softly.assertThat(unsentMail.id()).isEqualTo(id);
            softly.assertThat(unsentMail.mailFrom()).contains(mailAddress("sender@linagora.com"));
            softly.assertThat(unsentMail.rcptTo()).containsExactly(mailAddress("recipient@linagora.com"));
            softly.assertThat(unsentMail.mimeMessage()).isEqualTo(MIME_MESSAGE);
            softly.assertThat(unsentMail.createdAt()).isEqualTo(NOW);
            softly.assertThat(unsentMail.sendingTrials()).containsExactly(trial("Connection refused"));
        });
    }

    @Test
    default void readShouldSupportNullSender() {
        UnsentMailId id = testee().store(Optional.empty(), ImmutableList.of(mailAddress("recipient@linagora.com")),
            MIME_MESSAGE, trial("Connection refused")).block();

        assertThat(testee().read(id).block().mailFrom()).isEmpty();
    }

    @Test
    default void readShouldReturnAllRecipients() {
        UnsentMailId id = testee().store(Optional.of(mailAddress("sender@linagora.com")),
            ImmutableList.of(mailAddress("a@linagora.com"), mailAddress("b@linagora.com")),
            MIME_MESSAGE, trial("Connection refused")).block();

        assertThat(testee().read(id).block().rcptTo())
            .containsExactly(mailAddress("a@linagora.com"), mailAddress("b@linagora.com"));
    }

    @Test
    default void listShouldReturnEmptyWhenNone() {
        assertThat(testee().list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }

    @Test
    default void listShouldReturnStoredMailsOldestFirst() {
        UnsentMailId first = store("sender@linagora.com", "recipient@linagora.com");
        clock().setInstant(NOW.plus(1, ChronoUnit.HOURS));
        UnsentMailId second = store("sender@linagora.com", "recipient@linagora.com");

        assertThat(testee().list(UnsentMailQuery.ALL).collectList().block())
            .containsExactly(first, second);
    }

    @Test
    default void listShouldFilterBySender() {
        UnsentMailId matching = store("sender@linagora.com", "recipient@linagora.com");
        store("other@linagora.com", "recipient@linagora.com");

        assertThat(testee().list(UnsentMailQuery.builder()
                .sender(mailAddress("sender@linagora.com"))
                .build())
            .collectList().block())
            .containsExactly(matching);
    }

    @Test
    default void listShouldFilterByRecipient() {
        UnsentMailId matching = store("sender@linagora.com", "recipient@linagora.com");
        store("sender@linagora.com", "other@linagora.com");

        assertThat(testee().list(UnsentMailQuery.builder()
                .recipient(mailAddress("recipient@linagora.com"))
                .build())
            .collectList().block())
            .containsExactly(matching);
    }

    @Test
    default void listShouldMatchAnyRecipientOfTheEnvelope() {
        UnsentMailId id = testee().store(Optional.of(mailAddress("sender@linagora.com")),
            ImmutableList.of(mailAddress("a@linagora.com"), mailAddress("b@linagora.com")),
            MIME_MESSAGE, trial("Connection refused")).block();

        assertThat(testee().list(UnsentMailQuery.builder()
                .recipient(mailAddress("b@linagora.com"))
                .build())
            .collectList().block())
            .containsExactly(id);
    }

    @Test
    default void listShouldNotMatchTheNullSenderWhenSenderIsSpecified() {
        testee().store(Optional.empty(), ImmutableList.of(mailAddress("recipient@linagora.com")),
            MIME_MESSAGE, trial("Connection refused")).block();

        assertThat(testee().list(UnsentMailQuery.builder()
                .sender(mailAddress("sender@linagora.com"))
                .build())
            .collectList().block())
            .isEmpty();
    }

    @Test
    default void listShouldApplyLimit() {
        UnsentMailId first = store("sender@linagora.com", "recipient@linagora.com");
        clock().setInstant(NOW.plus(1, ChronoUnit.HOURS));
        store("sender@linagora.com", "recipient@linagora.com");

        assertThat(testee().list(UnsentMailQuery.builder().limit(1).build())
            .collectList().block())
            .containsExactly(first);
    }

    @Test
    default void searchShouldReturnTheStoredMails() {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        assertThat(testee().search(UnsentMailQuery.ALL).collectList().block())
            .extracting(UnsentMail::id)
            .containsExactly(id);
    }

    @Test
    default void countShouldReturnTheMatchingMailCount() {
        store("sender@linagora.com", "recipient@linagora.com");
        store("other@linagora.com", "recipient@linagora.com");

        assertThat(testee().count(UnsentMailQuery.builder()
            .sender(mailAddress("sender@linagora.com"))
            .build()).block())
            .isEqualTo(1L);
    }

    @Test
    default void countShouldReturnZeroWhenNone() {
        assertThat(testee().count(UnsentMailQuery.ALL).block()).isEqualTo(0L);
    }

    @Test
    default void appendTrialShouldAddTheTrial() {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        clock().setInstant(NOW.plus(1, ChronoUnit.HOURS));
        testee().appendTrial(id, trial("Mailbox full")).block();

        assertThat(testee().read(id).block().sendingTrials())
            .containsExactly(new SendingTrial(NOW, "Connection refused"),
                new SendingTrial(NOW.plus(1, ChronoUnit.HOURS), "Mailbox full"));
    }

    @Test
    default void appendTrialShouldRetainOnlyTheMostRecentTrials() {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        IntStream.range(0, UnsentMail.MAX_RETAINED_TRIALS + 5)
            .forEach(i -> testee().appendTrial(id, new SendingTrial(NOW, "Failure " + i)).block());

        List<SendingTrial> trials = testee().read(id).block().sendingTrials();
        assertThat(trials).hasSize(UnsentMail.MAX_RETAINED_TRIALS);
        assertThat(trials.getLast().errorMessage())
            .isEqualTo("Failure " + (UnsentMail.MAX_RETAINED_TRIALS + 4));
    }

    @Test
    default void appendTrialShouldNotThrowWhenNoSuchMail() {
        assertThatCode(() -> testee().appendTrial(UnsentMailId.generate(), trial("Mailbox full")).block())
            .doesNotThrowAnyException();
    }

    @Test
    default void deleteShouldRemoveTheMail() {
        UnsentMailId id = store("sender@linagora.com", "recipient@linagora.com");

        testee().delete(id).block();

        assertThat(testee().read(id).blockOptional()).isEmpty();
    }

    @Test
    default void deleteShouldNotThrowWhenNoSuchMail() {
        assertThatCode(() -> testee().delete(UnsentMailId.generate()).block())
            .doesNotThrowAnyException();
    }

    @Test
    default void deleteAllShouldRemoveEveryMail() {
        store("sender@linagora.com", "recipient@linagora.com");
        store("other@linagora.com", "recipient@linagora.com");

        testee().deleteAll().block();

        assertThat(testee().list(UnsentMailQuery.ALL).collectList().block()).isEmpty();
    }
}
