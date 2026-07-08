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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse.CalendarInvite;

class CalendarDetailsResponseTest {

    @Test
    void parseShouldExtractInvites() {
        CalendarDetailsResponse response = CalendarDetailsResponse.parse("""
            {
              "invite": [
                {
                  "href": "principals/team-calendars/team-calendar-id",
                  "principal": "principals/team-calendars/team-calendar-id",
                  "access": 1
                },
                {
                  "href": "mailto:alice@linagora.com",
                  "principal": "principals/users/alice-id",
                  "access": 2
                },
                {
                  "href": "mailto:bob@linagora.com",
                  "principal": "principals/users/bob-id",
                  "access": 3
                }
              ]
            }
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(response.invites()).containsExactly(
            new CalendarInvite("principals/team-calendars/team-calendar-id", Optional.of("principals/team-calendars/team-calendar-id"), Optional.of(1)),
            new CalendarInvite("mailto:alice@linagora.com", Optional.of("principals/users/alice-id"), Optional.of(2)),
            new CalendarInvite("mailto:bob@linagora.com", Optional.of("principals/users/bob-id"), Optional.of(3)));
    }

    @Test
    void parseShouldReturnEmptyWhenInviteIsMissing() {
        CalendarDetailsResponse response = CalendarDetailsResponse.parse("""
            {"dav:name":"Team calendar"}
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(response.invites()).isEmpty();
    }

    @Test
    void parseShouldReturnEmptyWhenInviteIsNotAnArray() {
        CalendarDetailsResponse response = CalendarDetailsResponse.parse("""
            {"invite":{}}
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(response.invites()).isEmpty();
    }

    @Test
    void parseShouldThrowWhenPayloadIsInvalid() {
        assertThatThrownBy(() -> CalendarDetailsResponse.parse("invalid".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(DavClientException.class)
            .hasMessage("Failed to parse calendar details response");
    }
}
