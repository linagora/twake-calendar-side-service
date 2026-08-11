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

import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * Parsed view of a public agenda booking that its owner has just declined through the "No" link of the proposal email.
 *
 * <p>Only an event created through a booking link ({@code X-PUBLICLY-CREATED}) names a booker
 * ({@code X-PUBLICLY-CREATOR}) to notify: declining any other event yields an empty optional.
 */
public record BookedEventDeclined(Username owner,
                                  EventFields.Person organizer,
                                  EventFields.Person booker,
                                  VEvent event) {
    private static final String X_PUBLICLY_CREATED = "X-PUBLICLY-CREATED";
    private static final String X_PUBLICLY_CREATOR = "X-PUBLICLY-CREATOR";

    public static Optional<BookedEventDeclined> from(Username owner, Calendar calendarData) {
        VEvent vEvent = EventParseUtils.getFirstEvent(calendarData);
        if (!publiclyCreated(vEvent)) {
            return Optional.empty();
        }
        return resolveBooker(vEvent)
            .map(booker -> new BookedEventDeclined(owner, resolveOrganizer(vEvent, owner), booker, vEvent));
    }

    private static boolean publiclyCreated(VEvent vEvent) {
        return EventParseUtils.getPropertyValueIgnoreCase(vEvent, X_PUBLICLY_CREATED)
            .map(BooleanUtils::toBoolean)
            .orElse(false);
    }

    private static Optional<EventFields.Person> resolveBooker(VEvent vEvent) {
        return EventParseUtils.getPropertyValueIgnoreCase(vEvent, X_PUBLICLY_CREATOR)
            .flatMap(bookerEmail -> EventParseUtils.getAttendees(vEvent).stream()
                .filter(attendee -> Strings.CI.equals(attendee.email().asString(), bookerEmail))
                .findFirst()
                .or(() -> toPerson(bookerEmail)));
    }

    private static EventFields.Person resolveOrganizer(VEvent vEvent, Username owner) {
        return EventParseUtils.getOrganizer(vEvent)
            .orElseGet(Throwing.supplier(() -> new EventFields.Person(StringUtils.EMPTY, owner.asMailAddress())));
    }

    private static Optional<EventFields.Person> toPerson(String email) {
        try {
            return Optional.of(EventFields.Person.of(StringUtils.EMPTY, email));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
