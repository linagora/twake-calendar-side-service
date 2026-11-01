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

package com.linagora.calendar.restapi.routes;

import static com.linagora.calendar.restapi.RestApiConstants.OBJECT_MAPPER_DEFAULT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.restapi.routes.BookingLinkCreateRoute.CreateBookingLinkRequestDTO;

class CreateBookingLinkRequestDTOTest {

    @Test
    void createShouldRejectNullAvailabilityRule() throws Exception {
        CreateBookingLinkRequestDTO dto = OBJECT_MAPPER_DEFAULT.readValue("""
            {"calendarUrl":"/calendars/user/cal","durationMinutes":30,"active":true,"availabilityRules":[null]}""",
            CreateBookingLinkRequestDTO.class);

        assertThatThrownBy(() -> CreateBookingLinkRequestDTO.toBookingLinkInsertRequest(dto, ZoneId.of("UTC"), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'availabilityRules' must not contain null");
    }
}
