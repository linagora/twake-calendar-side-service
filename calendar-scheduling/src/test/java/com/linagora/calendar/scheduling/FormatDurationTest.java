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

package com.linagora.calendar.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;

import org.apache.james.core.MaybeSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.smtp.template.MailTemplateConfiguration;

class FormatDurationTest {
    private AlarmTriggerService service;

    @BeforeEach
    void setUp() {
        service = new AlarmTriggerService(null, null, null, null, null, null, null,
            new MailTemplateConfiguration("", MaybeSender.getMailSender("no-reply@openpaas.org")));
    }

    @Test
    void shouldFormatMinutesOnly() {
        String result = service.formatDuration(Duration.ofMinutes(15), Locale.ENGLISH);
        assertThat(result).contains("15 minutes");
    }

    @Test
    void shouldFormatHoursAndMinutes() {
        String result = service.formatDuration(Duration.ofMinutes(125), Locale.ENGLISH);
        assertThat(result).contains("2 hours 5 minutes");
    }

    @Test
    void shouldFormatDaysAndHours() {
        String result = service.formatDuration(Duration.ofHours(49), Locale.ENGLISH);
        assertThat(result).contains("2 days 1 hour");
    }

    @Test
    void shouldFormatHoursOnly() {
        String result = service.formatDuration(Duration.ofHours(3), Locale.ENGLISH);
        assertThat(result).contains("3 hours");
    }

    @Test
    void shouldFormatDaysOnly() {
        String result = service.formatDuration(Duration.ofDays(2), Locale.ENGLISH);
        assertThat(result).contains("2 days");
    }
}
