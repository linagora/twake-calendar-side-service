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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventFields.Person;

public interface CalendarSearchServiceContract {
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

        EventSearchQuery query = simpleQuery("planning", event.calendarURL());

        testee().index(CalendarEvents.of(event)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"spr", "spri", "sprint", "plann", "meet"})
    default void searchShouldMatchSummaryPrefix(String search) {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        EventSearchQuery query = simpleQuery(search, event.calendarURL());

        testee().index(CalendarEvents.of(event)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(query)
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

        testee().index(calendarEvents).block();
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> testee().index(calendarEvents).block())
                .doesNotThrowAnyException();
        }

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery("planning", event.calendarURL()))
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

        testee().index(CalendarEvents.of(original)).block();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .summary("Updated Title")
            .calendarURL(calendarURL)
            .build();

        testee().index(CalendarEvents.of(updated)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> result = testee().search(simpleQuery("Title", calendarURL))
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

        testee().index(CalendarEvents.of(initial)).block();

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

        testee().index(CalendarEvents.of(updated)).block();

        EventSearchQuery query = simpleQuery("Updated", calendarURL);
        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(query)
            .collectList().block()).containsExactly(updated));
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

        indexEvents(event1);
        indexEvents(event2);
        indexEvents(updated2);

        List<String> results = testee().search(simpleQuery("Keep", event1.calendarURL()))
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
            assertThatCode(() -> testee().index(calendarEvents).block())
                .doesNotThrowAnyException();
        }

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventUid> result = testee().search(simpleQuery("Stable", event.calendarURL()))
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

        testee().index(CalendarEvents.of(masterEvent, recurrenceEvent)).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery("Recurrence", calendarURL))
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

        indexEvents(masterEvent);

        Supplier<List<EventFields>> query = () -> testee().search(simpleQuery("Recurrence", calendarURL))
            .collectList().block();
        assertThat(query.get()).containsExactly(masterEvent);

        EventFields recurrenceEvent = EventFields.builder()
            .uid(eventUid)
            .summary("Recurrence")
            .calendarURL(calendarURL)
            .isRecurrentMaster(false)
            .build();

        testee().index(CalendarEvents.of(masterEvent, recurrenceEvent)).block();

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

        indexEvents(event);
        testee().delete(event.calendarURL(), event.uid()).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery(event.summary(), event.calendarURL()))
            .collectList().block()).isEmpty());
    }

    @Test
    default void deleteShouldNotThrowWhenEventDoesNotExist() {
        assertThatCode(() -> testee().delete(generateCalendarURL(), new EventUid("non-existing" + UUID.randomUUID())).block())
            .doesNotThrowAnyException();
    }

    @Test
    default void deleteShouldNotAffectOtherCalendarEvents() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(event);

        EventSearchQuery searchQuery = EventSearchQuery.builder()
            .query(event.summary())
            .calendars(event.calendarURL())
            .build();
        testee().delete(generateCalendarURL(), event.uid()).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(searchQuery)
            .collectList().block()).extracting(EventFields::uid)
            .containsExactly(event.uid()));
    }

    @Test
    default void deleteShouldNotRemoveSameEventUidFromAnotherCalendarOfSameBaseCalendarId() {
        // Given two calendars share the same base calendar id and contain events with the same uid.
        OpenPaaSId baseCalendarId = new OpenPaaSId("base-id-" + UUID.randomUUID());
        CalendarURL firstCalendarURL = new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-1-" + UUID.randomUUID()));
        CalendarURL secondCalendarURL = new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-2-" + UUID.randomUUID()));
        EventUid eventUid = generateEventUid();
        EventFields firstCalendarEvent = EventFields.builder()
            .uid(eventUid)
            .summary("First same base")
            .calendarURL(firstCalendarURL)
            .build();
        EventFields secondCalendarEvent = EventFields.builder()
            .uid(eventUid)
            .summary("Second same base")
            .calendarURL(secondCalendarURL)
            .build();

        indexEvents(firstCalendarEvent);
        indexEvents(secondCalendarEvent);

        // When deleting the event uid from the first calendar only.
        testee().delete(firstCalendarURL, eventUid).block();

        // Then the second calendar still keeps its own event with the same uid.
        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(simpleQuery("", firstCalendarURL))
                .collectList().block()).isEmpty();
            assertThat(testee().search(simpleQuery("", secondCalendarURL))
                .collectList().block()).containsExactly(secondCalendarEvent);
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

        indexEvents(event1);
        indexEvents(event2);

        testee().delete(event1.calendarURL(), event1.uid()).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery("", event1.calendarURL(), event2.calendarURL()))
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

        testee().index(CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery("Quarterly", event.calendarURL());

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(query)
            .collectList().block()).containsExactly(event));
    }

    @Test
    default void searchShouldReturnEmptyWhenKeywordNoMatch() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(event);

        EventSearchQuery query = simpleQuery(UUID.randomUUID().toString(), event.calendarURL());

        List<EventUid> searchResults = testee().search(query)
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
        testee().index(CalendarEvents.of(eventFields)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(simpleQuery("Bob", eventFields.calendarURL()))
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(eventFields);
        });
    }

    @Test
    default void searchShouldReturnEmptyWhenNoMatchWithDifferentCalendar() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Team lunch")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(event);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("lunch")
            .calendars(generateCalendarURL())
            .build();

        List<EventUid> searchResults = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);
        indexEvents(event2);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("")
            .calendars(event1.calendarURL(), event2.calendarURL())
            .build();

        List<EventUid> searchResults = testee().search(query)
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
            .peek(event -> testee().index(CalendarEvents.of(event)).block())
            .toList();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery("Sync", calendarURL)).collectList().block())
            .hasSize(sampleSize));

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Sync")
            .calendars(calendarURL)
            .limit(3)
            .build();

        List<EventUid> result = testee().search(query)
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
            .peek(event -> testee().index(CalendarEvents.of(event)).block())
            .toList();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery("daily", calendarURL))
            .map(EventFields::uid)
            .collectList().block()).hasSize(sampleSize));

        EventSearchQuery query = EventSearchQuery.builder()
            .query("daily")
            .calendars(calendarURL)
            .limit(2)
            .offset(1)
            .build();

        List<EventUid> result = testee().search(query)
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

        testee().index(CalendarEvents.of(event)).block();

        // Offset = 10 while we only have 1 matching event
        EventSearchQuery query = EventSearchQuery.builder()
            .query("meeting")
            .calendars(event.calendarURL())
            .offset(10)
            .build();

        List<EventUid> searchResults = testee().search(query)
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

        indexEvents(event);

        EventSearchQuery query = simpleQuery("planning", event.calendarURL());

        List<EventUid> searchResults = testee().search(query)
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

        indexEvents(event);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Planning")
            .calendars(event.calendarURL())
            .attendees(new MailAddress(mail.toLowerCase(Locale.US)))
            .build();

        List<EventFields> searchResults = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Meeting")
            .calendars(calendarURL)
            .build();

        List<EventUid> searchResults = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);
        indexEvents(event3);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Team")
            .calendars(calendarURL1, calendarURL2)
            .build();

        List<EventUid> searchResults = testee().search(query)
            .map(EventFields::uid)
            .collectList().block();

        assertThat(searchResults).containsExactlyInAnyOrder(event1.uid(), event2.uid())
            .doesNotContain(event3.uid());
    }

    @Test
    default void searchShouldFilterByCalendarIdWhenCalendarsShareSameBaseCalendarId() {
        // Given two calendars share the same base calendar id and both contain matching events.
        OpenPaaSId baseCalendarId = new OpenPaaSId("base-id-" + UUID.randomUUID());
        CalendarURL firstCalendarURL = new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-1-" + UUID.randomUUID()));
        CalendarURL secondCalendarURL = new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-2-" + UUID.randomUUID()));
        EventFields firstCalendarEvent = EventFields.builder()
            .uid(generateEventUid())
            .summary("Same base meeting first")
            .calendarURL(firstCalendarURL)
            .build();
        EventFields secondCalendarEvent = EventFields.builder()
            .uid(generateEventUid())
            .summary("Same base meeting second")
            .calendarURL(secondCalendarURL)
            .build();

        indexEvents(firstCalendarEvent);
        indexEvents(secondCalendarEvent);

        // When searching only the first calendar.
        EventSearchQuery query = EventSearchQuery.builder()
            .query("Same base meeting")
            .calendars(firstCalendarURL)
            .build();

        List<EventFields> searchResults = testee().search(query)
            .collectList().block();

        // Then events from the second calendar are not returned.
        assertThat(searchResults).containsExactly(firstCalendarEvent);
    }

    @Test
    default void searchShouldReturnEmptyWhenCalendarListIsEmpty() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Meeting A")
            .calendarURL(generateCalendarURL())
            .build();

        indexEvents(event);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Meeting")
            .calendars(List.of())
            .build();

        List<EventFields> searchResults = testee().search(query)
            .collectList().block();

        assertThat(searchResults).isEmpty();
    }

    @Test
    default void searchShouldRejectTooManyCalendars() {
        List<CalendarURL> calendarURLs = IntStream.rangeClosed(0, MAX_LIMIT)
            .mapToObj(ignored -> generateCalendarURL())
            .toList();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Meeting")
            .calendars(calendarURLs)
            .build();

        assertThatThrownBy(() -> testee().search(query).collectList().block())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    default void searchShouldRejectWhenCalendarsAreNotProvided() {
        EventSearchQuery query = EventSearchQuery.builder()
            .query("Meeting")
            .build();

        assertThatThrownBy(() -> testee().search(query).collectList().block())
            .isInstanceOf(IllegalArgumentException.class);
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

        indexEvents(event1);
        indexEvents(event2);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Team")
            .calendars(calendarURL)
            .organizers(organizer1.email())
            .build();

        List<EventUid> searchResults = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);
        indexEvents(event3);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Event")
            .calendars(event1.calendarURL(), event2.calendarURL(), event3.calendarURL())
            .organizers(
                new MailAddress("alice@domain.tld"),
                new MailAddress("bob@domain.tld"))
            .build();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventUid> result = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);
        indexEvents(event3);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Mix")
            .calendars(event1.calendarURL(), event2.calendarURL(), event3.calendarURL())
            .organizers(new MailAddress("alice@domain.tld"))
            .attendees(
                new MailAddress("charlie@domain.tld"),
                new MailAddress("dave@domain.tld"))
            .build();

        List<EventUid> result = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);
        indexEvents(event3);

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventUid> searchSingleResults = testee().search(EventSearchQuery.builder()
                    .query("Sync")
                    .calendars(event1.calendarURL(), event2.calendarURL(), event3.calendarURL())
                    .attendees(attendee2.email())
                    .build())
                .map(EventFields::uid)
                .collectList().block();

            assertThat(searchSingleResults).containsExactly(event2.uid());

            List<EventUid> searchMultipleResults = testee().search(EventSearchQuery.builder()
                    .query("Sync")
                    .calendars(event1.calendarURL(), event2.calendarURL(), event3.calendarURL())
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

        testee().index(CalendarEvents.of(event)).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Sync")
            .calendars(calendarURL2)
            .build();

        List<EventUid> searchResults = testee().search(query)
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

        testee().index(CalendarEvents.of(event)).block();

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Planning")
            .organizers(new MailAddress("nonexist@domain.tld"))
            .attendees(new MailAddress("nonexist2@domain.tld"))
            .calendars(new CalendarURL(new OpenPaaSId("x"), new OpenPaaSId("y")))
            .build();

        List<EventUid> result = testee().search(query)
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

        indexEvents(event1);
        indexEvents(event2);

        EventSearchQuery query = EventSearchQuery.builder()
            .query("Weekly")
            .calendars(event1.calendarURL(), event2.calendarURL())
            .organizers(new MailAddress("alice@domain.tld"))
            .attendees(new MailAddress("bob@domain.tld"))
            .build();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(query)
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

        testee().index(CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery("Bob", event.calendarURL());

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(query)
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

        testee().index(CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery("bob.smith@domain.tld", event.calendarURL());

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(query)
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
            testee().index(CalendarEvents.of(event)).block();
            testee().search(simpleQuery("Planning", event.calendarURL())).collectList().block();
        }).doesNotThrowAnyException();
    }

    @Test
    default void deleteAllShouldRemoveAllEventsForBaseCalendarId() {
        OpenPaaSId baseCalendarId = new OpenPaaSId("base-id-" + UUID.randomUUID());
        EventFields event1 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event1")
            .calendarURL(new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-" + UUID.randomUUID())))
            .build();

        EventFields event2 = EventFields.builder()
            .uid(generateEventUid())
            .summary("Event2")
            .calendarURL(new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-" + UUID.randomUUID())))
            .build();

        indexEvents(event1);
        indexEvents(event2);

        testee().deleteAll(baseCalendarId).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery("", event1.calendarURL(), event2.calendarURL()))
            .collectList().block()).isEmpty());
    }

    @Test
    default void deleteAllShouldNotAffectOtherBaseCalendarIds() {
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

        indexEvents(event1);
        indexEvents(event2);

        testee().deleteAll(event1.calendarURL().base()).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("")
                    .calendars(event1.calendarURL())
                    .build()).collectList().block()).isEmpty();
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("")
                    .calendars(event2.calendarURL())
                    .build()).collectList().block())
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

        indexEvents(v1);
        testee().index(CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("v2", url))
                .collectList().block())
                .containsExactly(v2));

        assertThat(testee().search(simpleQuery("v1", url))
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
        indexEvents(v2);

        EventFields v1 = EventFields.builder()
            .uid(uid)
            .sequence(1)
            .summary("v1")
            .calendarURL(url)
            .build();
        testee().index(CalendarEvents.of(v1)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("v2", url))
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

        indexEvents(v1);
        testee().index(CalendarEvents.of(sameSeq)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("original", url))
                .collectList().block())
                .containsExactly(v1));

        assertThat(testee().search(simpleQuery("ignored", url))
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
        indexEvents(noSeq);

        EventFields newSeq = EventFields.builder()
            .uid(uid)
            .sequence(3)
            .summary("withSeq")
            .calendarURL(url)
            .build();
        testee().index(CalendarEvents.of(newSeq)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("withSeq", url))
                .collectList().block())
                .containsExactly(newSeq));

        assertThat(testee().search(simpleQuery("noSeq", url))
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

        indexEvents(small);
        testee().index(CalendarEvents.of(big)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("new", url))
                .collectList().block())
                .containsExactly(big));

        assertThat(testee().search(simpleQuery("old", url))
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

        testee().index(CalendarEvents.of(masterV1, recurrence)).block();
        testee().index(CalendarEvents.of(masterV2, recurrence)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(simpleQuery("", url))
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

        indexEvents(v1);
        testee().index(CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> result = testee().search(simpleQuery("new", url))
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

        indexEvents(v1);
        testee().index(CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("beta", url))
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
        indexEvents(oldSeq);

        // reindex with lower sequence => MUST override
        testee().reindex(CalendarEvents.of(newSeq)).block();

        CALMLY_AWAIT.untilAsserted(() ->
            assertThat(testee().search(simpleQuery("new", url))
                .collectList().block())
                .containsExactly(newSeq));

        assertThat(testee().search(simpleQuery("old", url))
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
        indexEvents(masterHigh);

        // reindex lower => MUST replace
        testee().reindex(CalendarEvents.of(masterLow)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(simpleQuery("", url))
                .collectList().block();

            assertThat(results)
                .extracting(EventFields::summary)
                .containsExactly("master-low");
        });
    }

    @Test
    default void reindexShouldNotAffectSameEventUidInOtherCalendar() {
        EventUid uid = generateEventUid();
        CalendarURL firstCalendarURL = generateCalendarURL();
        CalendarURL secondCalendarURL = generateCalendarURL();
        EventFields firstCalendarEvent = EventFields.builder()
            .uid(uid)
            .summary("first-calendar")
            .calendarURL(firstCalendarURL)
            .build();

        EventFields secondCalendarEvent = EventFields.builder()
            .uid(uid)
            .summary("second-calendar")
            .calendarURL(secondCalendarURL)
            .build();

        indexEvents(firstCalendarEvent);
        indexEvents(secondCalendarEvent);

        EventFields updatedFirstCalendarEvent = EventFields.builder()
            .uid(uid)
            .summary("updated-first-calendar")
            .calendarURL(firstCalendarURL)
            .build();

        testee().reindex(CalendarEvents.of(updatedFirstCalendarEvent)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(simpleQuery("", firstCalendarURL))
                .collectList().block()).containsExactly(updatedFirstCalendarEvent);
            assertThat(testee().search(simpleQuery("", secondCalendarURL))
                .collectList().block()).containsExactly(secondCalendarEvent);
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
        testee().index(CalendarEvents.of(v1)).block();

        EventFields v2 = EventFields.builder()
            .uid(uid)
            .summary("single")
            .start(Instant.parse("2025-01-02T10:00:00Z")) // updated DTSTART
            .sequence(2)
            .calendarURL(url)
            .build();

        // update with new DTSTART
        testee().index(CalendarEvents.of(v2)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(simpleQuery("single", url))
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
        testee().index(CalendarEvents.of(master, instanceV1)).block();

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
        testee().index(CalendarEvents.of(master, instanceV2)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> results = testee().search(simpleQuery("recur", url))
                .collectList().block();

            assertThat(results)
                .extracting(EventFields::start)
                .containsExactlyInAnyOrder(master.start(), instanceV2.start());
        });
    }

    @Test
    default void indexShouldNotAffectSameEventUidInOtherCalendar() {
        EventUid eventUid = generateEventUid();
        CalendarURL firstCalendarURL = generateCalendarURL();
        CalendarURL secondCalendarURL = generateCalendarURL();
        EventFields event = EventFields.builder()
            .uid(eventUid)
            .summary("Original")
            .calendarURL(firstCalendarURL)
            .build();

        EventFields updated = EventFields.builder()
            .uid(eventUid)
            .summary("Updated")
            .calendarURL(secondCalendarURL)
            .build();

        testee().index(CalendarEvents.of(event)).block();
        testee().index(CalendarEvents.of(updated)).block();

        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("Original")
                    .calendars(firstCalendarURL)
                    .build())
                .collectList().block()).containsExactly(event);
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("Updated")
                    .calendars(firstCalendarURL)
                    .build())
                .collectList().block()).isEmpty();
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("Updated")
                    .calendars(secondCalendarURL)
                    .build())
                .collectList().block()).containsExactly(updated);
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("Original")
                    .calendars(secondCalendarURL)
                    .build())
                .collectList().block()).isEmpty();
        });
    }

    @Test
    default void indexShouldNotAffectSameEventUidInAnotherCalendarOfSameBaseCalendarId() {
        // Given two calendars share the same base calendar id and use the same event uid.
        OpenPaaSId baseCalendarId = new OpenPaaSId("base-id-" + UUID.randomUUID());
        CalendarURL firstCalendarURL = new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-1-" + UUID.randomUUID()));
        CalendarURL secondCalendarURL = new CalendarURL(baseCalendarId, new OpenPaaSId("calendar-id-2-" + UUID.randomUUID()));
        EventUid eventUid = generateEventUid();
        EventFields firstCalendarEvent = EventFields.builder()
            .uid(eventUid)
            .summary("alphaoriginal")
            .calendarURL(firstCalendarURL)
            .build();
        EventFields secondCalendarEvent = EventFields.builder()
            .uid(eventUid)
            .summary("betaupdated")
            .calendarURL(secondCalendarURL)
            .build();

        // When indexing both events.
        testee().index(CalendarEvents.of(firstCalendarEvent)).block();
        testee().index(CalendarEvents.of(secondCalendarEvent)).block();

        // Then each calendar keeps its own indexed event and does not expose the other one.
        CALMLY_AWAIT.untilAsserted(() -> {
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("alphaoriginal")
                    .calendars(firstCalendarURL)
                    .build())
                .collectList().block()).containsExactly(firstCalendarEvent);
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("betaupdated")
                    .calendars(firstCalendarURL)
                    .build())
                .collectList().block()).isEmpty();
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("betaupdated")
                    .calendars(secondCalendarURL)
                    .build())
                .collectList().block()).containsExactly(secondCalendarEvent);
            assertThat(testee().search(EventSearchQuery.builder()
                    .query("alphaoriginal")
                    .calendars(secondCalendarURL)
                    .build())
                .collectList().block()).isEmpty();
        });
    }

    private void indexEvents(EventFields events) {
        testee().index(CalendarEvents.of(events)).block();

        CALMLY_AWAIT.untilAsserted(() -> assertThat(testee().search(simpleQuery(events.summary(), events.calendarURL()))
            .collectList().block())
            .isNotEmpty());
    }

    default EventSearchQuery simpleQuery(String query, CalendarURL calendarURL, CalendarURL... otherCalendarURLs) {
        List<CalendarURL> calendarURLs = Stream.concat(Stream.of(calendarURL), Stream.of(otherCalendarURLs))
            .toList();

        return new EventSearchQuery(query, Optional.of(calendarURLs),
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
