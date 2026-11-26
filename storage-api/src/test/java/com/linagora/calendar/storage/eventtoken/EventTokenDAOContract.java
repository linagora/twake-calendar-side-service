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

package com.linagora.calendar.storage.eventtoken;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

public interface EventTokenDAOContract {

    CalendarURL CALENDAR_URL = new CalendarURL(new OpenPaaSId("5f50a663bdaffe002629099c"), new OpenPaaSId("5f50a663bdaffe002629099c"));
    CalendarURL OTHER_CALENDAR_URL = new CalendarURL(new OpenPaaSId("5f50a663bdaffe002629099c"), new OpenPaaSId("6a1b2c3d4e5f600000000001"));
    String EVENT_ID = "sabredav-bbc9219b-e21b-4797-b1fb-7480bb1c80fc";
    String OTHER_EVENT_ID = "sabredav-0d6a7b7e-3a2f-4c4e-9d0e-2f1b9a5c6d7e";

    EventTokenDAO testee();

    UpdatableTickingClock clock();

    default EventToken eventToken(CalendarURL calendarURL, String eventId, Duration validity) {
        return EventToken.builder()
            .id(EventTokenId.generate())
            .calendarURL(calendarURL)
            .eventId(eventId)
            .right(EventTokenRight.VIEW)
            .expiration(clock().instant().plus(validity))
            .build();
    }

    @Test
    default void findByIdShouldReturnInsertedToken() {
        // Given a token valid for one day was stored
        EventToken eventToken = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofDays(1));
        testee().insert(eventToken).block();

        // When it is looked up by its id
        EventToken found = testee().findById(eventToken.id()).block();

        // Then every field of the stored token is returned
        assertThat(found).isEqualTo(eventToken);
    }

    @Test
    default void findByIdShouldReturnEmptyWhenTokenIsUnknown() {
        // Given no token was stored

        // When an arbitrary id is looked up
        // Then nothing is returned
        assertThat(testee().findById(EventTokenId.generate()).blockOptional()).isEmpty();
    }

    @Test
    default void findByIdShouldReturnEmptyOnceTokenIsExpired() {
        // Given a token valid for one day was stored
        EventToken eventToken = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofDays(1));
        testee().insert(eventToken).block();

        // When the expiration instant is reached
        clock().setInstant(eventToken.expiration());

        // Then the token can no longer be found
        assertThat(testee().findById(eventToken.id()).blockOptional()).isEmpty();
    }

    @Test
    default void listByEventShouldReturnOnlyValidTokensOfThatEvent() {
        // Given two tokens of the event, one valid for an hour and one for a week,
        // and tokens of another event of the same calendar and of the same event id in another calendar
        EventToken shortLived = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofHours(1));
        EventToken longLived = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofDays(7));
        testee().insert(shortLived).block();
        testee().insert(longLived).block();
        testee().insert(eventToken(CALENDAR_URL, OTHER_EVENT_ID, Duration.ofDays(7))).block();
        testee().insert(eventToken(OTHER_CALENDAR_URL, EVENT_ID, Duration.ofDays(7))).block();

        // When the short-lived one expires and the tokens of the event are listed
        clock().setInstant(clock().instant().plus(Duration.ofHours(2)));

        // Then only the still valid token of that event is returned
        assertThat(testee().listByEvent(CALENDAR_URL, EVENT_ID).collectList().block())
            .containsExactly(longLived);
    }

    @Test
    default void deleteShouldRevokeToken() {
        // Given a token was stored
        EventToken eventToken = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofDays(1));
        testee().insert(eventToken).block();

        // When it is deleted
        testee().delete(eventToken.id()).block();

        // Then it can neither be found nor listed anymore
        assertThat(testee().findById(eventToken.id()).blockOptional()).isEmpty();
        assertThat(testee().listByEvent(CALENDAR_URL, EVENT_ID).collectList().block()).isEmpty();
    }

    @Test
    default void deleteShouldNotAffectOtherTokens() {
        // Given two tokens of the same event were stored
        EventToken deleted = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofDays(1));
        EventToken kept = eventToken(CALENDAR_URL, EVENT_ID, Duration.ofDays(1));
        testee().insert(deleted).block();
        testee().insert(kept).block();

        // When one of them is deleted
        testee().delete(deleted.id()).block();

        // Then the other one is still listed
        assertThat(testee().listByEvent(CALENDAR_URL, EVENT_ID).collectList().block())
            .containsExactly(kept);
    }

    @Test
    default void deleteShouldSucceedWhenTokenIsUnknown() {
        // Given no token was stored

        // When an arbitrary id is deleted
        // Then no error is raised
        testee().delete(EventTokenId.generate()).block();
    }
}
