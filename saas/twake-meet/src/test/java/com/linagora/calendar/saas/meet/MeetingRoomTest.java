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

package com.linagora.calendar.saas.meet;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MeetingRoomTest {
    private static final MeetConfiguration CONFIGURATION = new MeetConfiguration("client", "secret",
        URI.create("https://meet.space.tld"), false, Duration.ofSeconds(5), Optional.empty());

    private final TwakeMeetLinkGenerator generator = new TwakeMeetLinkGenerator(new MeetApplicationClient(CONFIGURATION), CONFIGURATION);

    @ParameterizedTest
    @ValueSource(strings = {"https://meet.space.tld/abc-defg-hij", "https://MEET.space.tld/abc-defg-hij/", " https://meet.space.tld/abc-defg-hij "})
    void roomNameShouldReadTheRoomOfALinkToTheConfiguredMeet(String link) {
        assertThat(generator.roomName(link)).contains("abc-defg-hij");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://visio.other.tld/abc-defg-hij", "https://meet.space.tld/", "https://meet.space.tld/api/abc-defg-hij",
        "not a link", ""})
    void roomNameShouldBeEmptyForAnyOtherLink(String link) {
        assertThat(generator.roomName(link)).isEmpty();
    }
}
