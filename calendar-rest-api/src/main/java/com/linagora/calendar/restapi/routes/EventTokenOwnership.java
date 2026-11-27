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

import java.net.URI;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.mailbox.MailboxSession;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.smtp.MailUsernames;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventtoken.EventTokenId;

import io.netty.handler.codec.http.QueryStringDecoder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

/**
 * Event tokens are managed by the owner of the calendar home holding the event.
 */
public class EventTokenOwnership {

    public static final String EVENT_TOKEN_PARAM = "eventToken";

    public static URI eventHref(CalendarURL calendarURL, String eventId) {
        return URI.create(calendarURL.asUri() + "/" + eventId + CalDavClient.ICS_EXTENSION);
    }

    public static boolean isOrganizedBy(Calendar calendar, OpenPaaSUser owner) {
        return calendar.<VEvent>getComponents(Component.VEVENT).stream()
            .findFirst()
            .flatMap(EventParseUtils::getOrganizer)
            .flatMap(organizer -> MailUsernames.asUsername(organizer.email()))
            .filter(owner.username()::equals)
            .isPresent();
    }

    public static EventTokenId eventTokenFromQuery(HttpServerRequest request) {
        return new QueryStringDecoder(request.uri()).parameters()
            .getOrDefault(EVENT_TOKEN_PARAM, List.of())
            .stream()
            .filter(StringUtils::isNotBlank)
            .findAny()
            .map(EventTokenId::from)
            .orElseThrow(() -> new IllegalArgumentException("Missing '" + EVENT_TOKEN_PARAM + "' query parameter"));
    }

    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public EventTokenOwnership(OpenPaaSUserDAO openPaaSUserDAO) {
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    public Mono<OpenPaaSUser> assertCalendarHomeOwner(MailboxSession session, CalendarURL calendarURL) {
        return openPaaSUserDAO.retrieve(session.getUser())
            .filter(user -> user.id().equals(calendarURL.base()))
            .switchIfEmpty(Mono.error(() -> new ForbiddenException("Only the owner of calendar home '%s' can manage its event tokens"
                .formatted(calendarURL.base().value()))));
    }
}
