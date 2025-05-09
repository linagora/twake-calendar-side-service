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

import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.vacation.api.AccountId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.EventFields.Person;

public interface CalendarSearchServiceContract {
    Username username = Username.of("user@domain.tld");
    Username username2 = Username.of("user2@domain.tld");
    AccountId accountId = AccountId.fromUsername(username);
    AccountId accountId2 = AccountId.fromUsername(username2);

    CalendarSearchService testee();

    @Test
    default void indexThenSearchShouldReturnTheEvent() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        EventSearchQuery query = simpleQuery("planning");

        testee().index(accountId, event).block();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults)
            .containsExactly(event.uid());
    }

    @Test
    default void indexMultipleTimesWithSameEventFieldsShouldNotCauseError() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> testee().index(accountId, event).block())
                .doesNotThrowAnyException();
        }

        List<EventUid> searchResults = testee().search(accountId, simpleQuery("planning"))
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).hasSize(1)
            .containsExactly(event.uid());
    }

    @Test
    default void updateShouldNotThrowWhenEventIndexDoesNotExist() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Initial")
            .calendarURL(generateCalendarURL())
            .build();

        assertThatCode(() -> testee().update(accountId, event).block())
            .doesNotThrowAnyException();
    }

    @Test
    default void updateShouldUpdateExistingEvent() {
        EventUid eventUid = generateEventUid();
        CalendarURL calendarURL = generateCalendarURL();
        EventFields original = EventFields.builder()
            .uid(eventUid)
            .title("Old Title")
            .calendarURL(calendarURL)
            .build();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .title("Updated Title")
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, original).block();
        testee().update(accountId, updated).block();

        List<EventFields> result = testee().search(accountId, simpleQuery("Title"))
            .collectList().block();

        assertThat(result).extracting(EventFields::uid).containsExactly(eventUid);
        assertThat(result).extracting(EventFields::title).containsExactly("Updated Title");
    }

    @Test
    default void updateShouldOverrideAllFields() throws Exception {
        EventUid eventUid = generateEventUid();
        CalendarURL calendarURL = generateCalendarURL();
        EventFields initial = EventFields.builder()
            .uid(eventUid)
            .title("Initial title")
            .location("Initial location")
            .description("Initial description")
            .clazz("PRIVATE")
            .start(Instant.parse("2024-01-01T09:00:00Z"))
            .end(Instant.parse("2024-01-01T10:00:00Z"))
            .dtStamp(Instant.parse("2023-12-30T20:00:00Z"))
            .allDay(false)
            .hasResources(false)
            .isRecurrentMaster(false)
            .durationInDays(2)
            .organizer(Person.of("Alice", "alice@domain.tld"))
            .attendees(List.of(Person.of("Bob", "bob@domain.tld")))
            .resources(List.of(Person.of("Whiteboard", "whiteboard@resource.domain")))
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, initial).block();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .title("Updated title")
            .location("Updated location")
            .description("Updated description")
            .clazz("CONFIDENTIAL")
            .start(Instant.parse("2024-02-01T14:00:00Z"))
            .end(Instant.parse("2024-02-01T16:00:00Z"))
            .dtStamp(Instant.parse("2024-01-31T15:00:00Z"))
            .allDay(true)
            .hasResources(true)
            .isRecurrentMaster(true)
            .durationInDays(1)
            .organizer(Person.of("Charlie", "charlie@domain.tld"))
            .attendees(List.of(
                Person.of("Dave", "dave@domain.tld"),
                Person.of("Eve", "eve@domain.tld")))
            .resources(List.of(Person.of("Projector", "projector@resource.domain")))
            .calendarURL(calendarURL)
            .build();

        testee().update(accountId, updated).block();

        EventSearchQuery query = simpleQuery("Updated");
        EventFields result = testee().search(accountId, query)
            .single().block();

        assertThat(result).isEqualTo(updated);
    }

    @Test
    default void updateShouldNotAffectOtherAccounts() {
        EventUid eventUid = generateEventUid();
        EventFields event = EventFields.builder()
            .uid(eventUid)
            .title("Original")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .title("Updated")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId2, event).block();
        testee().update(accountId, updated).block();

        assertThat(testee().search(accountId2, simpleQuery("Original"))
            .collectList().block()).hasSize(1);
        assertThat(testee().search(accountId2, simpleQuery("Updated"))
            .collectList().block()).isEmpty();
        assertThat(testee().search(accountId, simpleQuery("Updated"))
            .collectList().block()).hasSize(1);
        assertThat(testee().search(accountId, simpleQuery("Original"))
            .collectList().block()).isEmpty();
    }

    @Test
    default void updateShouldNotAffectOtherEventUids() {
        EventUid eventUid = generateEventUid();
        EventUid eventUid2 = generateEventUid();
        EventFields event1 = EventFields.builder()
            .uid(eventUid)
            .title("Keep Me")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(eventUid2)
            .title("Change Me")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields updated2 = EventFields.builder()
            .uid(eventUid2)
            .title("Changed")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();
        testee().update(accountId, updated2).block();

        List<String> results = testee().search(accountId, simpleQuery("Keep"))
            .map(EventFields::title)
            .collectList().block();

        assertThat(results).containsExactly("Keep Me");
    }

    @Test
    default void updateSameEventMultipleTimesShouldNotFail() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Stable")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> testee().update(accountId, event).block())
                .doesNotThrowAnyException();
        }

        List<EventUid> result = testee().search(accountId, simpleQuery("Stable"))
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result).containsExactly(event.uid());
    }

    @Test
    default void deleteShouldRemoveExistingEvent() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Stable")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        testee().delete(accountId, event.uid()).block();

        List<EventFields> results = testee().search(accountId, simpleQuery(event.title()))
            .collectList().block();

        assertThat(results).isEmpty();
    }

    @Test
    default void deleteShouldNotThrowWhenEventDoesNotExist() {
        assertThatCode(() -> testee().delete(accountId, new EventUid("non-existing" + UUID.randomUUID())).block())
            .doesNotThrowAnyException();
    }

    @Test
    default void deleteShouldNotAffectOtherAccountEvents() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId2, event).block();

        testee().delete(accountId, event.uid()).block();

        List<EventFields> results = testee().search(accountId2, simpleQuery(event.title()))
            .collectList().block();

        assertThat(results).extracting(EventFields::uid).containsExactly(event.uid());
    }

    @Test
    default void deleteShouldNotRemoveOtherEvents() {
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team dinner")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();

        testee().delete(accountId, event1.uid()).block();

        List<EventUid> uids = testee().search(accountId, simpleQuery(""))
            .map(EventFields::uid)
            .collectList().block();

        assertThat(uids).containsExactly(event2.uid());
    }

    @Test
    default void searchShouldReturnExactlySameDataAsIndexed() throws Exception {
        EventFields.Person organizer = Person.of("Alice", "alice@domain.tld");
        List<EventFields.Person> attendees = List.of(Person.of("Bob", "bob@domain.tld"),
            Person.of("Charlie", "charlie@domain.tld"));
        List<EventFields.Person> resources = List.of(Person.of("Projector", "projector@resource.domain"));

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Quarterly Review")
            .location("Conference Room B")
            .description("Review of Q1 results and planning")
            .clazz("PUBLIC")
            .start(Instant.parse("2024-01-01T10:00:00Z"))
            .end(Instant.parse("2024-01-01T11:00:00Z"))
            .dtStamp(Instant.parse("2023-12-31T20:00:00Z"))
            .allDay(true)
            .hasResources(true)
            .isRecurrentMaster(true)
            .durationInDays(1)
            .organizer(organizer)
            .attendees(attendees)
            .resources(resources)
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = simpleQuery("Quarterly");

        EventFields result = testee().search(accountId, query)
            .single().block();

        assertThat(result).isEqualTo(event);
    }

    @Test
    default void searchShouldReturnEmptyWhenKeywordNoMatch() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = simpleQuery(UUID.randomUUID().toString());

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).isEmpty();
    }

    static Stream<Arguments> eventFieldForKeywordSearchSample() throws AddressException {
        String keyword = "Bob";
        CalendarURL calendarURL = new CalendarURL(new OpenPaaSId("base-id-" + UUID.randomUUID()), new OpenPaaSId("calendar-id-" + UUID.randomUUID()));

        EventFields titleMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title("Team lunch " + keyword)
            .calendarURL(calendarURL)
            .build();

        EventFields descriptionMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .description("Team lunch " + keyword)
            .calendarURL(calendarURL)
            .build();

        EventFields locationMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .location("Team lunch " + keyword)
            .calendarURL(calendarURL)
            .build();

        EventFields organizerCNMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .organizer(Person.of("Bob", UUID.randomUUID() + "@domain.tld"))
            .calendarURL(calendarURL)
            .build();

        EventFields organizerEmailMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .organizer(Person.of(UUID.randomUUID().toString(), keyword + "@domain.tld"))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeCNMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .attendees(List.of(Person.of("Bob", UUID.randomUUID() + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeEmailMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .attendees(List.of(Person.of(UUID.randomUUID().toString(), keyword + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeCNMatchMultiple = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .attendees(List.of(Person.of("Bob", UUID.randomUUID() + "@domain.tld"),
                Person.of("Alice", UUID.randomUUID() + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeEmailMatchMultiple = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .title(UUID.randomUUID().toString())
            .attendees(List.of(Person.of(UUID.randomUUID().toString(), keyword + "@domain.tld"),
                Person.of("Alice", UUID.randomUUID() + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        return Stream.of(
            Arguments.of(titleMatch, "title match"),
            Arguments.of(descriptionMatch, "description match"),
            Arguments.of(locationMatch, "location match"),
            Arguments.of(organizerCNMatch, "organizer common name match"),
            Arguments.of(organizerEmailMatch, "organizer email match"),
            Arguments.of(attendeeCNMatch, "attendee common name match"),
            Arguments.of(attendeeEmailMatch, "attendee email match"),
            Arguments.of(attendeeCNMatchMultiple, "multiple attendees with common name match"),
            Arguments.of(attendeeEmailMatchMultiple, "multiple attendees with email match"));
    }

    @ParameterizedTest(name = "{index} => {1}")
    @MethodSource("eventFieldForKeywordSearchSample")
    default void searchShouldReturnExpectedEventBasedOnKeywordMatch(EventFields eventFields, String ignored) {
        testee().index(accountId, eventFields).block();

        List<EventUid> searchResults = testee().search(accountId, simpleQuery("Bob"))
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).contains(eventFields.uid());
    }

    @Test
    default void searchShouldReturnEmptyWhenNoMatchWithDifferentAccount() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId2, event).block();

        EventSearchQuery query = simpleQuery("lunch");

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).isEmpty();
    }

    @Test
    default void searchShouldReturnAllEventsWhenKeywordIsEmpty() {
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Project Planning")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();
        testee().index(accountId2, event2).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("")
            .build();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).hasSize(2);
    }

    @Test
    default void searchShouldRespectLimit() {
        CalendarURL calendarURL = generateCalendarURL();
        String eventPrefix = "event-" + UUID.randomUUID();

        List<EventFields> events = IntStream.range(0, 5)
            .mapToObj(i -> EventFields.builder()
                .uid(new EventUid(eventPrefix + i))
                .title("Sync " + i)
                .calendarURL(calendarURL)
                .start(Instant.now().plus(i, ChronoUnit.MINUTES))
                .build())
            .peek(event -> testee().index(accountId, event).block())
            .toList();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Sync")
            .limit(3)
            .build();

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result).hasSize(3);
    }

    @Test
    default void searchShouldRespectOffsetBasedOnStartTimeOrder() {
        Instant now = Instant.now();
        CalendarURL calendarURL = generateCalendarURL();
        String eventPrefix = "event-" + UUID.randomUUID();

        List<EventFields> events = IntStream.range(1, 6)
            .mapToObj(i -> EventFields.builder()
                .uid(new EventUid(eventPrefix + i))
                .title("Daily standup  + " + i)
                .start(now.plus(i, ChronoUnit.HOURS))
                .calendarURL(calendarURL)
                .build())
            .peek(event -> testee().index(accountId, event).block())
            .toList();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("daily")
            .limit(2)
            .offset(1)
            .build();

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result)
            .containsExactly(new EventUid(eventPrefix + "2"), new EventUid(eventPrefix + "3"));
    }

    @Test
    default void searchShouldReturnEmptyWhenOffsetExceedsMatchingResults() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Team meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        // Offset = 10 while we only have 1 matching event
        EventSearchQuery query = EventSearchQuery.builder()
            .query("meeting")
            .offset(10)
            .build();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults)
            .isEmpty();
    }

    @Test
    default void searchShouldBeCaseInsensitive() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("PlanNing Session")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = simpleQuery("planning");

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).containsExactly(event.uid());
    }

    @Test
    default void searchShouldFilterByCalendarsWhenProvided() {
        CalendarURL calendarURL = generateCalendarURL();
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Meeting A")
            .calendarURL(calendarURL)
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Meeting B")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Meeting")
            .calendars(calendarURL)
            .build();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).containsExactly(event1.uid())
            .doesNotContain(event2.uid());
    }

    @Test
    default void searchShouldFilterByCalendarsWhenMultipleCalendarsProvided() {
        CalendarURL calendarURL1 = new CalendarURL(new OpenPaaSId("base-id-1"), new OpenPaaSId("calendar-id-1"));
        CalendarURL calendarURL2 = new CalendarURL(new OpenPaaSId("base-id-2"), new OpenPaaSId("calendar-id-2"));

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team Sync")
            .calendarURL(calendarURL1)
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team Marketing")
            .calendarURL(calendarURL2)
            .build();

        EventFields event3 = EventFields.builder()
            .uid(new EventUid("event-3"))
            .title("Product Launch")
            .calendarURL(calendarURL1)
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();
        testee().index(accountId, event3).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Team")
            .calendars(calendarURL1, calendarURL2)
            .build();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).containsExactlyInAnyOrder(event1.uid(), event2.uid())
            .doesNotContain(event3.uid());
    }

    @Test
    default void searchShouldFilterByOrganizerWhenProvided() throws AddressException {
        EventFields.Person organizer1 = Person.of("Alice", "alice@domain.tld");
        EventFields.Person organizer2 = Person.of("Bob", "bob@domain.tld");
        CalendarURL calendarURL = generateCalendarURL();

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team Sync")
            .organizer(organizer1)
            .calendarURL(calendarURL)
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Team Marketing")
            .organizer(organizer2)
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Team")
            .organizers(organizer1.email())
            .build();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).containsExactly(event1.uid())
            .doesNotContain(event2.uid());
    }

    @Test
    default void searchShouldFilterByAttendeesWhenProvided() throws AddressException {
        EventFields.Person attendee1 = EventFields.Person.of("Charlie", "charlie@domain.tld");
        EventFields.Person attendee2 = EventFields.Person.of("David", "david@domain.tld");

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Project Kick-off")
            .attendees(List.of(attendee1, attendee2))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Daily Sync")
            .attendees(List.of(attendee2))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event3 = EventFields.builder()
            .uid(generateEventUid())
            .title("Weekly Sync")
            .attendees(List.of(attendee1))
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();
        testee().index(accountId, event3).block();

        List<EventUid> searchSingleResults = testee().search(accountId, EventSearchQuery.builder()
                .query("Sync")
                .attendees(attendee2.email())
                .build())
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchSingleResults).containsExactly(event2.uid());

        List<EventUid> searchMultipleResults = testee().search(accountId, EventSearchQuery.builder()
                .query("Sync")
                .attendees(attendee2.email(), attendee1.email())
                .build())
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchMultipleResults).containsExactlyInAnyOrder(event2.uid(), event3.uid())
            .doesNotContain(event1.uid());
    }

    @Test
    default void searchShouldReturnEmptyWhenNoEventsMatchQueryCalendars() {
        CalendarURL calendarURL1 = generateCalendarURL();
        CalendarURL calendarURL2 = generateCalendarURL();

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Team Sync")
            .calendarURL(calendarURL1)
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Sync")
            .calendars(calendarURL2)
            .build();

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).isEmpty();
    }

    @Test
    default void searchShouldReturnEmptyWhenOptionalFiltersDontMatch() throws Exception {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Planning")
            .organizer(Person.of("Alice", "alice@domain.tld"))
            .attendees(List.of(Person.of("Bob", "bob@domain.tld")))
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Planning")
            .organizers(new MailAddress("nonexist@domain.tld"))
            .attendees(new MailAddress("nonexist2@domain.tld"))
            .calendars(new CalendarURL(new OpenPaaSId("x"), new OpenPaaSId("y")))
            .build();

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result).isEmpty();
    }

    @Test
    default void searchShouldReturnOnlyEventMatchingAllFiltersAndQuery() throws AddressException {
        Person organizer = Person.of("Alice", "alice@domain.tld");
        Person attendee = Person.of("Bob", "bob@domain.tld");

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .title("Weekly Sync")
            .organizer(organizer)
            .attendees(List.of(attendee))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .title("Weekly Sync")
            .organizer(organizer)
            .attendees(List.of())
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event1).block();
        testee().index(accountId, event2).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Weekly")
            .organizers(new MailAddress("alice@domain.tld"))
            .attendees(new MailAddress("bob@domain.tld"))
            .build();

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result).containsExactly(event1.uid())
            .doesNotContain(event2.uid());
    }

    @Test
    default void searchShouldNotDuplicateEventWhenMultipleFieldsMatch() throws AddressException {
        Person person = Person.of("Bob", "bob@domain.tld");

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Weekly")
            .organizer(person)
            .attendees(List.of(person))
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = simpleQuery("Bob");

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result).containsExactly(event.uid());
    }

    @Test
    default void searchShouldMatchEmailWithSpecialCharacters() throws AddressException {
        Person organizer = Person.of("Bob", "bob.smith@domain.tld");

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Budget")
            .organizer(organizer)
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, event).block();

        EventSearchQuery query = simpleQuery("bob.smith@domain.tld");

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(result).containsExactly(event.uid());
    }

    @Test
    default void searchShouldNotThrowWhenOptionalFieldsAreNull() throws AddressException {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .title("Planning")
            .description(null)
            .location(null)
            .organizer(Person.of(null, "a@b.c"))
            .attendees(List.of())
            .calendarURL(generateCalendarURL())
            .build();

        assertThatCode(() -> {
            testee().index(accountId, event).block();
            testee().search(accountId, simpleQuery("Planning")).collectList().block();
        }).doesNotThrowAnyException();
    }

    default EventSearchQuery simpleQuery(String query) {
        return new EventSearchQuery(query, Optional.empty(),
            Optional.empty(), Optional.empty(),
            Integer.MAX_VALUE, 0);
    }

    default CalendarURL generateCalendarURL() {
        return new CalendarURL(new OpenPaaSId("base-id-" + UUID.randomUUID()), new OpenPaaSId("calendar-id-" + UUID.randomUUID()));
    }

    default EventUid generateEventUid() {
        return new EventUid("event-" + UUID.randomUUID());
    }
}
