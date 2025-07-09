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

package com.linagora.calendar.smtp.template.content.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class EventTimeModelTest {

    @Test
    void shouldConvertAndFormatToVietnamTimeZone() {
        ZonedDateTime eventTime = ZonedDateTime.parse("2025-07-07T07:00:00+02:00[Europe/Paris]");
        EventTimeModel model = new EventTimeModel(eventTime);

        Map<String, Object> pugModel = model.toPugModel(Locale.ENGLISH, ZoneId.of("Asia/Ho_Chi_Minh"));

        assertThat(pugModel.get("date")).isEqualTo("2025-07-07");
        assertThat(pugModel.get("time")).isEqualTo("12:00");
        assertThat(pugModel.get("fullDate")).isEqualTo("Monday, 07 July 2025");
        assertThat(pugModel.get("fullDateTime")).isEqualTo("Monday, 07 July 2025 12:00");
        assertThat(pugModel.get("timezone")).isEqualTo("Asia/Ho_Chi_Minh");
    }

    @Test
    void shouldFormatInFrenchLocale() {
        ZonedDateTime eventTime = ZonedDateTime.parse("2025-12-25T09:30:00+01:00[Europe/Paris]");
        EventTimeModel model = new EventTimeModel(eventTime);

        Map<String, Object> pugModel = model.toPugModel(Locale.FRENCH, ZoneId.of("Europe/Paris"));

        assertThat(pugModel.get("fullDate")).isEqualTo("jeudi, 25 décembre 2025");
        assertThat(pugModel.get("fullDateTime")).isEqualTo("jeudi, 25 décembre 2025 09:30");
    }
}
