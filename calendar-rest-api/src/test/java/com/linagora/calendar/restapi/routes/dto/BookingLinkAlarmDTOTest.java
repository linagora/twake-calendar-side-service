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

package com.linagora.calendar.restapi.routes.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.booking.BookingLinkAlarm;
import com.linagora.calendar.storage.booking.BookingLinkAlarmAction;

class BookingLinkAlarmDTOTest {

    @Test
    void toBookingLinkAlarmsShouldRejectNullElement() {
        List<BookingLinkAlarmDTO> alarms = new ArrayList<>();
        alarms.add(null);

        assertThatThrownBy(() -> BookingLinkAlarmDTO.toBookingLinkAlarms(Optional.of(alarms)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'alarm' must not contain null");
    }

    @Test
    void toBookingLinkAlarmsShouldRejectNullElementAmongValidOnes() {
        List<BookingLinkAlarmDTO> alarms = new ArrayList<>();
        alarms.add(new BookingLinkAlarmDTO("-PT10M", "EMAIL"));
        alarms.add(null);

        assertThatThrownBy(() -> BookingLinkAlarmDTO.toBookingLinkAlarms(alarms))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'alarm' must not contain null");
    }

    @Test
    void toBookingLinkAlarmsShouldReturnEmptyWhenAbsent() {
        assertThat(BookingLinkAlarmDTO.toBookingLinkAlarms(Optional.empty())).isEmpty();
    }

    @Test
    void toBookingLinkAlarmsShouldConvertValidAlarms() {
        assertThat(BookingLinkAlarmDTO.toBookingLinkAlarms(Optional.of(List.of(new BookingLinkAlarmDTO("-PT10M", "EMAIL")))))
            .containsExactly(new BookingLinkAlarm("-PT10M", BookingLinkAlarmAction.fromString("EMAIL")));
    }
}
