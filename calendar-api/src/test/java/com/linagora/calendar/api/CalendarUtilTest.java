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

package com.linagora.calendar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;

class CalendarUtilTest {

    @Test
    void parseIcsShouldNotThrowWhenVTimezoneHasNoObservance() {
        // Reproduces https://github.com/linagora/twake-calendar-side-service/issues/896
        // Sabre may generate a VTIMEZONE without any STANDARD/DAYLIGHT observance which makes
        // ical4j fail with a NullPointerException on Observance.getRequiredProperty(...).
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            BEGIN:VTIMEZONE
            TZID:Europe/Paris
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:87e925b3-a715-4930-8a81-f8f423672c00
            TRANSP:OPAQUE
            DTSTART;TZID=Europe/Paris:20250714T073000
            CLASS:PUBLIC
            SUMMARY:testFromNewfront
            DTEND;TZID=Europe/Paris:20250714T073000
            DTSTAMP:20250717T140746Z
            END:VEVENT
            END:VCALENDAR
            """.replace("\n", "\r\n");

        assertThatCode(() -> CalendarUtil.parseIcs(ics)).doesNotThrowAnyException();
    }

    @Test
    void parseIcsShouldKeepVEventWhenVTimezoneHasNoObservance() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            BEGIN:VTIMEZONE
            TZID:Europe/Paris
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:87e925b3-a715-4930-8a81-f8f423672c00
            DTSTART;TZID=Europe/Paris:20250714T073000
            SUMMARY:testFromNewfront
            DTEND;TZID=Europe/Paris:20250714T073000
            DTSTAMP:20250717T140746Z
            END:VEVENT
            END:VCALENDAR
            """.replace("\n", "\r\n");

        Calendar calendar = CalendarUtil.parseIcs(ics);

        assertThat(calendar.getComponents(Component.VEVENT)).hasSize(1);
    }

    @Test
    void parseIcsShouldKeepValidVTimezone() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            BEGIN:VTIMEZONE
            TZID:Asia/Saigon
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:87e925b3-a715-4930-8a81-f8f423672c00
            DTSTART;TZID=Asia/Saigon:20250714T073000
            SUMMARY:testFromNewfront
            DTEND;TZID=Asia/Saigon:20250714T083000
            DTSTAMP:20250717T140746Z
            END:VEVENT
            END:VCALENDAR
            """.replace("\n", "\r\n");

        Calendar calendar = CalendarUtil.parseIcs(ics);

        assertThat(calendar.getComponents(Component.VTIMEZONE)).hasSize(1);
        assertThat(calendar.getComponents(Component.VEVENT)).hasSize(1);
    }
}
