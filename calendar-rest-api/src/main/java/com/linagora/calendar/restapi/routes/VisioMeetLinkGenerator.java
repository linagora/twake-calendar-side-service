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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;

import com.linagora.calendar.api.MeetingConferenceLinkGenerator;
import com.linagora.calendar.restapi.RestApiConfiguration;

import reactor.core.publisher.Mono;

/** Invents a room code and hangs it off the configured visio base URL. The default. */
public class VisioMeetLinkGenerator implements MeetingConferenceLinkGenerator {
    private static final String ROOM_CODE_SEPARATOR = "-";
    private static final int[] ROOM_CODE_SEGMENT_LENGTHS = { 3, 4, 3 };

    private final RestApiConfiguration configuration;
    private final Supplier<String> roomCodeSupplier;

    @Inject
    public VisioMeetLinkGenerator(RestApiConfiguration configuration) {
        this(configuration, VisioMeetLinkGenerator::generateRoomCode);
    }

    VisioMeetLinkGenerator(RestApiConfiguration configuration, Supplier<String> roomCodeSupplier) {
        this.configuration = configuration;
        this.roomCodeSupplier = roomCodeSupplier;
    }

    @Override
    public Mono<URL> generate(MailAddress organizer) {
        return Mono.fromCallable(() -> {
            try {
                return URI.create(Strings.CS.appendIfMissing(configuration.getVisioURL().toString(), "/") + roomCodeSupplier.get())
                    .toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Invalid visio URL", e);
            }
        });
    }

    private static String generateRoomCode() {
        return Arrays.stream(ROOM_CODE_SEGMENT_LENGTHS)
            .mapToObj(length -> RandomStringUtils.secure().nextAlphabetic(length).toLowerCase(Locale.US))
            .collect(Collectors.joining(ROOM_CODE_SEPARATOR));
    }
}
