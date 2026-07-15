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
 * fetched from the CalDAV server before deletion, since the cancellation token only carries identifiers.
 */
public record BookedEventCancelled(OpenPaaSUser organizer,
                                   EventFields.Person organizerPerson,
                                   List<EventFields.Person> attendees,
                                   String summary,
                                   Instant start,
                                   Instant end,
                                   Optional<String> description) {

    public static BookedEventCancelled from(OpenPaaSUser organizer, Calendar calendarData) {
        VEvent vEvent = EventParseUtils.getFirstEvent(calendarData);

        EventFields.Person organizerPerson = EventParseUtils.getOrganizer(vEvent)
            .orElseGet(Throwing.supplier(() -> new EventFields.Person(organizer.fullName(), organizer.username().asMailAddress())));

        Instant start = EventParseUtils.getStartTime(vEvent).toInstant();
        Instant end = EventParseUtils.getEndTime(vEvent).map(ZonedDateTime::toInstant).orElse(start);

        return new BookedEventCancelled(organizer,
            organizerPerson,
            EventParseUtils.getAttendees(vEvent),
            EventParseUtils.getSummary(vEvent).orElse(""),
            start,
            end,
            EventParseUtils.getDescription(vEvent));
    }
}
