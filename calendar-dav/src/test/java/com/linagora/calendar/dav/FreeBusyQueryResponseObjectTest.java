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

package com.linagora.calendar.dav;

import static com.linagora.calendar.dav.FreeBusyQueryResponseObject.BusyInterval;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class FreeBusyQueryResponseObjectTest {

    @Test
    void busyIntervalsShouldParseAbsolutePeriods() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:20250110T100000Z/20250110T103000Z
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .containsExactly(new BusyInterval(Instant.parse("2025-01-10T10:00:00Z"), Instant.parse("2025-01-10T10:30:00Z")));
    }

    @Test
    void busyIntervalsShouldParseDurationPeriods() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:20250110T100000Z/PT30M
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .containsExactly(new BusyInterval(Instant.parse("2025-01-10T10:00:00Z"), Instant.parse("2025-01-10T10:30:00Z")));
    }

    @Test
    void busyIntervalsShouldParseListOfPeriods() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:20250110T100000Z/20250110T103000Z,20250110T110000Z/PT15M
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .containsExactly(new BusyInterval(Instant.parse("2025-01-10T10:00:00Z"), Instant.parse("2025-01-10T10:30:00Z")),
                new BusyInterval(Instant.parse("2025-01-10T11:00:00Z"), Instant.parse("2025-01-10T11:15:00Z")));
    }

    @Test
    void busyIntervalsShouldParseFoldedFreeBusyLine() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:20250110T100000Z/20250110T103000Z,
             20250110T110000Z/PT15M
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .containsExactly(new BusyInterval(
                    Instant.parse("2025-01-10T10:00:00Z"),
                    Instant.parse("2025-01-10T10:30:00Z")),
                new BusyInterval(
                    Instant.parse("2025-01-10T11:00:00Z"),
                    Instant.parse("2025-01-10T11:15:00Z")));
    }

    @Test
    void busyIntervalsShouldReturnIntervalsAsReturnedByFreeBusy() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:20250110T093000Z/20250110T103000Z
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .containsExactly(new BusyInterval(Instant.parse("2025-01-10T09:30:00Z"), Instant.parse("2025-01-10T10:30:00Z")));
    }

    @Test
    void busyIntervalsShouldThrowWhenFreeBusyPeriodIsInvalid() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:invalid-period
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(testee::busyIntervals)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid FREEBUSY value: invalid-period");
    }


    @Test
    void busyIntervalsShouldParseMultipleFreeBusyProperties() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:20250110T100000Z/20250110T103000Z
            FREEBUSY:20250110T110000Z/20250110T113000Z
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .containsExactly(
                new BusyInterval(Instant.parse("2025-01-10T10:00:00Z"), Instant.parse("2025-01-10T10:30:00Z")),
                new BusyInterval(Instant.parse("2025-01-10T11:00:00Z"), Instant.parse("2025-01-10T11:30:00Z")));
    }

    @Test
    void busyIntervalsShouldReturnEmptyWhenFreeBusyIsEmpty() {
        FreeBusyQueryResponseObject testee = new FreeBusyQueryResponseObject("""
            BEGIN:VCALENDAR
            BEGIN:VFREEBUSY
            FREEBUSY:
            END:VFREEBUSY
            END:VCALENDAR
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(testee.busyIntervals())
            .isEmpty();
    }
}
