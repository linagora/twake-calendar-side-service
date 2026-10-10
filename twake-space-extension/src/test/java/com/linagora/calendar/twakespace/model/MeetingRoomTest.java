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

package com.linagora.calendar.twakespace.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MeetingRoomTest {
    private static final URI MEET = URI.create("https://meet.space.tld");

    @ParameterizedTest
    @ValueSource(strings = {"https://meet.space.tld/abc-defg-hij", "https://MEET.space.tld/abc-defg-hij/", " https://meet.space.tld/abc-defg-hij "})
    void ofShouldReadTheRoomOfALinkToTheConfiguredMeet(String link) {
        assertThat(MeetingRoom.of(link, MEET)).contains("abc-defg-hij");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://visio.other.tld/abc-defg-hij", "https://meet.space.tld/", "https://meet.space.tld/api/abc-defg-hij",
        "not a link", ""})
    void ofShouldBeEmptyForAnyOtherLink(String link) {
        assertThat(MeetingRoom.of(link, MEET)).isEmpty();
    }
}
