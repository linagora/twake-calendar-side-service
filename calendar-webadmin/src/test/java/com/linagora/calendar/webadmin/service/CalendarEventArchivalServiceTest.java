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

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.webadmin.task.RunningOptions.DEFAULT_EVENTS_PER_SECOND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.TestFixture;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.webadmin.model.EventArchivalCriteria;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

class CalendarEventArchivalServiceTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private CalDavClient calDavClient;
    private CalendarEventArchivalService testee;
    private OpenPaaSUser user;

    @BeforeEach
    void setup() throws SSLException {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(),
            TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING);

        user = sabreDavExtension.newTestUser();
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);

        testee = new CalendarEventArchivalService(calDavClient, openPaaSUserDAO);
    }

    @Test
    void shouldMoveMatchEventFromDefaultCalendarToArchivalCalendar() {
        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        createEvent(sourceCalendar, simpleEventIcs("event-1", "20240101T100000Z"));

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();
        Task.Result result = testee.archiveUser(user, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        CalendarURL archivalCalendar = findArchivalCalendar(user);

        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(context.totalSuccess().get()).isEqualTo(1);
            softly.assertThat(context.totalFailure().get()).isEqualTo(0);

            softly.assertThat(listEvents(sourceCalendar))
                .as("Event should be removed from source default calendar")
                .doesNotContain("UID:event-1");

            softly.assertThat(listEvents(archivalCalendar))
                .as("Event should be created in archival calendar")
                .contains("UID:event-1");
        });
    }

    @Test
    void shouldCreateArchivalCalendarWhenNotExist() {
        // Given: user has no Archival calendar
        assertThat(findArchivalCalendar(user)).isNull();

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.now())
            .build();

        // When: running archival without any event
        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();
        Task.Result result = testee.archiveUser(user, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        // Then: Archival calendar is created
        CalendarURL archivalCalendar = findArchivalCalendar(user);

        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(archivalCalendar).isNotNull();
        });
    }

    @Test
    void shouldNotMoveEventWhenNotMatchCriteria() {
        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        // Given: an event that does NOT match the archival criteria
        createEvent(sourceCalendar, simpleEventIcs("event-not-match", "20990101T100000Z"));

        // Before: event exists in source calendar
        assertThat(listEvents(sourceCalendar))
            .as("Event should exist in source default calendar before archival")
            .contains("UID:event-not-match");

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-01-01T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();

        // When: running archival
        Task.Result result = testee.archiveUser(user, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        // Then: Archival calendar may be created, but event is NOT moved
        CalendarURL archivalCalendar = findArchivalCalendar(user);

        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(context.totalSuccess().get()).isEqualTo(0);
            softly.assertThat(context.totalFailure().get()).isEqualTo(0);

            softly.assertThat(listEvents(sourceCalendar))
                .as("Event should remain in source calendar when criteria does not match")
                .contains("UID:event-not-match");

            if (archivalCalendar != null) {
                softly.assertThat(listEvents(archivalCalendar))
                    .as("Event should not be created in archival calendar when criteria does not match")
                    .doesNotContain("UID:event-not-match");
            }
        });
    }

    @Test
    void shouldMoveOnlyEventsMatchingCriteria() {
        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        // Given: three events, two match criteria, one does not
        createEvent(sourceCalendar, simpleEventIcs("event-match-1", "20240101T100000Z"));
        createEvent(sourceCalendar, simpleEventIcs("event-match-2", "20240201T100000Z"));
        createEvent(sourceCalendar, simpleEventIcs("event-not-match", "20990101T100000Z"));

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();

        // When: running archival
        Task.Result result = testee.archiveUser(user, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        CalendarURL archivalCalendar = findArchivalCalendar(user);

        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(context.totalSuccess().get()).isEqualTo(2);
            softly.assertThat(context.totalFailure().get()).isEqualTo(0);

            softly.assertThat(listEvents(sourceCalendar))
                .as("Only non-matching event should remain in source calendar")
                .contains("UID:event-not-match")
                .doesNotContain("UID:event-match-1")
                .doesNotContain("UID:event-match-2");

            softly.assertThat(listEvents(archivalCalendar))
                .as("Only matching events should be moved to archival calendar")
                .contains("UID:event-match-1")
                .contains("UID:event-match-2")
                .doesNotContain("UID:event-not-match");
        });
    }

    @Test
    void shouldReuseExistingArchivalCalendar() {
        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        // Given: an existing Archival calendar is already created manually
        String archivalCalendarId = UUID.randomUUID().toString();
        calDavClient.createNewCalendar(user.username(), user.id(),
                new CalDavClient.NewCalendar(archivalCalendarId, "Archival", "#8E8E93",
                    "Archived events. Events moved here are no longer active."))
            .block();

        // And one event matching criteria in source calendar
        createEvent(sourceCalendar, simpleEventIcs("event-1", "20240101T100000Z"));

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();

        // When: running archival
        Task.Result result = testee.archiveUser(user, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        // Then: the existing Archival calendar is reused (no new one created)
        CalendarURL archivalCalendar = new CalendarURL(user.id(), new OpenPaaSId(archivalCalendarId));

        List<CalendarURL> archivalCalendarsAfter = calDavClient.findUserCalendars(user.username(), user.id(), Map.of("personal", "true"))
            .map(calendarList ->
                calendarList.calendars().entrySet().stream()
                    .filter(entry -> Strings.CS.equals("Archival", entry.getValue().path("dav:name").asText()))
                    .map(Map.Entry::getKey)
                    .toList())
            .block();

        assertThat(archivalCalendarsAfter)
            .hasSize(1)
            .containsExactly(archivalCalendar);

        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(listEvents(archivalCalendar))
                .contains("UID:event-1");
        });
    }

    @Test
    void shouldBeIdempotentWhenArchiveUserIsRunTwice() {
        CalendarURL sourceCalendar = CalendarURL.from(user.id());

        createEvent(sourceCalendar, simpleEventIcs("event-1", "20240101T100000Z"));

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        // First run
        CalendarEventArchivalService.Context context1 = new CalendarEventArchivalService.Context();
        Task.Result result1 = testee.archiveUser(user, criteria, context1, DEFAULT_EVENTS_PER_SECOND).block();

        // Second run should be a no-op: already archived events must not be re-archived
        CalendarEventArchivalService.Context context2 = new CalendarEventArchivalService.Context();
        Task.Result result2 = testee.archiveUser(user, criteria, context2, DEFAULT_EVENTS_PER_SECOND).block();

        CalendarURL archivalCalendar = findArchivalCalendar(user);

        assertSoftly(softly -> {
            // First run
            softly.assertThat(result1).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(context1.totalSuccess().get()).isEqualTo(1);

            // Second run
            softly.assertThat(result2).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(context2.totalSuccess().get()).isEqualTo(0);
            softly.assertThat(context2.totalFailure().get()).isEqualTo(0);

            // Final state
            softly.assertThat(listEvents(sourceCalendar))
                .as("Source calendar should remain empty after second run")
                .doesNotContain("UID:event-1");

            softly.assertThat(listEvents(archivalCalendar))
                .as("Event should exist only once in archival calendar")
                .contains("UID:event-1");
        });
    }

    @Test
    void shouldArchiveIndependentlyBetweenDifferentUsers() {
        // Given: two distinct users
        OpenPaaSUser userA = sabreDavExtension.newTestUser();
        OpenPaaSUser userB = sabreDavExtension.newTestUser();

        CalendarURL sourceCalendarA = CalendarURL.from(userA.id());
        CalendarURL sourceCalendarB = CalendarURL.from(userB.id());

        // And each user has one matching event
        calDavClient.importCalendar(sourceCalendarA, UUID.randomUUID().toString(), userA.username(),
                simpleEventIcs("event-user-a", "20240101T100000Z").getBytes(StandardCharsets.UTF_8))
            .block();
        calDavClient.importCalendar(sourceCalendarB, UUID.randomUUID().toString(), userB.username(),
                simpleEventIcs("event-user-b", "20240101T100000Z").getBytes(StandardCharsets.UTF_8))
            .block();

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context contextA = new CalendarEventArchivalService.Context();

        // When: archive is run for user A only
        Task.Result resultA = testee.archiveUser(userA, criteria, contextA, DEFAULT_EVENTS_PER_SECOND).block();

        // Then: user A is archived, user B is untouched
        CalendarURL archivalCalendarB = findArchivalCalendar(userB);

        assertSoftly(softly -> {
            // User A assertions
            softly.assertThat(resultA).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(contextA.totalSuccess().get()).isEqualTo(1);
            softly.assertThat(contextA.totalFailure().get()).isEqualTo(0);

            // User B assertions (no archival triggered)
            softly.assertThat(archivalCalendarB)
                .as("User B archival calendar should not be created")
                .isNull();

            softly.assertThat(listEvents(userB.username(), sourceCalendarB))
                .as("User B source calendar should remain untouched")
                .contains("UID:event-user-b");
        });
    }

    @Test
    void shouldArchiveOnlyDefaultCalendarAndIgnoreOtherCalendars() {
        // Given
        CalendarURL defaultCalendar = CalendarURL.from(user.id());

        // Create another personal calendar
        String workCalendarId = UUID.randomUUID().toString();
        calDavClient.createNewCalendar(user.username(), user.id(),
                new CalDavClient.NewCalendar(workCalendarId, "Work", "#FF9500", "Work calendar"))
            .block();

        CalendarURL workCalendar = new CalendarURL(user.id(), new OpenPaaSId(workCalendarId));

        // And each calendar has one matching event
        createEvent(defaultCalendar, simpleEventIcs("event-default", "20240101T100000Z"));
        createEvent(workCalendar, simpleEventIcs("event-work", "20240101T100000Z"));

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();

        // When
        Task.Result result = testee.archiveUser(user, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        CalendarURL archivalCalendar = findArchivalCalendar(user);

        // Then
        assertSoftly(softly -> {
            // Result & statistics
            softly.assertThat(result).isEqualTo(Task.Result.COMPLETED);
            softly.assertThat(context.totalSuccess().get())
                .as("Only events from default calendar should be archived")
                .isEqualTo(1);
            softly.assertThat(context.totalFailure().get()).isEqualTo(0);

            // Other calendar: untouched
            softly.assertThat(listEvents(workCalendar))
                .as("Non-default calendar should not be archived")
                .contains("UID:event-work");

            softly.assertThat(listEvents(archivalCalendar))
                .as("Archived calendar should not contain events from other calendars")
                .doesNotContain("UID:event-work");
        });
    }

    @Test
    void shouldReturnPartialWhenArchiveSingleUserDoesNotExist() {
        Username unknownUser = Username.of("unknown@domain.tld");

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.now())
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();

        Task.Result result = testee.archiveUser(unknownUser, criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        assertSoftly(softly -> {
            softly.assertThat(result)
                .as("Archiving a non-existing user should return PARTIAL")
                .isEqualTo(Task.Result.PARTIAL);

            softly.assertThat(context.totalSuccess().get())
                .as("No event should be archived for non-existing user")
                .isEqualTo(0);

            softly.assertThat(context.totalFailure().get())
                .as("No event-level failure should be recorded for non-existing user")
                .isEqualTo(0);
        });
    }

    @Test
    void shouldArchiveAllUsersIndependentlyInAllMode() {
        // Given: two users, each has one matching event
        OpenPaaSUser userA = sabreDavExtension.newTestUser();
        OpenPaaSUser userB = sabreDavExtension.newTestUser();

        CalendarURL calendarA = CalendarURL.from(userA.id());
        CalendarURL calendarB = CalendarURL.from(userB.id());

        createEvent(userA, calendarA, simpleEventIcs("event-a", "20240101T100000Z"));
        createEvent(userB, calendarB, simpleEventIcs("event-b", "20240102T100000Z"));

        EventArchivalCriteria criteria = EventArchivalCriteria.builder()
            .masterDtStartBefore(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        CalendarEventArchivalService.Context context = new CalendarEventArchivalService.Context();

        // When:
        Task.Result result = testee.archive(criteria, context, DEFAULT_EVENTS_PER_SECOND).block();

        CalendarURL archivalCalendarA = findArchivalCalendar(userA);
        CalendarURL archivalCalendarB = findArchivalCalendar(userB);

        assertSoftly(softly -> {
            softly.assertThat(result)
                .as("Global archival should complete successfully when all users have matching events")
                .isEqualTo(Task.Result.COMPLETED);

            softly.assertThat(context.totalSuccess().get())
                .as("Both users' events should be archived")
                .isEqualTo(2);

            softly.assertThat(context.totalFailure().get())
                .isEqualTo(0);

            // User A: event archived
            softly.assertThat(listEvents(userA.username(), archivalCalendarA))
                .contains("UID:event-a");

            softly.assertThat(listEvents(userA.username(), calendarA))
                .doesNotContain("UID:event-a");

            // User B: event archived
            softly.assertThat(listEvents(userB.username(), archivalCalendarB))
                .contains("UID:event-b");

            softly.assertThat(listEvents(userB.username(), calendarB))
                .doesNotContain("UID:event-b");
        });
    }

    private void createEvent(CalendarURL calendar, String ics) {
        createEvent(user, calendar, ics);
    }

    private void createEvent(OpenPaaSUser user, CalendarURL calendar, String ics) {
        calDavClient.importCalendar(calendar, UUID.randomUUID().toString(), user.username(), ics.getBytes(StandardCharsets.UTF_8))
            .block();
    }

    private String listEvents(CalendarURL calendar) {
        return listEvents(user.username(), calendar);
    }

    private String listEvents(Username username, CalendarURL calendar) {
        return calDavClient.export(username, calendar.asUri())
            .blockOptional()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .orElse("");
    }

    private CalendarURL findArchivalCalendar(OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id(), Map.of("personal", "true"))
            .flatMap(calendarList -> Mono.justOrEmpty(calendarList.findCalendarByName("Archival")))
            .block();
    }

    private String simpleEventIcs(String uid, String dtStart) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:%s
            DTSTART:%s
            DTEND:%s
            SUMMARY:Test event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, dtStart, dtStart);
    }
}

