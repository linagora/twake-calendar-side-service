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

package com.linagora.calendar.restapi;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ImmutableMap;

public class DayOfWeekUtil {

    private static final Map<String, DayOfWeek> ABBREVIATION_TO_DAY = ImmutableMap.<String, DayOfWeek>builder()
        .put("MON", DayOfWeek.MONDAY)
        .put("TUE", DayOfWeek.TUESDAY)
        .put("WED", DayOfWeek.WEDNESDAY)
        .put("THU", DayOfWeek.THURSDAY)
        .put("FRI", DayOfWeek.FRIDAY)
        .put("SAT", DayOfWeek.SATURDAY)
        .put("SUN", DayOfWeek.SUNDAY)
        .build();

    private static final Map<DayOfWeek, String> DAY_TO_ABBREVIATION = ImmutableMap.<DayOfWeek, String>builder()
        .put(DayOfWeek.MONDAY, "MON")
        .put(DayOfWeek.TUESDAY, "TUE")
        .put(DayOfWeek.WEDNESDAY, "WED")
        .put(DayOfWeek.THURSDAY, "THU")
        .put(DayOfWeek.FRIDAY, "FRI")
        .put(DayOfWeek.SATURDAY, "SAT")
        .put(DayOfWeek.SUNDAY, "SUN")
        .build();

    public static DayOfWeek fromAbbreviation(String abbreviation) {
        return Optional.ofNullable(ABBREVIATION_TO_DAY.get(abbreviation.toUpperCase()))
            .orElseThrow(() -> new IllegalArgumentException("Unknown day of week abbreviation: " + abbreviation));
    }

    public static String toAbbreviation(DayOfWeek dayOfWeek) {
        return DAY_TO_ABBREVIATION.get(dayOfWeek);
    }
}
