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

import org.junit.jupiter.api.Test;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;

class CalendarUtilTest {

    private static final String ICS_WITHOUT_METHOD = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Twake Calendar//Public Booking//EN
        BEGIN:VEVENT
        UID:8e4d5652-3525-4511-b660-1873ba3df559
        DTSTAMP:20260707T094407Z
        DTSTART:20260710T150000Z
        SUMMARY:Test
        END:VEVENT
        END:VCALENDAR
        """;

    @Test
    void withMethodShouldAddMethodWhenMissing() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITHOUT_METHOD);

        assertThat(CalendarUtil.withMethod(calendar, ImmutableMethod.REQUEST).toString())
            .contains("METHOD:REQUEST");
    }

    @Test
    void withMethodShouldOverrideExistingMethod() {
        Calendar calendar = CalendarUtil.withMethod(CalendarUtil.parseIcs(ICS_WITHOUT_METHOD), ImmutableMethod.REQUEST);

        assertThat(CalendarUtil.withMethod(calendar, ImmutableMethod.CANCEL).toString())
            .contains("METHOD:CANCEL")
            .doesNotContain("METHOD:REQUEST");
    }

    @Test
    void withMethodShouldNotMutateSuppliedCalendar() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITHOUT_METHOD);

        CalendarUtil.withMethod(calendar, ImmutableMethod.REQUEST);

        assertThat(calendar.toString())
            .doesNotContain("METHOD:");
    }
}
