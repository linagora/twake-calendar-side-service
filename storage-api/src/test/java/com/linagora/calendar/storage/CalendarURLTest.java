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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarURLTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "base1/calendar1",
        "/base1/calendar1",
        "/calendars/base1/calendar1",
        "/calendars/base1/calendar1/",
        "/calendars/base1/calendar1/3423434.ics"
    })
    void parseShouldAcceptVariousFormats(String input) {
        assertThat(CalendarURL.parse(input))
            .isEqualTo(new CalendarURL(new OpenPaaSId("base1"), new OpenPaaSId("calendar1")));
    }

    @Test
    void parseShouldRejectInvalidFormat() {
        assertThatThrownBy(() -> CalendarURL.parse("baseOnly"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseShouldRejectEmptyInput() {
        assertThatThrownBy(() -> CalendarURL.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}