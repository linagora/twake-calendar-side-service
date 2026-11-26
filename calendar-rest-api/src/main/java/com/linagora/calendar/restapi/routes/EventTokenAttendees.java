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

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;

import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarEventModifier;
import com.linagora.calendar.dav.CalendarEventNotFoundException;
import com.linagora.calendar.dav.CalendarEventUpdatePatch.AddAttendeePatch;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.restapi.NotFoundException;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.eventtoken.EventToken;
import com.linagora.calendar.storage.eventtoken.EventTokenDAO;
import com.linagora.calendar.storage.eventtoken.EventTokenId;
import com.linagora.calendar.storage.eventtoken.EventTokenRight;

import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;

/**
 * Adds attendees to the event an event token points to, on behalf of the owner of its calendar home, who organizes
 * it: the DAV server then invites the new attendees.
 */
public class EventTokenAttendees {

    private final EventTokenDAO eventTokenDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavEventRepository calDavEventRepository;

    @Inject
    public EventTokenAttendees(EventTokenDAO eventTokenDAO,
                               OpenPaaSUserDAO openPaaSUserDAO,
                               CalDavEventRepository calDavEventRepository) {
        this.eventTokenDAO = eventTokenDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavEventRepository = calDavEventRepository;
    }

    public Mono<Void> add(EventTokenId id, MailAddress email, Optional<String> displayName, PartStat partStat) {
        return eventTokenDAO.findById(id)
            .switchIfEmpty(Mono.error(NotFoundException::new))
            .filter(eventToken -> eventToken.right() == EventTokenRight.ADD_MYSELF)
            .switchIfEmpty(Mono.error(() -> new ForbiddenException("Event token does not allow to add attendees")))
            .flatMap(eventToken -> openPaaSUserDAO.retrieve(eventToken.calendarURL().base())
                .switchIfEmpty(Mono.error(NotFoundException::new))
                .flatMap(owner -> addAttendee(eventToken, owner, new AddAttendeePatch(email, displayName, partStat))))
            .onErrorMap(CalendarEventNotFoundException.class, e -> new NotFoundException());
    }

    private Mono<Void> addAttendee(EventToken eventToken, OpenPaaSUser owner, AddAttendeePatch patch) {
        CalendarEventModifier modifier = new CalendarEventModifier(List.of(patch), Optional.of(calendar -> {
            // the event may have changed since the token was created
            if (!EventTokenOwnership.isOrganizedBy(calendar, owner)) {
                throw new ForbiddenException("The event is no longer a meeting organized by '%s'".formatted(owner.username().asString()));
            }
        }));
        return calDavEventRepository.updateEvent(owner.username(), EventTokenOwnership.eventHref(eventToken.calendarURL(), eventToken.eventId()), modifier)
            .then();
    }
}
