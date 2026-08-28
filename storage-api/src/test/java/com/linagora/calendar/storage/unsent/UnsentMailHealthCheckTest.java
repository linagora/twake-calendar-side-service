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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.apache.james.core.healthcheck.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.SendingTrial;

import reactor.core.publisher.Mono;

class UnsentMailHealthCheckTest {
    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

    private MemoryUnsentMailRepository repository;
    private UnsentMailHealthCheck testee;

    @BeforeEach
    void setUp() {
        repository = new MemoryUnsentMailRepository(Clock.fixed(NOW, ZoneOffset.UTC));
        testee = new UnsentMailHealthCheck(repository);
    }

    private void storeUnsentMail(String recipient) throws Exception {
        repository.store(Optional.of(new MailAddress("sender@linagora.com")),
                ImmutableList.of(new MailAddress(recipient)),
                "Subject: Hi\r\n\r\nHello!".getBytes(StandardCharsets.UTF_8),
                new SendingTrial(NOW, "Connection refused"))
            .block();
    }

    private Result check() {
        return Mono.from(testee.check()).block();
    }

    @Test
    void checkShouldBeHealthyWhenNoUnsentMail() {
        assertThat(check().isHealthy()).isTrue();
    }

    @Test
    void checkShouldBeDegradedWhenSomeMailsCouldNotBeDelivered() throws Exception {
        storeUnsentMail("recipient@linagora.com");
        storeUnsentMail("other@linagora.com");

        Result result = check();

        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getCause()).contains("2 mail(s) could not be delivered. Resend them with POST /unsentMails?action=resend");
    }

    @Test
    void checkShouldBeHealthyOnceTheUnsentMailsAreDropped() throws Exception {
        storeUnsentMail("recipient@linagora.com");
        repository.deleteAll().block();

        assertThat(check().isHealthy()).isTrue();
    }
}
