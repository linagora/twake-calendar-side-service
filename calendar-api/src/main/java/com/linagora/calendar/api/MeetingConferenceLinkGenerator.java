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

package com.linagora.calendar.api;

import java.net.URL;
import java.util.Optional;

import org.apache.james.core.MailAddress;

import reactor.core.publisher.Mono;

/**
 * Where the meeting link written into {@code X-OPENPAAS-VIDEOCONFERENCE} comes from — the
 * videoconference route, booking links and TwakeSpace scheduling.
 */
public interface MeetingConferenceLinkGenerator {
    Mono<URL> generate(MailAddress organizer);

    /** Room name used in activity feeds, when the provider recognizes the link. */
    default Optional<String> roomName(String videoconference) {
        return Optional.empty();
    }
}
