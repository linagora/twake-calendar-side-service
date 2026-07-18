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

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.Strings;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * Parsed view of a booked event that has just been cancelled by its booker.
 *
 * <p>All the data needed to notify the organizer (calendar owner) is extracted from the ICS that was
 * fetched from the CalDAV server before the cancellation update, since the cancellation token only carries identifiers.
 */
public record BookedEventCancelled(OpenPaaSUser organizer,
                                   EventFields.Person organizerPerson,
                                   EventFields.Person cancelledBy,
                                   List<EventFields.Person> attendees,
                                   String summary,
                                   Instant start,
                                   Instant end,
                                   Optional<String> description) {
    private static final String X_PUBLICLY_CREATOR = "X-PUBLICLY-CREATOR";
    private static final String X_PUBLICLY_CANCELLED_BY = "X-PUBLICLY-CANCELLED-BY";

    public static BookedEventCancelled from(OpenPaaSUser organizer, Calendar calendarData) {
        VEvent vEvent = EventParseUtils.getFirstEvent(calendarData);

        EventFields.Person organizerPerson = EventParseUtils.getOrganizer(vEvent)
            .orElseGet(Throwing.supplier(() -> new EventFields.Person(organizer.fullName(), organizer.username().asMailAddress())));
        EventFields.Person cancelledBy = resolveCancelledBy(vEvent, organizerPerson);

        Instant start = EventParseUtils.getStartTime(vEvent).toInstant();
        Instant end = EventParseUtils.getEndTime(vEvent).map(ZonedDateTime::toInstant).orElse(start);

        return new BookedEventCancelled(organizer,
            organizerPerson,
            cancelledBy,
            EventParseUtils.getAttendees(vEvent),
            EventParseUtils.getSummary(vEvent).orElse(""),
            start,
            end,
            EventParseUtils.getDescription(vEvent));
    }

    private static EventFields.Person resolveCancelledBy(VEvent vEvent, EventFields.Person fallback) {
        return EventParseUtils.getPropertyValueIgnoreCase(vEvent, X_PUBLICLY_CANCELLED_BY)
            .or(() -> EventParseUtils.getPropertyValueIgnoreCase(vEvent, X_PUBLICLY_CREATOR))
            .flatMap(creatorEmail -> EventParseUtils.getAttendees(vEvent).stream()
                .filter(attendee -> Strings.CI.equals(attendee.email().asString(), creatorEmail))
                .findFirst()
                .or(() -> toPerson(creatorEmail)))
            .orElse(fallback);
    }

    private static Optional<EventFields.Person> toPerson(String email) {
        try {
            return Optional.of(EventFields.Person.of("", email));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
