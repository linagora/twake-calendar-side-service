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

import static com.linagora.calendar.storage.eventsearch.EventSearchQuery.MAX_LIMIT;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.vacation.api.AccountId;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventFields.Person;

public interface CalendarSearchServiceContract {
    Username username = Username.of("user@domain.tld");
    Username username2 = Username.of("user2@domain.tld");
    AccountId accountId = AccountId.fromUsername(username);
    AccountId accountId2 = AccountId.fromUsername(username2);

    ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await()
        .atMost(Durations.TEN_SECONDS);

    CalendarSearchService testee();

    @Test
    default void indexThenSearchShouldReturnTheEvent() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        EventSearchQuery query = simpleQuery("planning");

        testee().index(accountId, CalendarEvents.of(event)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);
        });
    }

    @Test
    default void indexMultipleTimesWithSameEventFieldsShouldNotCauseError() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        CalendarEvents calendarEvents = CalendarEvents.of(event);

        testee().index(accountId, calendarEvents).block();
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> testee().index(accountId, calendarEvents).block())
                .doesNotThrowAnyException();
        }

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery("planning"))
            .map(EventFields::uid)
            .collectList().block()).hasSize(1)
            .containsExactly(event.uid()));
    }

    @Test
    default void indexShouldUpdateExistingEvent() {
        EventUid eventUid = generateEventUid();
        CalendarURL calendarURL = generateCalendarURL();
        EventFields original = EventFields.builder()
            .uid(eventUid)
            .summary("Old Title")
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, CalendarEvents.of(original)).block();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .summary("Updated Title")
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, CalendarEvents.of(updated)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> result = testee().search(accountId, simpleQuery("Title"))
                .collectList().block();

            assertThat(result).extracting(EventFields::uid).containsExactly(eventUid);
            assertThat(result).extracting(EventFields::summary).containsExactly("Updated Title");
        });
    }

    @Test
    default void indexShouldOverrideAllFieldsWhenExists() throws Exception {
        EventUid eventUid = generateEventUid();
        CalendarURL calendarURL = generateCalendarURL();
        EventFields initial = EventFields.builder()
            .uid(eventUid)
            .summary("Initial summary")
            .location("Initial location")
            .description("Initial description")
            .clazz("PRIVATE")
            .start(Instant.parse("2024-01-01T09:00:00Z"))
            .end(Instant.parse("2024-01-01T10:00:00Z"))
            .dtStamp(Instant.parse("2023-12-30T20:00:00Z"))
            .allDay(false)
            .isRecurrentMaster(false)
            .organizer(Person.of("Alice", "alice@domain.tld"))
            .attendees(List.of(Person.of("Bob", "bob@domain.tld")))
            .resources(List.of(Person.of("Whiteboard", "whiteboard@resource.domain")))
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, CalendarEvents.of(initial)).block();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .summary("Updated summary")
            .location("Updated location")
            .description("Updated description")
            .clazz("CONFIDENTIAL")
            .start(Instant.parse("2024-02-01T14:00:00Z"))
            .end(Instant.parse("2024-02-01T16:00:00Z"))
            .dtStamp(Instant.parse("2024-01-31T15:00:00Z"))
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(Person.of("Charlie", "charlie@domain.tld"))
            .attendees(List.of(
                Person.of("Dave", "dave@domain.tld"),
                Person.of("Eve", "eve@domain.tld")))
            .resources(List.of(Person.of("Projector", "projector@resource.domain")))
            .calendarURL(calendarURL)
            .build();

        testee().index(accountId, CalendarEvents.of(updated)).block();

        EventSearchQuery query = simpleQuery("Updated");
        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, query)
            .collectList().block()).containsExactly(updated));
    }

    @Test
    default void indexShouldNotAffectOtherAccounts() {
        EventUid eventUid = generateEventUid();
        EventFields event = EventFields.builder()
            .uid(eventUid)
            .summary("Original")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .summary("Updated")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId2, CalendarEvents.of(event)).block();
        testee().index(accountId, CalendarEvents.of(updated)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(accountId2, simpleQuery("Original"))
                .collectList().block()).hasSize(1);
            assertThat(testee().search(accountId2, simpleQuery("Updated"))
                .collectList().block()).isEmpty();
            assertThat(testee().search(accountId, simpleQuery("Updated"))
                .collectList().block()).hasSize(1);
            assertThat(testee().search(accountId, simpleQuery("Original"))
                .collectList().block()).isEmpty();
        });
    }

    @Test
    default void indexShouldNotAffectOtherEventUids() {
        EventUid eventUid = generateEventUid();
        EventUid eventUid2 = generateEventUid();
        EventFields event1 = EventFields.builder()
            .uid(eventUid)
            .summary("Keep Me")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(eventUid2)
            .summary("Change Me")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields updated2 = EventFields.builder()
            .uid(eventUid2)
            .summary("Changed")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);
        indexEvents(accountId, updated2);

        List<String> results = testee().search(accountId, simpleQuery("Keep"))
            .map(EventFields::summary)
            .collectList().block();

        assertThat(results).containsExactly("Keep Me");
    }

    @Test
    default void indexSameEventMultipleTimesShouldNotFail() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Stable")
            .calendarURL(generateCalendarURL())
            .build();

        CalendarEvents calendarEvents = CalendarEvents.of(event);

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> testee().index(accountId, calendarEvents).block())
                .doesNotThrowAnyException();
        }

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventUid> result = testee().search(accountId, simpleQuery("Stable"))
                .map(EventFields::uid)
                .collectList().block();

            assertThat(result).containsExactly(event.uid());
        });
    }

    @Test
    default void indexRecurrenceEventsShouldWorksWhenDoesNotExist() {
        CalendarURL calendarURL = generateCalendarURL();
        EventUid eventUid = generateEventUid();

        EventFields masterEvent = EventFields.builder()
            .uid(eventUid)
            .summary("Recurrence")
            .calendarURL(calendarURL)
            .isRecurrentMaster(true)
            .build();

        EventFields recurrenceEvent = EventFields.builder()
            .uid(eventUid)
            .summary("Recurrence")
            .calendarURL(calendarURL)
            .isRecurrentMaster(false)
            .build();

        testee().index(accountId, CalendarEvents.of(masterEvent, recurrenceEvent)).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery("Recurrence"))
            .collectList().block()).containsExactlyInAnyOrder(masterEvent, recurrenceEvent));
    }

    @Test
    default void indexRecurrenceEventsShouldWorksWhenExists() {
        CalendarURL calendarURL = generateCalendarURL();
        EventUid eventUid = generateEventUid();

        EventFields masterEvent = EventFields.builder()
            .uid(eventUid)
            .summary("Recurrence master")
            .calendarURL(calendarURL)
            .isRecurrentMaster(true)
            .build();

        indexEvents(accountId, masterEvent);

        Supplier<List<EventFields>> query = () -> testee().search(accountId, simpleQuery("Recurrence"))
            .collectList().block();
        assertThat(query.get()).containsExactly(masterEvent);

        EventFields recurrenceEvent = EventFields.builder()
            .uid(eventUid)
            .summary("Recurrence")
            .calendarURL(calendarURL)
            .isRecurrentMaster(false)
            .build();

        testee().index(accountId, CalendarEvents.of(masterEvent, recurrenceEvent)).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(query.get())
            .containsExactlyInAnyOrder(masterEvent, recurrenceEvent));
    }

    @Test
    default void deleteShouldRemoveExistingEvent() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Stable")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event);
        testee().delete(accountId, event.uid()).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery(event.summary()))
            .collectList().block()).isEmpty());
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
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId2, event);

        EventSearchQuery searchQuery = simpleQuery(event.summary());
        testee().delete(accountId, event.uid()).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(accountId, searchQuery)
                .collectList().block()).isEmpty();

            assertThat(testee().search(accountId2, searchQuery)
                .collectList().block()).extracting(EventFields::uid)
                .containsExactly(event.uid());
        });
    }

    @Test
    default void deleteShouldNotRemoveOtherEvents() {
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team dinner")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);

        testee().delete(accountId, event1.uid()).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery(""))
            .map(EventFields::uid)
            .collectList().block())
            .containsExactly(event2.uid())
            .doesNotContain(event1.uid()));
    }

    @Test
    default void searchShouldReturnExactlySameDataAsIndexed() throws Exception {
        EventFields.Person organizer = Person.of("Alice", "alice@domain.tld");
        List<EventFields.Person> attendees = List.of(Person.of("Bob", "bob@domain.tld"),
            Person.of("Charlie", "charlie@domain.tld"));
        List<EventFields.Person> resources = List.of(Person.of("Projector", "projector@resource.domain"));

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Quarterly Review")
            .location("Conference Room B")
            .description("Review of Q1 results and planning")
            .clazz("PUBLIC")
            .start(Instant.parse("2024-01-01T10:00:00Z"))
            .end(Instant.parse("2024-01-01T11:00:00Z"))
            .dtStamp(Instant.parse("2023-12-31T20:00:00Z"))
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(organizer)
            .attendees(attendees)
            .resources(resources)
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery("Quarterly");

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, query)
            .collectList().block()).containsExactly(event));
    }

    @Test
    default void searchShouldReturnEmptyWhenKeywordNoMatch() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event);

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
            .summary("Team lunch " + keyword)
            .calendarURL(calendarURL)
            .build();

        EventFields descriptionMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .description("Team lunch " + keyword)
            .calendarURL(calendarURL)
            .build();

        EventFields locationMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .location("Team lunch " + keyword)
            .calendarURL(calendarURL)
            .build();

        EventFields organizerCNMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .organizer(Person.of("Bob", UUID.randomUUID() + "@domain.tld"))
            .calendarURL(calendarURL)
            .build();

        EventFields organizerEmailMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .organizer(Person.of(UUID.randomUUID().toString(), keyword + "@domain.tld"))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeCNMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .attendees(List.of(Person.of("Bob", UUID.randomUUID() + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeEmailMatch = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .attendees(List.of(Person.of(UUID.randomUUID().toString(), keyword + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeCNMatchMultiple = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .attendees(List.of(Person.of("Bob", UUID.randomUUID() + "@domain.tld"),
                Person.of("Alice", UUID.randomUUID() + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        EventFields attendeeEmailMatchMultiple = EventFields.builder()
            .uid(new EventUid(UUID.randomUUID().toString()))
            .summary(UUID.randomUUID().toString())
            .attendees(List.of(Person.of(UUID.randomUUID().toString(), keyword + "@domain.tld"),
                Person.of("Alice", UUID.randomUUID() + "@domain.tld")))
            .calendarURL(calendarURL)
            .build();

        return Stream.of(
            Arguments.of(titleMatch, "summary match"),
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
        testee().index(accountId, CalendarEvents.of(eventFields)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, simpleQuery("Bob"))
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(eventFields);
        });
    }

    @Test
    default void searchShouldReturnEmptyWhenNoMatchWithDifferentAccount() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId2, event);

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
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Project Planning")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);
        indexEvents(accountId2, event2);

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

        int sampleSize = 5;
        List<EventFields> events = IntStream.range(0, sampleSize)
            .mapToObj(i -> EventFields.builder()
                .uid(new EventUid(eventPrefix + i))
                .summary("Sync " + i)
                .calendarURL(calendarURL)
                .start(Instant.now().plus(i, ChronoUnit.MINUTES))
                .build())
            .peek(event -> testee().index(accountId, CalendarEvents.of(event)).block())
            .toList();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery("Sync")).collectList().block())
            .hasSize(sampleSize));

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

        int sampleSize = 5;
        List<EventFields> events = IntStream.range(1, sampleSize + 1)
            .mapToObj(i -> EventFields.builder()
                .uid(new EventUid(eventPrefix + i))
                .summary("Daily standup  + " + i)
                .start(now.plus(i, ChronoUnit.HOURS))
                .calendarURL(calendarURL)
                .build())
            .peek(event -> testee().index(accountId, CalendarEvents.of(event)).block())
            .toList();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery("daily"))
            .map(EventFields::uid)
            .collectList().block()).hasSize(sampleSize));

        EventSearchQuery query = EventSearchQuery.builder()
            .query("daily")
            .limit(2)
            .offset(1)
            .build();

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        // Events are sorted by start date descending (newest first)
        // With offset=1 and limit=2, we skip event5 and get event4 and event3
        assertThat(result)
            .containsExactly(new EventUid(eventPrefix + "4"), new EventUid(eventPrefix + "3"));
    }

    @Test
    default void searchShouldReturnEmptyWhenOffsetExceedsMatchingResults() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

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
            .summary("PlanNing Session")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event);

        EventSearchQuery query = simpleQuery("planning");

        List<EventUid> searchResults = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).containsExactly(event.uid());
    }

    @Test
    default void searchShouldBeCaseInsensitiveInMailAddress() throws AddressException {
        String mail = "bOB@example.com";
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Planning Session")
            .addAttendee(Person.of("Bob", mail))
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Planning")
            .attendees(new MailAddress(mail.toLowerCase(Locale.US)))
            .build();

        List<EventFields> searchResults = testee().search(accountId, query)
            .collectList().block();

        assertThat(searchResults).extracting(EventFields::uid).containsExactly(event.uid());
        assertThat(searchResults).extracting(EventFields::attendees)
            .containsExactly(List.of(Person.of("Bob", mail)));
    }

    @Test
    default void searchShouldFilterByCalendarsWhenProvided() {
        CalendarURL calendarURL = generateCalendarURL();
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Meeting A")
            .calendarURL(calendarURL)
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Meeting B")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);

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
            .summary("Team Sync")
            .calendarURL(calendarURL1)
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team Marketing")
            .calendarURL(calendarURL2)
            .build();

        EventFields event3 = EventFields.builder()
            .uid(new EventUid("event-3"))
            .summary("Product Launch")
            .calendarURL(calendarURL1)
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);
        indexEvents(accountId, event3);

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
            .summary("Team Sync")
            .organizer(organizer1)
            .calendarURL(calendarURL)
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team Marketing")
            .organizer(organizer2)
            .calendarURL(calendarURL)
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);

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
    default void searchShouldReturnEventsMatchingAnyOrganizerInList() throws Exception {
        Person organizer1 = Person.of("Alice", "alice@domain.tld");
        Person organizer2 = Person.of("Bob", "bob@domain.tld");
        Person organizer3 = Person.of("Charlie", "charlie@domain.tld");

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event A")
            .organizer(organizer1)
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event B")
            .organizer(organizer2)
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event3 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event C")
            .organizer(organizer3)
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);
        indexEvents(accountId, event3);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Event")
            .organizers(
                new MailAddress("alice@domain.tld"),
                new MailAddress("bob@domain.tld"))
            .build();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventUid> result = testee().search(accountId, query)
                .map(EventFields::uid)
                .collectList().block();

            assertThat(result).containsExactlyInAnyOrder(event1.uid(), event2.uid())
                .doesNotContain(event3.uid());
        });
    }

    @Test
    default void searchShouldReturnEventsMatchingOrganizerAndAnyAttendeeInList() throws Exception {
        Person organizer1 = Person.of("Alice", "alice@domain.tld");
        Person organizer2 = Person.of("Bob", "bob@domain.tld");

        Person attendee1 = Person.of("Charlie", "charlie@domain.tld");
        Person attendee2 = Person.of("Dave", "dave@domain.tld");
        Person attendee3 = Person.of("Eve", "eve@domain.tld");

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Mix Match A")
            .organizer(organizer1)
            .attendees(List.of(attendee1))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Mix Match B")
            .organizer(organizer2)
            .attendees(List.of(attendee2))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event3 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Mix Match C")
            .organizer(organizer1)
            .attendees(List.of(attendee3))
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);
        indexEvents(accountId, event3);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Mix")
            .organizers(new MailAddress("alice@domain.tld"))
            .attendees(
                new MailAddress("charlie@domain.tld"),
                new MailAddress("dave@domain.tld"))
            .build();

        List<EventUid> result = testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block();

        // Only event1 matches both:
        // organizer = Alice
        // attendee = Charlie or Dave
        assertThat(result).containsExactly(event1.uid());
    }

    @Test
    default void searchShouldFilterByAttendeesWhenProvided() throws AddressException {
        EventFields.Person attendee1 = EventFields.Person.of("Charlie", "charlie@domain.tld");
        EventFields.Person attendee2 = EventFields.Person.of("David", "david@domain.tld");

        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Project Kick-off")
            .attendees(List.of(attendee1, attendee2))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Daily Sync")
            .attendees(List.of(attendee2))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event3 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Weekly Sync")
            .attendees(List.of(attendee1))
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);
        indexEvents(accountId, event3);

        CALMLY_AWAIT.untilAsserted(() -> {
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
        });
    }

    @Test
    default void searchShouldReturnEmptyWhenNoEventsMatchQueryCalendars() {
        CalendarURL calendarURL1 = generateCalendarURL();
        CalendarURL calendarURL2 = generateCalendarURL();

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team Sync")
            .calendarURL(calendarURL1)
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

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
            .summary("Planning")
            .organizer(Person.of("Alice", "alice@domain.tld"))
            .attendees(List.of(Person.of("Bob", "bob@domain.tld")))
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

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
            .summary("Weekly Sync 1")
            .organizer(organizer)
            .attendees(List.of(attendee))
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Weekly Sync 2")
            .organizer(organizer)
            .attendees(List.of())
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Weekly")
            .organizers(new MailAddress("alice@domain.tld"))
            .attendees(new MailAddress("bob@domain.tld"))
            .build();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, query)
                .map(EventFields::uid)
                .collectList().block()).containsExactly(event1.uid())
                .doesNotContain(event2.uid()));
    }

    @Test
    default void searchShouldNotDuplicateEventWhenMultipleFieldsMatch() throws AddressException {
        Person person = Person.of("Bob", "bob@domain.tld");

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Weekly")
            .organizer(person)
            .attendees(List.of(person))
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery("Bob");

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block())
            .containsExactly(event.uid()));
    }

    @Test
    default void searchShouldMatchEmailWithSpecialCharacters() throws AddressException {
        Person organizer = Person.of("Bob", "bob.smith@domain.tld");

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Budget")
            .organizer(organizer)
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery("bob.smith@domain.tld");

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, query)
            .map(EventFields::uid)
            .collectList().block())
            .containsExactly(event.uid()));
    }

    @Test
    default void searchShouldNotThrowWhenOptionalFieldsAreNull() throws AddressException {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Planning")
            .description(null)
            .location(null)
            .organizer(Person.of(null, "a@b.c"))
            .attendees(List.of())
            .calendarURL(generateCalendarURL())
            .build();

        assertThatCode(() -> {
            testee().index(accountId, CalendarEvents.of(event)).block();
            testee().search(accountId, simpleQuery("Planning")).collectList().block();
        }).doesNotThrowAnyException();
    }

    @Test
    default void deleteAllShouldRemoveAllEventsForAccount() {
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event1")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event2")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId, event2);

        testee().deleteAll(accountId).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery(""))
            .collectList().block()).isEmpty());
    }

    @Test
    default void deleteAllShouldNotAffectOtherAccounts() {
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event1")
            .calendarURL(generateCalendarURL())
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event2")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(accountId, event1);
        indexEvents(accountId2, event2);

        testee().deleteAll(accountId).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(accountId, simpleQuery("")).collectList().block()).isEmpty();
            assertThat(testee().search(accountId2, simpleQuery("")).collectList().block())
                .extracting(EventFields::uid).containsExactly(event2.uid());
        });
    }

    @Test
    default void indexShouldUpdateWhenNewSequenceIsHigher() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("v1")
            .calendarURL(url)
            .build();

        EventFields v2 = EventFields.builder()
            .uid(uid)
            .sequence(2)
            .summary("v2")
            .calendarURL(url)
            .build();

        indexEvents(accountId, v1);
        testee().index(accountId, CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("v2"))
                .collectList().block())
                .containsExactly(v2));

        assertThat(testee().search(accountId, simpleQuery("v1"))
            .collectList().block()).isEmpty();
    }

    @Test
    default void indexShouldIgnoreWhenNewSequenceIsLower() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields v2 = EventFields.builder()
            .uid(uid)
            .sequence(2)
            .summary("v2")
            .calendarURL(url)
            .build();
        indexEvents(accountId, v2);

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("v1")
            .calendarURL(url)
            .build();
        testee().index(accountId, CalendarEvents.of(v1)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("v2"))
                .collectList().block())
                .containsExactly(v2));
    }

    @Test
    default void indexShouldNotUpdateWhenSequenceIsEqual() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .sequence(5)
            .summary("original")
            .calendarURL(url)
            .build();

        EventFields sameSeq = EventFields.builder()
            .uid(uid)
            .sequence(5)
            .summary("ignored")
            .calendarURL(url)
            .build();

        indexEvents(accountId, v1);
        testee().index(accountId, CalendarEvents.of(sameSeq)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("original"))
                .collectList().block())
                .containsExactly(v1));

        assertThat(testee().search(accountId, simpleQuery("ignored"))
            .collectList().block()).isEmpty();
    }

    @Test
    default void indexShouldUpdateWhenExistingSequenceIsNull() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields noSeq = EventFields.builder()
            .uid(uid)
            .summary("noSeq")
            .calendarURL(url)
            .build();
        indexEvents(accountId, noSeq);

        EventFields newSeq = EventFields.builder()
            .uid(uid)
            .sequence(3)
            .summary("withSeq")
            .calendarURL(url)
            .build();
        testee().index(accountId, CalendarEvents.of(newSeq)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("withSeq"))
                .collectList().block())
                .containsExactly(newSeq));

        assertThat(testee().search(accountId, simpleQuery("noSeq"))
            .collectList().block()).isEmpty();
    }

    @Test
    default void indexShouldUpdateOnLargeSequenceJump() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields small = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("old")
            .calendarURL(url)
            .build();

        EventFields big = EventFields.builder()
            .uid(uid)
            .sequence(10_000)
            .summary("new")
            .calendarURL(url)
            .build();

        indexEvents(accountId, small);
        testee().index(accountId, CalendarEvents.of(big)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("new"))
                .collectList().block())
                .containsExactly(big));

        assertThat(testee().search(accountId, simpleQuery("old"))
            .collectList().block()).isEmpty();
    }

    @Test
    default void indexShouldRespectSequenceForRecurrenceMasterAndInstances() {
        CalendarURL url = generateCalendarURL();
        EventUid uid = generateEventUid();

        EventFields masterV1 = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("master-v1")
            .isRecurrentMaster(true)
            .calendarURL(url)
            .build();

        EventFields masterV2 = EventFields.builder()
            .uid(uid)
            .sequence(2)
            .summary("master-v2")
            .isRecurrentMaster(true)
            .calendarURL(url)
            .build();

        EventFields recurrence = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("instance")
            .isRecurrentMaster(false)
            .calendarURL(url)
            .build();

        testee().index(accountId, CalendarEvents.of(masterV1, recurrence)).block();
        testee().index(accountId, CalendarEvents.of(masterV2, recurrence)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(accountId, simpleQuery(""))
                .collectList().block();

            assertThat(results).extracting(EventFields::summary)
                .contains("master-v2", "instance");
        });
    }

    @Test
    default void indexShouldOverrideAllFieldsOnlyWhenSequenceIsHigher() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("old")
            .location("loc1")
            .calendarURL(url)
            .build();

        EventFields v2 = EventFields.builder()
            .uid(uid)
            .sequence(2)
            .summary("new")
            .location("loc2")
            .calendarURL(url)
            .build();

        indexEvents(accountId, v1);
        testee().index(accountId, CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> result = testee().search(accountId, simpleQuery("new"))
                .collectList().block();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().location()).isEqualTo("loc2");
        });
    }

    @Test
    default void sequenceUpdateShouldNotAffectSearchResultContent() {
        EventUid uid = generateEventUid();
        CalendarURL url = generateCalendarURL();

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("alpha")
            .calendarURL(url)
            .build();

        EventFields v2 = EventFields.builder()
            .uid(uid)
            .sequence(2)
            .summary("beta")
            .calendarURL(url)
            .build();

        indexEvents(accountId, v1);
        testee().index(accountId, CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("beta"))
                .collectList().block())
                .containsExactly(v2));
    }

    @Test
    default void reindexShouldOverrideRegardlessOfSequence() {
        CalendarURL url = generateCalendarURL();
        EventUid uid = generateEventUid();

        EventFields oldSeq = EventFields.builder()
            .uid(uid)
            .sequence(10)
            .summary("old")
            .calendarURL(url)
            .build();

        EventFields newSeq = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("new")
            .calendarURL(url)
            .build();

        // index initial high sequence
        indexEvents(accountId, oldSeq);

        // reindex with lower sequence => MUST override
        testee().reindex(accountId, CalendarEvents.of(newSeq)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(accountId, simpleQuery("new"))
                .collectList().block())
                .containsExactly(newSeq));

        assertThat(testee().search(accountId, simpleQuery("old"))
            .collectList().block())
            .isEmpty();
    }

    @Test
    default void reindexShouldIgnoreSequenceForRecurrenceEvents() {
        CalendarURL url = generateCalendarURL();
        EventUid uid = generateEventUid();

        EventFields masterHigh = EventFields.builder()
            .uid(uid)
            .sequence(100)
            .summary("master-high")
            .isRecurrentMaster(true)
            .calendarURL(url)
            .build();

        EventFields masterLow = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("master-low")
            .isRecurrentMaster(true)
            .calendarURL(url)
            .build();

        // index high sequence
        indexEvents(accountId, masterHigh);

        // reindex lower => MUST replace
        testee().reindex(accountId, CalendarEvents.of(masterLow)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(accountId, simpleQuery(""))
                .collectList().block();

            assertThat(results)
                .extracting(EventFields::summary)
                .containsExactly("master-low");
        });
    }

    @Test
    default void reindexShouldNotAffectOtherAccounts() {
        CalendarURL url = generateCalendarURL();

        EventUid uid1 = generateEventUid();
        EventFields eventAcc1 = EventFields.builder()
            .uid(uid1)
            .summary("acc1")
            .calendarURL(url)
            .build();

        EventFields eventAcc2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("acc2")
            .calendarURL(url)
            .build();

        indexEvents(accountId, eventAcc1);
        indexEvents(accountId2, eventAcc2);

        EventFields newAcc1 = EventFields.builder()
            .uid(uid1)
            .summary("new-acc1")
            .calendarURL(url)
            .build();

        testee().reindex(accountId, CalendarEvents.of(newAcc1)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            // account1 must see only new event
            assertThat(testee().search(accountId, simpleQuery(""))
                .collectList().block())
                .containsExactly(newAcc1);

            // account2 must remain unchanged
            assertThat(testee().search(accountId2, simpleQuery(""))
                .collectList().block())
                .extracting(EventFields::summary)
                .containsExactly("acc2");
        });
    }

    @Test
    default void updateSingleEventStartDateShouldNotCreateDuplicate() {
        CalendarURL url = generateCalendarURL();
        EventUid uid = generateEventUid();

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .summary("single")
            .start(Instant.parse("2025-01-01T10:00:00Z"))
            .sequence(1)
            .calendarURL(url)
            .build();

        // index initial
        testee().index(accountId, CalendarEvents.of(v1)).block();

        EventFields v2 = EventFields.builder()
            .uid(uid)
            .summary("single")
            .start(Instant.parse("2025-01-02T10:00:00Z")) // updated DTSTART
            .sequence(2)
            .calendarURL(url)
            .build();

        // update with new DTSTART
        testee().index(accountId, CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(accountId, simpleQuery("single"))
                .collectList().block();

            assertThat(results)
                .hasSize(1)
                .containsExactly(v2);
        });
    }

    @Test
    default void updateRecurrenceInstanceStartDateShouldNotCreateDuplicate() {
        CalendarURL url = generateCalendarURL();
        EventUid uid = generateEventUid();

        EventFields master = EventFields.builder()
            .uid(uid)
            .summary("recur")
            .sequence(1)
            .isRecurrentMaster(true)
            .start(Instant.parse("2025-01-01T09:00:00Z"))
            .calendarURL(url)
            .build();

        EventFields instanceV1 = EventFields.builder()
            .uid(uid)
            .summary("recur")
            .isRecurrentMaster(false)
            .sequence(1)
            .start(Instant.parse("2025-01-03T10:00:00Z"))
            .recurrenceId("2025-01-03T10:00:00Z")
            .calendarURL(url)
            .build();

        // index initial recurrence
        testee().index(accountId, CalendarEvents.of(master, instanceV1)).block();

        EventFields instanceV2 = EventFields.builder()
            .uid(uid)
            .summary("recur")
            .isRecurrentMaster(false)
            .sequence(2)
            .start(Instant.parse("2025-01-04T10:00:00Z"))
            .recurrenceId("2025-01-03T10:00:00Z")
            .calendarURL(url)
            .build();

        // update instance
        testee().index(accountId, CalendarEvents.of(master, instanceV2)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(accountId, simpleQuery("recur"))
                .collectList().block();

            assertThat(results)
                .extracting(EventFields::start)
                .containsExactlyInAnyOrder(master.start(), instanceV2.start());
        });
    }

    // private helper
    private void indexEvents(AccountId accountId, EventFields events) {
        testee().index(accountId, CalendarEvents.of(events)).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(accountId, simpleQuery(events.summary())).collectList().block())
            .isNotEmpty());
    }

    default EventSearchQuery simpleQuery(String query) {
        return new EventSearchQuery(query, Optional.empty(),
            Optional.empty(), Optional.empty(),
            MAX_LIMIT, 0);
    }

    default CalendarURL generateCalendarURL() {
        return new CalendarURL(new OpenPaaSId("base-id-" + UUID.randomUUID()), new OpenPaaSId("calendar-id-" + UUID.randomUUID()));
    }

    default EventUid generateEventUid() {
        return new EventUid("event-" + UUID.randomUUID());
    }
}
