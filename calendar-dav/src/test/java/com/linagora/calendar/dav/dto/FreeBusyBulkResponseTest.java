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

package com.linagora.calendar.dav.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.dto.FreeBusyBulkResponse.BusyInterval;

class FreeBusyBulkResponseTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void anchorAllDayInShouldMoveWholeUtcDaysToTheMidnightsOfTheZone() {
        BusyInterval allDay = new BusyInterval(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z"));

        assertThat(allDay.anchorAllDayIn(PARIS))
            .isEqualTo(new BusyInterval(Instant.parse("2026-10-18T22:00:00Z"), Instant.parse("2026-10-21T22:00:00Z")));
    }

    @Test
    void anchorAllDayInShouldApplyTheOffsetOfEachMidnight() {
        // Europe/Paris leaves summer time on 2026-10-25
        BusyInterval allDay = new BusyInterval(Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-27T00:00:00Z"));

        assertThat(allDay.anchorAllDayIn(PARIS))
            .isEqualTo(new BusyInterval(Instant.parse("2026-10-23T22:00:00Z"), Instant.parse("2026-10-26T23:00:00Z")));
    }

    @Test
    void anchorAllDayInShouldSupportZonesWestOfUtc() {
        BusyInterval allDay = new BusyInterval(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"));

        assertThat(allDay.anchorAllDayIn(NEW_YORK))
            .isEqualTo(new BusyInterval(Instant.parse("2026-10-19T04:00:00Z"), Instant.parse("2026-10-20T04:00:00Z")));
    }

    @Test
    void anchorAllDayInShouldBeANoopInUtc() {
        BusyInterval allDay = new BusyInterval(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"));

        assertThat(allDay.anchorAllDayIn(ZoneOffset.UTC)).isEqualTo(allDay);
    }

    @Test
    void anchorAllDayInShouldNotMoveTimedIntervals() {
        BusyInterval timed = new BusyInterval(Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-10-19T10:00:00Z"));

        assertThat(timed.anchorAllDayIn(PARIS)).isEqualTo(timed);
    }

    @Test
    void anchorAllDayInShouldNotMoveIntervalsStartingAtUtcMidnightOnly() {
        BusyInterval timed = new BusyInterval(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-19T01:00:00Z"));

        assertThat(timed.anchorAllDayIn(PARIS)).isEqualTo(timed);
    }

    @Test
    void anchorAllDayInShouldNotMoveEmptyIntervals() {
        BusyInterval empty = new BusyInterval(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"));

        assertThat(empty.anchorAllDayIn(PARIS)).isEqualTo(empty);
    }
}
