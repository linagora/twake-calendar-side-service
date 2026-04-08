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
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.ItipEmailNotificationPublisher.NotificationEmailDTO;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.property.Method;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class ItipEmailNotificationPublisherTest {

    private static final String UID = "event-uid@test";
    private static final String CALENDAR_ID = "team-calendar";
    private static final URI EVENT_PATH = URI.create("/calendars/openpaas/team-calendar/event-uid@test.ics");

    private ItipEmailNotificationPublisher testee;

    @BeforeEach
    void setUp() {
        testee = new ItipEmailNotificationPublisher(
            mock(Sender.class),
            body -> new OutboundMessage("exchange", "routingKey", body));
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
                    assertThat(notification.shouldNotify()).isTrue();
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
                    assertThat(notification.shouldNotify()).isTrue();
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

    }

    private ItipLocalDeliveryDTO dto(String method, String message, Optional<String> oldMessage) {
        return new ItipLocalDeliveryDTO("mailto:bob@example.com", method, UID, CALENDAR_ID, message,
            oldMessage, true, List.of("mailto:alice@example.com"));
    }
}
