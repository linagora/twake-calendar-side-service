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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.james.core.Username;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.EventFields;

import reactor.core.publisher.Mono;

public interface CalendarSearchDeletionTaskStepContract {

    CalendarSearchService calendarSearchService();

    OpenPaaSUserDAO userDAO();

    CalendarSearchDeletionTaskStep testee();

    @Test
    default void deleteUserDataShouldRemoveAllEventsForUserBaseCalendarId() {
        Username username = Username.of("user@domain.tld");
        OpenPaaSUser user = userDAO().add(username).block();
        CalendarURL calendarURL = new CalendarURL(user.id(), new OpenPaaSId("calendar-id-1"));

        EventFields event = EventFields.builder()
            .uid(new EventUid("event-1"))
            .summary("Event1")
            .calendarURL(calendarURL)
            .build();
        indexEvents(event);

        Mono.from(testee().deleteUserData(username)).block();

        Awaitility.await().atMost(Durations.TEN_SECONDS).untilAsserted(() -> {
            List<EventFields> events = calendarSearchService().search(EventSearchQuery.builder()
                    .query("")
                    .calendars(calendarURL)
                    .build())
                .collectList()
                .block();
            assertThat(events).isEmpty();
        });
    }

    @Test
    default void deleteUserDataShouldNotAffectOtherUserBaseCalendarIds() {
        Username username1 = Username.of("user@domain.tld");
        Username username2 = Username.of("user2@domain.tld");
        OpenPaaSUser user1 = userDAO().add(username1).block();
        OpenPaaSUser user2 = userDAO().add(username2).block();
        CalendarURL calendarURL1 = new CalendarURL(user1.id(), new OpenPaaSId("calendar-id-1"));
        CalendarURL calendarURL2 = new CalendarURL(user2.id(), new OpenPaaSId("calendar-id-2"));

        EventFields event1 = EventFields.builder()
            .uid(new EventUid("event-1"))
            .summary("Event1")
            .calendarURL(calendarURL1)
            .build();
        EventFields event2 = EventFields.builder()
            .uid(new EventUid("event-2"))
            .summary("Event2")
            .calendarURL(calendarURL2)
            .build();
        indexEvents(event1);
        indexEvents(event2);

        Mono.from(testee().deleteUserData(username1)).block();

        Awaitility.await().atMost(Durations.TEN_SECONDS).untilAsserted(() -> {
            assertThat(calendarSearchService().search(EventSearchQuery.builder()
                    .query("")
                    .calendars(calendarURL1)
                    .build())
                .collectList()
                .block())
                .isEmpty();
            assertThat(calendarSearchService().search(EventSearchQuery.builder()
                    .query("")
                    .calendars(calendarURL2)
                    .build())
                .collectList()
                .block())
                .containsExactly(event2);
        });
    }

    private void indexEvents(EventFields events) {
        calendarSearchService().index(CalendarEvents.of(events)).block();

        Awaitility.await().atMost(Durations.TEN_SECONDS).untilAsserted(() ->
            assertThat(calendarSearchService().search(EventSearchQuery.builder()
                    .query(events.summary())
                    .calendars(events.calendarURL())
                    .build())
                .collectList()
                .block())
                .isNotEmpty());
    }
}
