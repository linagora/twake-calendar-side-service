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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class TimeFormatUtilTest {

    @Test
    void shouldFormatMinutesOnly() {
        String result = TimeFormatUtil.formatDuration(Duration.ofMinutes(15), Locale.ENGLISH);
        assertThat(result).isEqualTo("15 minutes");
    }

    @Test
    void shouldFormatHoursAndMinutes() {
        String result = TimeFormatUtil.formatDuration(Duration.ofMinutes(125), Locale.ENGLISH);
        assertThat(result).isEqualTo("2 hours 5 minutes");
    }

    @Test
    void shouldFormatDaysAndHours() {
        String result = TimeFormatUtil.formatDuration(Duration.ofHours(49), Locale.ENGLISH);
        assertThat(result).isEqualTo("2 days 1 hour");
    }

    @Test
    void shouldFormatHoursOnly() {
        String result = TimeFormatUtil.formatDuration(Duration.ofHours(3), Locale.ENGLISH);
        assertThat(result).isEqualTo("3 hours");
    }

    @Test
    void shouldFormatDaysAndHoursInFrench() {
        String result = TimeFormatUtil.formatDuration(Duration.ofHours(49), Locale.FRENCH);
        assertThat(result).isEqualToNormalizingWhitespace("2 jours 1 heure");
    }

    @Test
    void shouldRoundUpSecondsToNextMinute() {
        String result = TimeFormatUtil.formatDuration(Duration.ofSeconds(121), Locale.ENGLISH);
        assertThat(result).isEqualTo("3 minutes");
    }

    @Test
    void shouldFormatExactlyOneMinute() {
        String result = TimeFormatUtil.formatDuration(Duration.ofMinutes(1), Locale.ENGLISH);
        assertThat(result).isEqualTo("1 minute");
    }

    @Test
    void shouldFormatExactlyOneHour() {
        String result = TimeFormatUtil.formatDuration(Duration.ofHours(1), Locale.ENGLISH);
        assertThat(result).isEqualTo("1 hour");
    }

    @Test
    void shouldRoundUpFractionsOfASecond() {
        assertThat(TimeFormatUtil.formatDuration(Duration.ofMillis(500), Locale.ENGLISH)).isEqualTo("1 minute");
        assertThat(TimeFormatUtil.formatDuration(Duration.ofMillis(59_999), Locale.ENGLISH)).isEqualTo("1 minute");
        assertThat(TimeFormatUtil.formatDuration(Duration.ofMillis(60_500), Locale.ENGLISH)).isEqualTo("2 minutes");
        assertThat(TimeFormatUtil.formatDuration(Duration.ofMillis(3_599_500), Locale.ENGLISH)).isEqualTo("1 hour");
    }

    @Test
    void shouldReadNoDelayAsZeroMinutes() {
        assertThat(TimeFormatUtil.formatDuration(Duration.ZERO, Locale.ENGLISH)).isEqualTo("0 minutes");
    }

    @Test
    void shouldFormatDelaysTooLongToBeCountedInNanoseconds() {
        assertThat(TimeFormatUtil.formatDuration(Duration.ofDays(200_000).plusMillis(500), Locale.ENGLISH)).isEqualTo("200,000 days");
    }
}
