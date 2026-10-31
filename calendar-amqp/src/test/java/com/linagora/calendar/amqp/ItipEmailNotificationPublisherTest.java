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

package com.linagora.calendar.amqp;

import static com.linagora.calendar.api.CalendarUtil.parseIcs;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.linagora.calendar.amqp.ItipEmailNotificationPublisher.NotificationEmailDTO;
import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.RecurrenceId;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class ItipEmailNotificationPublisherTest {

    private static final String UID = "event-uid@test";
    private static final String CALENDAR_ID = "team-calendar";
    private static final URI EVENT_PATH = URI.create("/calendars/openpaas/team-calendar/event-uid@test.ics");

    // All the fixtures below start on 2026-04-01, so a clock anchored before that keeps them upcoming.
    private static final Clock CLOCK_BEFORE_EVENTS = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private ItipEmailNotificationPublisher testee;

    @BeforeEach
    void setUp() {
        testee = publisherWithClock(CLOCK_BEFORE_EVENTS);
    }

    private static ItipEmailNotificationPublisher publisherWithClock(Clock clock) {
        return new ItipEmailNotificationPublisher(
            mock(Sender.class),
            body -> new OutboundMessage("exchange", "routingKey", body),
            clock);
    }

    @Nested
    class SingleEvent {
        private static final String SINGLE_REQUEST_OLD = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:event-uid@test
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            SUMMARY:Sprint sync
            ATTENDEE:mailto:alice@example.com
            ORGANIZER:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        private static final String SINGLE_REQUEST_NEW = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:event-uid@test
            DTSTART:20260401T103000Z
            DTEND:20260401T113000Z
            SUMMARY:Sprint sync updated
            ATTENDEE:mailto:alice@example.com
            ORGANIZER:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        @Test
        void requestWithoutOldShouldMarkAsNewEvent() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, SINGLE_REQUEST_NEW, Optional.empty());

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.empty());

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification ->
                    assertThat(notification.isNewEvent()).isTrue());
        }

        @Test
        void replyShouldIgnoreOldEventAndChanges() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REPLY, SINGLE_REQUEST_NEW, Optional.of(SINGLE_REQUEST_OLD));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(SINGLE_REQUEST_OLD)));

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REPLY);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.oldEvent()).isEmpty();
                    assertThat(notification.changes()).isEmpty();
                });
        }

        @Test
        void counterShouldIncludeOldEvent() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_COUNTER, SINGLE_REQUEST_NEW, Optional.of(SINGLE_REQUEST_OLD));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.empty());

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_COUNTER);
                    assertThat(notification.oldEvent()).contains(SINGLE_REQUEST_OLD);
                    assertThat(notification.changes()).isEmpty();
                });
        }

        @Test
        void requestWithOldShouldComputeChangesAndNotMarkAsNew() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, SINGLE_REQUEST_NEW, Optional.of(SINGLE_REQUEST_OLD));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(SINGLE_REQUEST_OLD)));

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThatJson(notification.changes().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "summary": {
                                    "previous": "Sprint sync",
                                    "current": "Sprint sync updated"
                                },
                                "dtstart": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-01 10:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-01 10:30:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                },
                                "dtend": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-01 11:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-01 11:30:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                }
                            }""");
                });
        }

        @Test
        void rescheduleFromPastToFutureShouldStillNotify() {
            // Issue #976: the event was at 10:00, has just passed (clock is 10:05), and gets moved to 11:00.
            // The past-guard must be based on the new time (11:00, still upcoming) so the attendee is notified.
            Clock justAfterOldStart = Clock.fixed(Instant.parse("2026-04-01T10:05:00Z"), ZoneOffset.UTC);
            String rescheduledToFuture = SINGLE_REQUEST_NEW
                .replace("DTSTART:20260401T103000Z", "DTSTART:20260401T110000Z")
                .replace("DTEND:20260401T113000Z", "DTEND:20260401T120000Z");
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, rescheduledToFuture, Optional.of(SINGLE_REQUEST_OLD));

            List<NotificationEmailDTO> notifications = publisherWithClock(justAfterOldStart)
                .buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(SINGLE_REQUEST_OLD)));

            assertThat(notifications).hasSize(1);
        }

        @Test
        void updateShouldBeSuppressedWhenNewEventIsAlreadyInThePast() {
            // The new start (10:30) is already past relative to the clock (11:00): no point emailing.
            Clock afterNewStart = Clock.fixed(Instant.parse("2026-04-01T11:00:00Z"), ZoneOffset.UTC);
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, SINGLE_REQUEST_NEW, Optional.of(SINGLE_REQUEST_OLD));

            List<NotificationEmailDTO> notifications = publisherWithClock(afterNewStart)
                .buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(SINGLE_REQUEST_OLD)));

            assertThat(notifications).isEmpty();
        }

        @Test
        void requestWithOldShouldReturnEmptyWhenRecipientNeverAttends() {
            String singleWithoutRecipient = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:event-uid@test
                DTSTART:20260401T103000Z
                DTEND:20260401T113000Z
                SUMMARY:Sprint sync updated
                ATTENDEE:mailto:cedric@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, singleWithoutRecipient, Optional.of(singleWithoutRecipient));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(singleWithoutRecipient)));

            assertThat(notifications).isEmpty();
        }

        @Test
        void requestWithOldAndOnlyAlarmChangeShouldNotGenerateNotification() {
            String singleWithPersonalAlarm = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:event-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Sprint sync
                ATTENDEE:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                TRIGGER:-PT10M
                END:VALARM
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = new ItipLocalDeliveryDTO("mailto:bob@example.com", Method.VALUE_REQUEST, UID, CALENDAR_ID,
                singleWithPersonalAlarm, Optional.of(SINGLE_REQUEST_OLD), false, List.of("mailto:alice@example.com"));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(SINGLE_REQUEST_OLD)));

            assertThat(notifications).isEmpty();
        }

        @Test
        void publicAgendaRequestShouldStillNotifyWhenOnlyPartstatChanges() {
            String singlePublicAgendaNeedsAction = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:event-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Public agenda booking
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String singlePublicAgendaAccepted = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:event-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Public agenda booking
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, singlePublicAgendaAccepted, Optional.of(singlePublicAgendaNeedsAction));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(singlePublicAgendaNeedsAction)));

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.changes()).isEmpty();
                });
        }

        @Test
        void notificationShouldRespectMetadataFromItipLocalDeliveryDto() {
            ItipLocalDeliveryDTO dto = new ItipLocalDeliveryDTO(
                "mailto:organizer@example.com",
                Method.VALUE_REQUEST,
                UID,
                "public-team-calendar",
                SINGLE_REQUEST_NEW,
                Optional.empty(),
                true,
                List.of("mailto:alice@example.com"));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.empty());

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.senderEmail()).isEqualTo("organizer@example.com");
                    assertThat(notification.recipientEmail()).isEqualTo("alice@example.com");
                    assertThat(notification.calendarURI()).isEqualTo("public-team-calendar");
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.eventPath()).contains(EVENT_PATH.getPath());
                    assertThat(notification.event()).isEqualTo(SINGLE_REQUEST_NEW);
                });
        }
    }

    @Nested
    class RecurrenceEvent {
        private static final String MASTER_VEVENT = """
            BEGIN:VEVENT
            UID:recurring-uid@test
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            RRULE:FREQ=WEEKLY;COUNT=3
            SUMMARY:Weekly sync
            ATTENDEE:mailto:alice@example.com
            ORGANIZER:mailto:bob@example.com
            END:VEVENT""";

        private static final String OVERRIDE_VEVENT = """
            BEGIN:VEVENT
            UID:recurring-uid@test
            RECURRENCE-ID:20260408T100000Z
            DTSTART:20260408T120000Z
            DTEND:20260408T130000Z
            SUMMARY:Weekly sync moved
            ATTENDEE:mailto:alice@example.com
            ORGANIZER:mailto:bob@example.com
            END:VEVENT""";

        private static final String RECURRING_MASTER_AND_OVERRIDE = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            %s
            %s
            END:VCALENDAR
            """.formatted(MASTER_VEVENT, OVERRIDE_VEVENT);

        private static final String RECURRING_OLD_NO_EXDATE = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:recurring-uid@test
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            RRULE:FREQ=WEEKLY;COUNT=3
            SUMMARY:Weekly sync
            ATTENDEE:mailto:alice@example.com
            ORGANIZER:mailto:bob@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        @Test
        void requestWithUnparsableRecurrenceIdShouldStillNotifyChangedMaster() {
            String current = RECURRING_MASTER_AND_OVERRIDE
                .replace("SUMMARY:Weekly sync\n", "SUMMARY:Weekly sync updated\n");

            List<NotificationEmailDTO> notifications = requestWithUnparsableRecurrenceId(current);

            assertThat(notifications).anySatisfy(notification -> {
                assertThat(notification.isNewEvent()).isFalse();
                assertThat(notification.event()).contains("SUMMARY:Weekly sync updated")
                    .doesNotContain("RECURRENCE-ID");
            });
        }

        @Test
        void requestWithChangedRuleAndUnparsableRecurrenceIdShouldStillNotifyNewSeries() {
            String current = RECURRING_MASTER_AND_OVERRIDE
                .replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=WEEKLY;COUNT=4");

            List<NotificationEmailDTO> notifications = requestWithUnparsableRecurrenceId(current);

            assertThat(notifications).anySatisfy(notification -> {
                assertThat(notification.isNewEvent()).isTrue();
                assertThat(notification.event()).contains("RRULE:FREQ=WEEKLY;COUNT=4")
                    .doesNotContain("RECURRENCE-ID");
            });
        }

        @Test
        void requestWithUnreadableRDateShouldNotBeSuppressedWhenExpiryIsUnknown() {
            String current = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RDATE;TZID=Europe/Paris:20261340T100000");
            Clock afterStart = Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC);

            List<NotificationEmailDTO> notifications = publisherWithClock(afterStart)
                .buildNotificationMessages(dto(Method.VALUE_REQUEST, current, Optional.empty()), EVENT_PATH, Optional.empty());

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.event()).isEqualTo(current);
                });
        }

        @Test
        void requestWithoutOldShouldSplitByOccurrenceAndMarkAsNew() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, RECURRING_MASTER_AND_OVERRIDE, Optional.empty());

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.empty());

            assertThat(notifications).hasSize(2)
                .allSatisfy(notification -> {
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(parseIcs(notification.event()).getComponents(Component.VEVENT)).hasSize(1);
                });

            List<String> actualEvents = notifications.stream()
                .map(notification -> parseIcs(notification.event()).getComponents(Component.VEVENT).getFirst().toString())
                .toList();
            assertThat(actualEvents)
                .anySatisfy(event -> assertThat(event).isEqualToIgnoringNewLines(MASTER_VEVENT.trim()))
                .anySatisfy(event -> assertThat(event).isEqualToIgnoringNewLines(OVERRIDE_VEVENT.trim()));
        }

        @Test
        void recurrenceNotificationsShouldRespectMetadataFromItipLocalDeliveryDto() {
            ItipLocalDeliveryDTO dto = new ItipLocalDeliveryDTO(
                "mailto:organizer-recurring@example.com",
                Method.VALUE_REQUEST,
                UID,
                "public-recurring-calendar",
                RECURRING_MASTER_AND_OVERRIDE,
                Optional.empty(),
                true,
                List.of("mailto:alice@example.com"));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.empty());

            assertThat(notifications).hasSize(2)
                .allSatisfy(notification -> {
                    assertThat(notification.senderEmail()).isEqualTo("organizer-recurring@example.com");
                    assertThat(notification.recipientEmail()).isEqualTo("alice@example.com");
                    assertThat(notification.calendarURI()).isEqualTo("public-recurring-calendar");
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.eventPath()).contains(EVENT_PATH.getPath());
                });
        }

        @Test
        void requestWithOldAndNoChangesShouldNotGenerateNotification() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, RECURRING_MASTER_AND_OVERRIDE,
                Optional.of(RECURRING_MASTER_AND_OVERRIDE));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH,
                Optional.of(parseIcs(RECURRING_MASTER_AND_OVERRIDE)));

            assertThat(notifications).isEmpty();
        }

        @Test
        void requestWithOldAndChangedMasterShouldGenerateUpdateNotification() {
            String recurringNewChangedMaster = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Weekly sync updated
                ATTENDEE:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                BEGIN:VEVENT
                UID:recurring-uid@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T120000Z
                DTEND:20260408T130000Z
                SUMMARY:Weekly sync moved
                ATTENDEE:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, recurringNewChangedMaster,
                Optional.of(RECURRING_MASTER_AND_OVERRIDE));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH,
                Optional.of(parseIcs(RECURRING_MASTER_AND_OVERRIDE)));

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.event())
                        .isEqualToIgnoringNewLines("""
                            BEGIN:VCALENDAR
                            VERSION:2.0
                            PRODID:-//Test//Calendar//EN
                            METHOD:REQUEST
                            BEGIN:VEVENT
                            UID:recurring-uid@test
                            DTSTART:20260401T100000Z
                            DTEND:20260401T110000Z
                            RRULE:FREQ=WEEKLY;COUNT=3
                            SUMMARY:Weekly sync updated
                            ATTENDEE:mailto:alice@example.com
                            ORGANIZER:mailto:bob@example.com
                            END:VEVENT
                            END:VCALENDAR""");
                    assertThatJson(notification.changes().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "summary": {
                                    "previous": "Weekly sync",
                                    "current": "Weekly sync updated"
                                }
                            }""");
                });
        }

        @Test
        void requestWithoutOldAndOnlyMasterVEventShouldReturnSingleNotification() {
            String recurringOnlyMaster = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Weekly sync
                ATTENDEE:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, recurringOnlyMaster, Optional.empty());

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.empty());

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).isEqualTo(recurringOnlyMaster);
                });
        }

        @Test
        void publicAgendaRequestShouldStillNotifyWhenOnlyOrganizerPartstatChanges() {
            String recurringPublicAgendaNeedsAction = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Public agenda recurring booking
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:bob@example.com
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String recurringPublicAgendaAccepted = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                SUMMARY:Public agenda recurring booking
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:bob@example.com
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, recurringPublicAgendaAccepted,
                Optional.of(recurringPublicAgendaNeedsAction));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH,
                Optional.of(parseIcs(recurringPublicAgendaNeedsAction)));

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.changes()).isEmpty();
                });
        }

        @Test
        void counterShouldIncludeOldEventAndKeepSingleNotification() {
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_COUNTER, RECURRING_MASTER_AND_OVERRIDE, Optional.of(RECURRING_OLD_NO_EXDATE));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(RECURRING_OLD_NO_EXDATE)));

            assertThat(notifications).hasSize(1)
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_COUNTER);
                    assertThat(notification.oldEvent()).contains(RECURRING_OLD_NO_EXDATE);
                    assertThat(notification.event()).isEqualTo(RECURRING_MASTER_AND_OVERRIDE);
                });
        }

        @Test
        void addedExDateShouldOnlyCancelTheNewOccurrenceWithEmbeddedTimezone() {
            String current = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Linagora//Twake-Calendar//EN
                BEGIN:VTIMEZONE
                TZID:Europe/Paris
                BEGIN:DAYLIGHT
                TZOFFSETFROM:+0100
                TZOFFSETTO:+0200
                TZNAME:CEST
                DTSTART:19700329T020000
                RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
                END:DAYLIGHT
                BEGIN:STANDARD
                TZOFFSETFROM:+0200
                TZOFFSETTO:+0100
                TZNAME:CET
                DTSTART:19701025T030000
                RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
                END:STANDARD
                END:VTIMEZONE
                BEGIN:VEVENT
                UID:event-uid@test
                DTSTART;TZID=Europe/Paris:20261013T140000
                DTEND;TZID=Europe/Paris:20261013T150000
                RRULE:FREQ=WEEKLY;BYDAY=TU
                SUMMARY:Weekly meeting
                ORGANIZER:mailto:bob@example.com
                ATTENDEE:mailto:alice@example.com
                EXDATE;TZID=Europe/Paris:20261013T140000
                EXDATE;TZID=Europe/Paris:20261020T140000
                END:VEVENT
                END:VCALENDAR
                """;
            String previous = current.replace("EXDATE;TZID=Europe/Paris:20261020T140000\n", "");

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(
                dto(Method.VALUE_REQUEST, current, Optional.of(previous)), EVENT_PATH, Optional.of(parseIcs(previous)));

            assertThat(notifications).singleElement().satisfies(notification -> {
                assertThat(notification.method()).isEqualTo(Method.VALUE_CANCEL);
                assertThat(notification.event()).contains("RECURRENCE-ID;TZID=Europe/Paris:20261020T140000");
            });
        }

        @Test
        void requestWithOldAndNewExDateShouldGenerateCancelNotification() {
            String recurringNewWithExDate = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY;COUNT=3
                EXDATE:20260408T100000Z
                SUMMARY:Weekly sync
                ATTENDEE:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String expectedCancelEvent = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:CANCEL
                BEGIN:VEVENT
                UID:recurring-uid@test
                SUMMARY:Weekly sync
                ATTENDEE:mailto:alice@example.com
                ORGANIZER:mailto:bob@example.com
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T100000Z
                DTEND:20260408T110000Z
                STATUS:CANCELLED
                END:VEVENT
                END:VCALENDAR
                """.trim();
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, recurringNewWithExDate, Optional.of(RECURRING_OLD_NO_EXDATE));

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(RECURRING_OLD_NO_EXDATE)));

            assertThat(notifications)
                .hasSize(1)
                .filteredOn(notification -> notification.method().equals(Method.VALUE_CANCEL))
                .singleElement()
                .satisfies(cancel -> assertThat(cancel.event()).isEqualToIgnoringNewLines(expectedCancelEvent));
        }

        @Test
        void singleEventBecomingRecurringShouldInviteAttendeeToTheSeries() {
            String timezone = """
                BEGIN:VTIMEZONE
                TZID:Europe/Moscow
                BEGIN:STANDARD
                TZOFFSETFROM:+0300
                TZOFFSETTO:+0300
                TZNAME:MSK
                DTSTART:19700101T000000
                END:STANDARD
                END:VTIMEZONE""";
            String singleOld = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Linagora//Twake-Calendar//EN
                %s
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART;TZID=Europe/Moscow:20261026T123000
                DTEND;TZID=Europe/Moscow:20261026T130000
                SEQUENCE:1
                SUMMARY:Weekly sync
                ORGANIZER:mailto:bob@example.com
                ATTENDEE;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:bob@example.com
                ATTENDEE;PARTSTAT=NEEDS-ACTION;SCHEDULE-STATUS=1.0:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """.formatted(timezone);
            String recurringNew = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Sabre//Sabre VObject 5.0.0//EN
                METHOD:REQUEST
                %s
                BEGIN:VEVENT
                UID:recurring-uid@test
                DTSTART;TZID=Europe/Moscow:20261026T123000
                DTEND;TZID=Europe/Moscow:20261026T130000
                SEQUENCE:2
                SUMMARY:Weekly sync
                ORGANIZER:mailto:bob@example.com
                RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
                ATTENDEE;PARTSTAT=ACCEPTED;ROLE=CHAIR:mailto:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """.formatted(timezone);

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(
                dto(Method.VALUE_REQUEST, recurringNew, Optional.of(singleOld)), EVENT_PATH, Optional.of(parseIcs(singleOld)));

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR");
                });
        }

        @Test
        void recurrenceRuleOnlyChangeShouldInviteAttendeeToTheNewSeries() {
            String recurringDaily = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=DAILY;COUNT=3");

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(
                dto(Method.VALUE_REQUEST, recurringDaily, Optional.of(RECURRING_OLD_NO_EXDATE)), EVENT_PATH,
                Optional.of(parseIcs(RECURRING_OLD_NO_EXDATE)));

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=DAILY;COUNT=3");
                });
        }

        @Test
        void requestWithOnlyOverridesShouldNotFailOnMissingMaster() {
            String overridesOnly = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                %s
                %s
                END:VCALENDAR
                """.formatted(OVERRIDE_VEVENT, OVERRIDE_VEVENT
                    .replace("20260408T1", "20260415T1")
                    .replace("SUMMARY:Weekly sync moved", "SUMMARY:Weekly sync moved again"));
            String previousOverrides = overridesOnly.replace("SUMMARY:Weekly sync moved again", "SUMMARY:Weekly sync moved");

            List<NotificationEmailDTO> notifications = testee.buildNotificationMessages(
                dto(Method.VALUE_REQUEST, overridesOnly, Optional.of(previousOverrides)), EVENT_PATH,
                Optional.of(parseIcs(previousOverrides)));

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260415T100000Z");
                });
        }

        @Test
        void recurringEventBecomingSingleShouldInviteAttendeeToTheSingleEvent() {
            String single = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3\n", "");

            List<NotificationEmailDTO> notifications = request(single, RECURRING_OLD_NO_EXDATE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).doesNotContain("RRULE");
                });
        }

        @Test
        void recurringEventWithOverrideBecomingSingleShouldOnlyInviteAttendeeToTheSingleEvent() {
            String single = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3\n", "");

            List<NotificationEmailDTO> notifications = request(single, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).doesNotContain("RRULE", "RECURRENCE-ID");
                });
        }

        @Test
        void singleEventBecomingRecurringWithOverrideShouldInviteAttendeeToTheSeriesAndTheOverride() {
            String single = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3\n", "");

            List<NotificationEmailDTO> notifications = request(RECURRING_MASTER_AND_OVERRIDE, single);

            assertThat(notifications).hasSize(2)
                .allSatisfy(notification -> assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST))
                .anySatisfy(notification -> {
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=WEEKLY;COUNT=3");
                })
                .anySatisfy(notification -> assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z"));
        }

        @Test
        void changedExDateShouldCancelTheNewlyExcludedOccurrenceAndRestoreThePreviousOne() {
            String previous = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");
            String current = withExDate(RECURRING_OLD_NO_EXDATE, "20260415T100000Z");

            List<NotificationEmailDTO> notifications = request(current, previous);

            assertThat(notifications).hasSize(2)
                .anySatisfy(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_CANCEL);
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260415T100000Z");
                })
                .anySatisfy(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z", "DTSTART:20260408T100000Z");
                });
        }

        @Test
        void unreadableExDateShouldNotRestorePreviouslyExcludedOccurrence() {
            String previous = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");
            String current = RECURRING_OLD_NO_EXDATE.replaceFirst("(RRULE:[^\\n]+\\n)", "$1EXDATE;TZID=Europe/Paris:20261340T120000\n");

            List<NotificationEmailDTO> notifications = request(current, previous);

            assertThat(notifications).noneSatisfy(notification -> {
                assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z");
            });
        }

        @Test
        void requestShouldIgnoreUnparsableExDate() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Calendar//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:event-uid@test
                DTSTART;TZID=Europe/Paris:20261013T140000
                DTEND;TZID=Europe/Paris:20261013T150000
                RRULE:FREQ=WEEKLY;BYDAY=TU
                EXDATE;TZID=Europe/Paris:20261340T140000
                SUMMARY:Weekly meeting
                ORGANIZER:mailto:bob@example.com
                ATTENDEE:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            ItipLocalDeliveryDTO dto = dto(Method.VALUE_REQUEST, ics, Optional.of(ics));

            assertThatCode(() -> testee.buildNotificationMessages(dto, EVENT_PATH, Optional.of(parseIcs(ics))))
                .doesNotThrowAnyException();
        }

        @Test
        void unparsableExDateShouldNotPreventProcessingTheReadableOnes() {
            String previous = withExDate(withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z"), "20261340T100000Z");
            String current = withExDate(withExDate(RECURRING_OLD_NO_EXDATE, "20260415T100000Z"), "20261340T100000Z");

            List<NotificationEmailDTO> notifications = request(current, previous);

            assertThat(notifications).hasSize(2)
                .anySatisfy(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_CANCEL);
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260415T100000Z");
                })
                .anySatisfy(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z");
                });
        }

        @Test
        void removedExDateShouldInviteAttendeeToTheRestoredOccurrence() {
            String previous = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");

            List<NotificationEmailDTO> notifications = request(RECURRING_OLD_NO_EXDATE, previous);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z")
                        .doesNotContain("RRULE", "EXDATE");
                });
        }

        @Test
        void removedExDateReplacedByAnOverrideShouldOnlyNotifyTheOverride() {
            String previous = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");

            List<NotificationEmailDTO> notifications = request(RECURRING_MASTER_AND_OVERRIDE, previous);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z", "SUMMARY:Weekly sync moved");
                });
        }

        @Test
        void removedExDateAndItsOverrideShouldOnlyInviteAttendeeToTheRestoredOccurrence() {
            String previous = withExDate(RECURRING_MASTER_AND_OVERRIDE, "20260408T100000Z");

            List<NotificationEmailDTO> notifications = request(RECURRING_OLD_NO_EXDATE, previous);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z", "DTSTART:20260408T100000Z");
                });
        }

        @Test
        void removedExDateOfAnHourlySeriesShouldInviteAttendeeToTheRestoredOccurrenceAtItsOwnTime() {
            String hourly = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=HOURLY;COUNT=3")
                .replace("DTEND:20260401T110000Z", "DTEND:20260401T103000Z");
            String previous = withExDate(hourly, "20260401T110000Z");

            List<NotificationEmailDTO> notifications = request(hourly, previous);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260401T110000Z",
                        "DTSTART:20260401T110000Z", "DTEND:20260401T113000Z");
                });
        }

        @Test
        void newOverrideShouldOnlyNotifyTheMovedOccurrence() {
            List<NotificationEmailDTO> notifications = request(RECURRING_MASTER_AND_OVERRIDE, RECURRING_OLD_NO_EXDATE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z");
                    assertThatJson(notification.changes().orElseThrow().toString())
                        .isObject().containsKeys("summary", "dtstart", "dtend");
                });
        }

        @Test
        void overrideChangeShouldOnlyNotifyTheOverride() {
            String current = RECURRING_MASTER_AND_OVERRIDE.replace("SUMMARY:Weekly sync moved", "SUMMARY:Weekly sync moved again");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z");
                    assertThatJson(notification.changes().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "summary": {
                                    "previous": "Weekly sync moved",
                                    "current": "Weekly sync moved again"
                                }
                            }""");
                });
        }

        @Test
        void removedOverrideShouldNotifyTheOccurrenceGoingBackToTheSeries() {
            List<NotificationEmailDTO> notifications = request(RECURRING_OLD_NO_EXDATE, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z", "DTSTART:20260408T100000Z")
                        .doesNotContain("RRULE");
                    assertThatJson(notification.changes().orElseThrow().toString())
                        .isObject().containsKeys("summary", "dtstart", "dtend");
                });
        }

        @Test
        void overrideReplacedByAnExDateShouldOnlyCancelTheOccurrence() {
            String current = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_CANCEL);
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z");
                });
        }

        private static final String RECURRING_WITH_BAD_OVERRIDE = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:event-uid@test
            DTSTART;TZID=Europe/Paris:20261013T140000
            DTEND;TZID=Europe/Paris:20261013T150000
            RRULE:FREQ=WEEKLY;BYDAY=TU
            SUMMARY:Weekly meeting
            ORGANIZER:mailto:bob@example.com
            ATTENDEE:mailto:alice@example.com
            END:VEVENT
            BEGIN:VEVENT
            UID:event-uid@test
            RECURRENCE-ID;TZID=Europe/Paris:20261340T140000
            DTSTART;TZID=Europe/Paris:20261020T160000
            DTEND;TZID=Europe/Paris:20261020T170000
            SUMMARY:Weekly meeting moved
            ORGANIZER:mailto:bob@example.com
            ATTENDEE:mailto:alice@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        @Test
        void requestShouldIgnoreOverrideWithUnparsableRecurrenceId() {
            assertThatCode(() -> request(RECURRING_WITH_BAD_OVERRIDE, RECURRING_WITH_BAD_OVERRIDE))
                .doesNotThrowAnyException();
        }

        @Test
        void requestShouldNotifyMasterChangeDespiteOverrideWithUnparsableRecurrenceId() {
            String current = RECURRING_WITH_BAD_OVERRIDE.replace("SUMMARY:Weekly meeting\n", "SUMMARY:Weekly meeting renamed\n");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_WITH_BAD_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.event()).contains("SUMMARY:Weekly meeting renamed")
                        .doesNotContain("RECURRENCE-ID");
                });
        }

        @Test
        void recurrenceRuleAndExDateChangeShouldOnlyInviteAttendeeToTheNewSeries() {
            String current = withExDate(RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=DAILY;COUNT=3"),
                "20260402T100000Z");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_OLD_NO_EXDATE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=DAILY;COUNT=3", "EXDATE:20260402T100000Z");
                });
        }

        @Test
        void recurrenceRuleChangeAndRemovedExDateShouldOnlyInviteAttendeeToTheNewSeries() {
            String previous = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");
            String current = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=WEEKLY;COUNT=4");

            List<NotificationEmailDTO> notifications = request(current, previous);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=WEEKLY;COUNT=4");
                });
        }

        @Test
        void recurrenceRuleChangeWithUntouchedOverrideShouldOnlyInviteAttendeeToTheNewSeries() {
            String current = RECURRING_MASTER_AND_OVERRIDE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=WEEKLY;COUNT=4");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=WEEKLY;COUNT=4")
                        .doesNotContain("RECURRENCE-ID");
                });
        }

        @Test
        void recurrenceRuleChangeWithChangedOverrideShouldInviteAttendeeToTheNewSeriesAndUpdateTheOverride() {
            String current = RECURRING_MASTER_AND_OVERRIDE
                .replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=WEEKLY;COUNT=4")
                .replace("SUMMARY:Weekly sync moved", "SUMMARY:Weekly sync moved again");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).hasSize(2)
                .allSatisfy(notification -> assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST))
                .anySatisfy(notification -> {
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=WEEKLY;COUNT=4");
                })
                .anySatisfy(notification -> {
                    assertThat(notification.isNewEvent()).isFalse();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z", "SUMMARY:Weekly sync moved again");
                });
        }

        @Test
        void recurrenceRuleChangeWithRemovedOverrideShouldOnlyInviteAttendeeToTheNewSeries() {
            String current = RECURRING_OLD_NO_EXDATE.replace("RRULE:FREQ=WEEKLY;COUNT=3", "RRULE:FREQ=DAILY;COUNT=3");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_MASTER_AND_OVERRIDE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RRULE:FREQ=DAILY;COUNT=3");
                });
        }

        @Test
        void unchangedDateExDateOnTimedMasterShouldNotNotifyAnybody() {
            String ics = withDateExDate(RECURRING_OLD_NO_EXDATE, "20260408");

            assertThat(request(ics, ics)).isEmpty();
        }

        @Test
        void addedDateExDateOnTimedMasterShouldCancelTheOccurrenceOfThatDay() {
            String current = withDateExDate(RECURRING_OLD_NO_EXDATE, "20260408");

            List<NotificationEmailDTO> notifications = request(current, RECURRING_OLD_NO_EXDATE);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_CANCEL);
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z");
                });
        }

        @Test
        void removedDateExDateOnTimedMasterShouldInviteAttendeeToTheRestoredOccurrence() {
            String previous = withDateExDate(RECURRING_OLD_NO_EXDATE, "20260408");

            List<NotificationEmailDTO> notifications = request(RECURRING_OLD_NO_EXDATE, previous);

            assertThat(notifications).singleElement()
                .satisfies(notification -> {
                    assertThat(notification.method()).isEqualTo(Method.VALUE_REQUEST);
                    assertThat(notification.isNewEvent()).isTrue();
                    assertThat(notification.event()).contains("RECURRENCE-ID:20260408T100000Z", "DTSTART:20260408T100000Z");
                });
        }

        @Test
        void dateExDateOnTimedMasterShouldMatchTheEquivalentDateTimeExDate() {
            String previous = withDateExDate(RECURRING_OLD_NO_EXDATE, "20260408");
            String current = withExDate(RECURRING_OLD_NO_EXDATE, "20260408T100000Z");

            assertThat(request(current, previous)).isEmpty();
        }

        private List<NotificationEmailDTO> requestWithUnparsableRecurrenceId(String current) {
            Calendar previousCalendar = parseIcs(RECURRING_OLD_NO_EXDATE);
            Calendar currentCalendar = parseIcs(current);
            VEvent override = currentCalendar.<VEvent>getComponents(Component.VEVENT).getLast();
            override.remove(override.getRecurrenceId());
            // Same state as CalendarDiffCalculatorTest: relaxed ICS parsing clears invalid values, so inject a throwing RECURRENCE-ID.
            override.add(new RecurrenceId<Temporal>("20260408T100000Z") {
                @Override
                public Temporal getDate() {
                    throw new DateTimeParseException("Unparsable RECURRENCE-ID", "mailto:john@example.com", 0);
                }
            });

            // The calculator returns both the master and override diffs.
            assertThat(CalendarDiffCalculator.calculate("alice@example.com", currentCalendar, previousCalendar)).hasSize(2);

            // Inject the parsed calendar only at the publisher boundary; keep other CalendarUtil calls real.
            try (MockedStatic<CalendarUtil> calendarUtil = mockStatic(CalendarUtil.class, CALLS_REAL_METHODS)) {
                calendarUtil.when(() -> CalendarUtil.parseIcs(current)).thenReturn(currentCalendar);
                return testee.buildNotificationMessages(dto(Method.VALUE_REQUEST, current, Optional.of(RECURRING_OLD_NO_EXDATE)),
                    EVENT_PATH, Optional.of(previousCalendar));
            }
        }

        private List<NotificationEmailDTO> request(String current, String previous) {
            return testee.buildNotificationMessages(dto(Method.VALUE_REQUEST, current, Optional.of(previous)), EVENT_PATH,
                Optional.of(parseIcs(previous)));
        }

        private String withExDate(String ics, String exDate) {
            return ics.replaceFirst("(RRULE:[^\\n]+\\n)", "$1EXDATE:" + exDate + "\n");
        }

        private String withDateExDate(String ics, String exDate) {
            return ics.replaceFirst("(RRULE:[^\\n]+\\n)", "$1EXDATE;VALUE=DATE:" + exDate + "\n");
        }

    }

    private ItipLocalDeliveryDTO dto(String method, String message, Optional<String> oldMessage) {
        return new ItipLocalDeliveryDTO("mailto:bob@example.com", method, UID, CALENDAR_ID, message,
            oldMessage, true, List.of("mailto:alice@example.com"));
    }
}
