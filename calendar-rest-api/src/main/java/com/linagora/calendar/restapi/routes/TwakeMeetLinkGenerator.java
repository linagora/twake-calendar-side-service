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

import java.net.URL;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;

import com.linagora.calendar.amqp.meet.MeetApplicationClient;
import com.linagora.calendar.api.MeetingConferenceLinkGenerator;

import reactor.core.publisher.Mono;

/** Creates the room in the organizer's name and returns the URL Meet answers with. */
public class TwakeMeetLinkGenerator implements MeetingConferenceLinkGenerator {
    private final MeetApplicationClient client;

    @Inject
    public TwakeMeetLinkGenerator(MeetApplicationClient client) {
        this.client = client;
    }

    @Override
    public Mono<URL> generate(MailAddress organizer) {
        return client.fetchToken(organizer)
            .flatMap(client::createRoom)
            .map(MeetApplicationClient.Room::url);
    }
}
