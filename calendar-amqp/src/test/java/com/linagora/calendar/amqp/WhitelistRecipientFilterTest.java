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

package com.linagora.calendar.amqp;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.EventEmailFilter.WhitelistRecipientFilter;

public class WhitelistRecipientFilterTest {

    @Test
    void shouldProcessWhenRecipientIsInWhitelist() throws Exception {
        Set<MailAddress> whitelist = Set.of(new MailAddress("user1@example.com"), new MailAddress("user2@example.com"));
        WhitelistRecipientFilter filter = new WhitelistRecipientFilter(whitelist);

        CalendarEventNotificationEmailDTO dto = mock(CalendarEventNotificationEmailDTO.class);
        when(dto.recipientEmail()).thenReturn(new MailAddress("user1@example.com"));

        assertThat(filter.shouldProcess(dto)).isTrue();
    }

    @Test
    void shouldNotProcessWhenNoRecipientIsInWhitelist() throws Exception {
        Set<MailAddress> whitelist = Set.of(new MailAddress("user1@example.com"), new MailAddress("user2@example.com"));
        WhitelistRecipientFilter filter = new WhitelistRecipientFilter(whitelist);

        CalendarEventNotificationEmailDTO dto = mock(CalendarEventNotificationEmailDTO.class);
        when(dto.recipientEmail()).thenReturn(new MailAddress("user3@example.com"));

        assertThat(filter.shouldProcess(dto)).isFalse();
    }

    @Test
    void shouldNotProcessWhenWhiteListIsEmpty() throws Exception {
        WhitelistRecipientFilter filter = new WhitelistRecipientFilter(Set.of());

        CalendarEventNotificationEmailDTO dto = mock(CalendarEventNotificationEmailDTO.class);
        when(dto.recipientEmail()).thenReturn(new MailAddress("user3@example.com"));

        assertThat(filter.shouldProcess(dto)).isFalse();
    }
}
