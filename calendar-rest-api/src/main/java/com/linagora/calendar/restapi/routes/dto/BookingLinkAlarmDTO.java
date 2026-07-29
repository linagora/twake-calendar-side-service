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

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.storage.booking.BookingLinkAlarm;
import com.linagora.calendar.storage.booking.BookingLinkAlarmAction;

/**
 * Wire representation of a booking link alarm: {@code {"period": "-PT10M", "action": "EMAIL"}}.
 */
public record BookingLinkAlarmDTO(@JsonProperty("period") String period,
                                  @JsonProperty("action") String action) {

    public BookingLinkAlarm toBookingLinkAlarm() {
        return new BookingLinkAlarm(period, BookingLinkAlarmAction.fromString(action));
    }

    public static BookingLinkAlarmDTO from(BookingLinkAlarm alarm) {
        return new BookingLinkAlarmDTO(alarm.period(), alarm.action().value());
    }

    public static List<BookingLinkAlarm> toBookingLinkAlarms(Optional<List<BookingLinkAlarmDTO>> alarms) {
        return alarms.stream()
            .flatMap(List::stream)
            .map(BookingLinkAlarmDTO::toBookingLinkAlarm)
            .toList();
    }

    public static List<BookingLinkAlarm> toBookingLinkAlarms(List<BookingLinkAlarmDTO> alarms) {
        return alarms.stream()
            .map(BookingLinkAlarmDTO::toBookingLinkAlarm)
            .toList();
    }

    public static Optional<List<BookingLinkAlarmDTO>> from(List<BookingLinkAlarm> alarms) {
        return Optional.of(alarms)
            .filter(list -> !list.isEmpty())
            .map(list -> list.stream().map(BookingLinkAlarmDTO::from).toList());
    }
}
