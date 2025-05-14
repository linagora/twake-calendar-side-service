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

package com.linagora.calendar.storage.eventsearch;

import java.util.Set;

import org.apache.commons.lang3.Validate;

import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.storage.CalendarURL;

public record CalendarEvents(EventUid eventUid,
                             CalendarURL calendarURL,
                             Set<EventFields> events) {

    public static CalendarEvents of(EventFields... eventFields) {
        EventUid uid = eventFields[0].uid();
        CalendarURL calendarURL = eventFields[0].calendarURL();
        return new CalendarEvents(uid, calendarURL, ImmutableSet.copyOf(eventFields));
    }

    public static CalendarEvents of(Set<EventFields> eventFields) {
        EventFields sample = eventFields.iterator().next();
        return new CalendarEvents(sample.uid(), sample.calendarURL(), ImmutableSet.copyOf(eventFields));
    }

    public CalendarEvents {
        Validate.notNull(eventUid, "eventUid must not be null");
        Validate.notNull(calendarURL, "calendarURL must not be null");
        Validate.notEmpty(events, "events must not be null or empty");
        Validate.isTrue(events.stream().allMatch(e -> e.uid().equals(eventUid)), "All EventFields must have the same EventUid");
        Validate.isTrue(events.stream().allMatch(e -> e.calendarURL().equals(calendarURL)), "All EventFields must have the same CalendarURL");
    }
}
