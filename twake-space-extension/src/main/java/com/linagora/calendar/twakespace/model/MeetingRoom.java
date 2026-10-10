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

import java.net.URI;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

// A card names the room, not the link: the feed builds the link from its own Meet URL.
public final class MeetingRoom {
    private MeetingRoom() {
    }

    public static Optional<String> of(String videoconference, URI meet) {
        try {
            URI link = URI.create(videoconference.trim());
            return Optional.ofNullable(link.getPath())
                .filter(path -> StringUtils.equalsIgnoreCase(link.getHost(), meet.getHost()))
                .map(path -> StringUtils.strip(path, "/"))
                .filter(room -> !room.isEmpty() && !room.contains("/"));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
