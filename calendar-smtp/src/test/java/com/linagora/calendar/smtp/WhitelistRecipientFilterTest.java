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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

class WhitelistRecipientFilterTest {

    @Test
    void shouldProcessWhenRecipientIsInWhitelist() throws Exception {
        Set<MailAddress> whitelist = Set.of(new MailAddress("user1@example.com"), new MailAddress("user2@example.com"));
        EventEmailFilter.WhitelistRecipientFilter filter = new EventEmailFilter.WhitelistRecipientFilter(whitelist);

        AssertionsForClassTypes.assertThat(filter.shouldProcess(new MailAddress("user1@example.com"))).isTrue();
    }

    @Test
    void shouldNotProcessWhenNoRecipientIsInWhitelist() throws Exception {
        Set<MailAddress> whitelist = Set.of(new MailAddress("user1@example.com"), new MailAddress("user2@example.com"));
        EventEmailFilter.WhitelistRecipientFilter filter = new EventEmailFilter.WhitelistRecipientFilter(whitelist);

        AssertionsForClassTypes.assertThat(filter.shouldProcess(new MailAddress("user3@example.com"))).isFalse();
    }

    @Test
    void shouldNotProcessWhenWhiteListIsEmpty() throws Exception {
        EventEmailFilter.WhitelistRecipientFilter filter = new EventEmailFilter.WhitelistRecipientFilter(Set.of());

        AssertionsForClassTypes.assertThat(filter.shouldProcess(new MailAddress("user3@example.com"))).isFalse();
    }

    @Test
    void filterRecipientsShouldReturnsMailWithAllAllowedRecipients() throws Exception {
        MailAddress alice = new MailAddress("alice@example.org");
        MailAddress bob = new MailAddress("bob@example.org");
        EventEmailFilter filter = new EventEmailFilter.WhitelistRecipientFilter(ImmutableSet.of(alice, bob));

        Message message = toMessage("From: a@b\nTo: x@y\n\nHello");
        Mail mail = new Mail(MaybeSender.of(alice), List.of(alice, bob), message);

        Optional<Mail> result = filter.filterRecipients(mail);

        assertThat(result).isPresent();
        Mail filtered = result.get();
        assertThat(filtered.recipients()).hasSize(2).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void filterRecipientsShouldFiltersOutUnallowedRecipients() throws Exception {
        MailAddress alice = new MailAddress("alice@example.org");
        MailAddress bob = new MailAddress("bob@example.org");
        EventEmailFilter filter = new EventEmailFilter.WhitelistRecipientFilter(ImmutableSet.of(alice));

        Message message = toMessage("From: a@b\nTo: x@y\n\nHello");
        Mail mail = new Mail(MaybeSender.of(alice), List.of(alice, bob), message);

        Optional<Mail> result = filter.filterRecipients(mail);

        assertThat(result).isPresent();
        Mail filtered = result.get();
        assertThat(filtered.recipients()).hasSize(1).containsExactly(alice);
    }

    @Test
    void filterRecipientsShouldReturnsEmptyWhenNoRecipientAllowed() throws Exception {
        MailAddress alice = new MailAddress("alice@example.org");
        MailAddress bob = new MailAddress("bob@example.org");
        EventEmailFilter filter = new EventEmailFilter.WhitelistRecipientFilter(ImmutableSet.of());

        Message message = toMessage("From: a@b\nTo: x@y\n\nHello");
        Mail mail = new Mail(MaybeSender.of(alice), List.of(alice, bob), message);

        Optional<Mail> result = filter.filterRecipients(mail);

        assertThat(result).isEmpty();
    }

    static Message toMessage(String rawMime) throws Exception {
        DefaultMessageBuilder builder = new DefaultMessageBuilder();
        try (ByteArrayInputStream is = new ByteArrayInputStream(rawMime.getBytes(StandardCharsets.UTF_8))) {
            return builder.parseMessage(is);
        }
    }
}

