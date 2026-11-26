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

import java.net.URI;
import java.net.URL;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.linagora.calendar.api.MeetingConferenceLinkGenerator;

import reactor.core.publisher.Mono;

/** Creates the room in the organizer's name and returns the URL Meet answers with. */
public class TwakeMeetLinkGenerator implements MeetingConferenceLinkGenerator {
    private final MeetApplicationClient client;
    private final URI baseUrl;

    @Inject
    public TwakeMeetLinkGenerator(MeetApplicationClient client, MeetConfiguration configuration) {
        this.client = client;
        this.baseUrl = configuration.externalApiBaseUrl();
    }

    @Override
    public Mono<URL> generate(MailAddress organizer) {
        return client.fetchToken(organizer)
            .flatMap(client::createRoom)
            .map(MeetApplicationClient.Room::url);
    }

    // The feed builds the URL from its own Meet configuration, so only a room name is published.
    @Override
    public Optional<String> roomName(String videoconference) {
        try {
            URI link = URI.create(videoconference.trim());
            return Optional.ofNullable(link.getPath())
                .filter(path -> StringUtils.equalsIgnoreCase(link.getHost(), baseUrl.getHost()))
                .map(path -> StringUtils.strip(path, "/"))
                .filter(room -> !room.isEmpty() && !room.contains("/"));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
