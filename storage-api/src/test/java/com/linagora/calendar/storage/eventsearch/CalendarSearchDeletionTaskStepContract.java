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
import java.util.Optional;

import org.apache.james.core.Username;
import org.apache.james.vacation.api.AccountId;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

import reactor.core.publisher.Mono;

public interface CalendarSearchDeletionTaskStepContract {

    CalendarSearchService calendarSearchService();

    CalendarSearchDeletionTaskStep testee();

    @Test
    default void deleteUserDataShouldRemoveAllEventsForAccount() {
        Username username = Username.of("user@domain.tld");
        AccountId accountId = AccountId.fromUsername(username);

        // Index a sample event
        EventFields event = EventFields.builder()
            .uid(new EventUid("event-1"))
            .summary("Event1")
            .calendarURL(new CalendarURL(new OpenPaaSId("base-id-1"), new OpenPaaSId("calendar-id-1")))
            .build();
        indexEvents(accountId, event);

        // Delete user data
        Mono.from(testee().deleteUserData(username)).block();

        Awaitility.await().atMost(Durations.TEN_SECONDS).untilAsserted(() -> {
            List<EventFields> events = calendarSearchService().search(accountId,
                    new EventSearchQuery("", Optional.empty(), Optional.empty(), Optional.empty(), 100, 0))
                .collectList()
                .block();
            assertThat(events).isEmpty();
        });
    }

    @Test
    default void deleteUserDataShouldNotAffectOtherAccounts() {
        Username username1 = Username.of("user@domain.tld");
        Username username2 = Username.of("user2@domain.tld");
        AccountId accountId1 = AccountId.fromUsername(username1);
        AccountId accountId2 = AccountId.fromUsername(username2);

        // Index events for both accounts
        EventFields event1 = EventFields.builder()
            .uid(new EventUid("event-1"))
            .summary("Event1")
            .calendarURL(new CalendarURL(new OpenPaaSId("base-id-1"), new OpenPaaSId("calendar-id-1")))
            .build();
        EventFields event2 = EventFields.builder()
            .uid(new EventUid("event-2"))
            .summary("Event2")
            .calendarURL(new CalendarURL(new OpenPaaSId("base-id-2"), new OpenPaaSId("calendar-id-2")))
            .build();
        indexEvents(accountId1, event1);
        indexEvents(accountId2, event2);

        // Delete user1 data
        Mono.from(testee().deleteUserData(username1)).block();

        Awaitility.await().atMost(Durations.TEN_SECONDS).untilAsserted(() -> {
            assertThat(calendarSearchService().search(accountId1,
                    new EventSearchQuery("", Optional.empty(), Optional.empty(), Optional.empty(), 100, 0))
                .collectList()
                .block())
                .isEmpty();
            assertThat(calendarSearchService().search(accountId2,
                    new EventSearchQuery("", Optional.empty(), Optional.empty(), Optional.empty(), 100, 0))
                .collectList()
                .block())
                .isNotEmpty();
        });
    }

    private void indexEvents(AccountId accountId, EventFields events) {
        calendarSearchService().index(accountId, CalendarEvents.of(events)).block();

        Awaitility.await().atMost(Durations.TEN_SECONDS).untilAsserted(() ->
            assertThat(calendarSearchService().search(accountId,
                    new EventSearchQuery(events.summary(), Optional.empty(), Optional.empty(), Optional.empty(), 100, 0))
                .collectList()
                .block())
                .isNotEmpty());
    }
}
