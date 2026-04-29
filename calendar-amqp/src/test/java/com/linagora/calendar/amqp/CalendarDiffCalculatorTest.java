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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.CalendarDiffCalculator.EventDiff;
import com.linagora.calendar.amqp.CalendarDiffCalculator.StringPropertyChange;
import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;

class CalendarDiffCalculatorTest {

    private static final String RECIPIENT = "bob@example.com";

    private static Calendar parse(String ics) {
        return CalendarUtil.parseIcs(ics);
    }

    @Nested
    class RecurringRequestUpdate {

        private static final String MASTER_AND_EXCEPTION_OLD = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            METHOD:REQUEST
            
            BEGIN:VEVENT
            UID:uid-recur@test
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            RRULE:FREQ=WEEKLY
            SUMMARY:Weekly sync
            SEQUENCE:1
            ATTENDEE:mailto:bob@example.com
            ORGANIZER:mailto:alice@example.com
            END:VEVENT
            
            BEGIN:VEVENT
            UID:uid-recur@test
            RECURRENCE-ID:20260408T100000Z
            DTSTART:20260408T120000Z
            DTEND:20260408T130000Z
            SUMMARY:Weekly sync (moved)
            SEQUENCE:1
            ATTENDEE:mailto:bob@example.com
            ORGANIZER:mailto:alice@example.com
            END:VEVENT
            
            END:VCALENDAR
            """;

        @Test
        void unchangedRecurringUpdateReturnsNoDiff() {
            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(MASTER_AND_EXCEPTION_OLD), parse(MASTER_AND_EXCEPTION_OLD));

            assertThat(diffs).isEmpty();
        }

        @Test
        void changedExceptionReturnsOneDiffWithSummaryChange() {
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Weekly sync
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T120000Z
                DTEND:20260408T130000Z
                SUMMARY:Weekly sync UPDATED
                SEQUENCE:2
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                END:VCALENDAR
                """;

            List<EventDiff> eventDiffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(MASTER_AND_EXCEPTION_OLD));

            assertThat(eventDiffs).hasSize(1);
            EventDiff eventDiff = eventDiffs.getFirst();
            StringPropertyChange summaryChange = (StringPropertyChange) eventDiff.changes().orElseThrow().getFirst();

            assertSoftly(softly -> {
                softly.assertThat(eventDiff.isNewEvent()).isFalse();
                softly.assertThat(summaryChange.propertyName()).isEqualTo(Property.SUMMARY);
                softly.assertThat(summaryChange.previous()).isEqualTo("Weekly sync (moved)");
                softly.assertThat(summaryChange.current()).isEqualTo("Weekly sync UPDATED");
                softly.assertThat(eventDiff.vevent().toString())
                    .isEqualToIgnoringNewLines("""
                        BEGIN:VEVENT
                        UID:uid-recur@test
                        RECURRENCE-ID:20260408T100000Z
                        DTSTART:20260408T120000Z
                        DTEND:20260408T130000Z
                        SUMMARY:Weekly sync UPDATED
                        SEQUENCE:2
                        ATTENDEE:mailto:bob@example.com
                        ORGANIZER:mailto:alice@example.com
                        END:VEVENT""".trim());
            });
        }

        @Test
        void changedMasterAndExceptionReturnsTwoDiffs() {
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Weekly sync UPDATED
                SEQUENCE:2
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T120000Z
                DTEND:20260408T130000Z
                SUMMARY:Weekly sync (moved) UPDATED
                SEQUENCE:2
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(MASTER_AND_EXCEPTION_OLD));

            assertThat(diffs).hasSize(2)
                .allSatisfy(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThat(diff.changes()).isPresent();
                });
        }

        @Test
        void newExceptionComparedAgainstMasterStillProducesDiff() {
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-recur2@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Weekly sync
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur2@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Weekly sync
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur2@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260409T100000Z
                DTEND:20260409T110000Z
                SUMMARY:Weekly sync (moved to Wednesday)
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThat(diff.changes()).isPresent();
                });
        }

        @Test
        void recurringOverrideShouldUseOccurrenceAsPreviousDateTime() {
            // Given: A recurring DAILY event (COUNT=3) without existing override in the old calendar
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-recur-daily@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:Daily standup
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            // When: A new override occurrence is added on day 2 with a shifted DTSTART/DTEND
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur-daily@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:Daily standup
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur-daily@test
                RECURRENCE-ID:20260402T100000Z
                DTSTART:20260402T150000Z
                DTEND:20260402T160000Z
                SUMMARY:Daily standup
                SEQUENCE:2
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(oldIcs));

            // Then: The diff computes `previous` from the day-2 master occurrence, not from day-1 master DTSTART/DTEND
            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThatJson(diff.serializeChanges().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "dtstart": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 10:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 15:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                },
                                "dtend": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 11:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 16:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                }
                            }""");
                });
        }

        @Test
        void existingRecurringOverrideDateTimeUpdateShouldUsePreviousOverrideDateTime() {
            // Given: A recurring DAILY event (COUNT=3) with an existing override occurrence on day 2
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-recur-daily-update@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:Daily standup
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                BEGIN:VEVENT
                UID:uid-recur-daily-update@test
                RECURRENCE-ID:20260402T100000Z
                DTSTART:20260402T150000Z
                DTEND:20260402T160000Z
                SUMMARY:Daily standup
                SEQUENCE:2
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            // When: The same override occurrence is updated with a new DTSTART/DTEND
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-recur-daily-update@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:Daily standup
                SEQUENCE:1
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                BEGIN:VEVENT
                UID:uid-recur-daily-update@test
                RECURRENCE-ID:20260402T100000Z
                DTSTART:20260402T170000Z
                DTEND:20260402T180000Z
                SUMMARY:Daily standup
                SEQUENCE:3
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(oldIcs));

            // Then: The diff uses the previous override datetime as `previous`, not the master occurrence datetime
            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThatJson(diff.serializeChanges().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "dtstart": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 15:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 17:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                },
                                "dtend": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 16:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-02 18:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                }
                            }""");
                });
        }

        @Test
        void newExceptionWithRecipientOnlyInExceptionMarksIsNewEvent() {
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-recur3@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Private weekly
                SEQUENCE:1
                ATTENDEE:mailto:charlie@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur3@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Private weekly
                SEQUENCE:1
                ATTENDEE:mailto:charlie@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur3@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T100000Z
                DTEND:20260408T110000Z
                SUMMARY:Private weekly
                SEQUENCE:1
                ATTENDEE:mailto:charlie@example.com
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).singleElement()
                .satisfies(diff -> assertThat(diff.isNewEvent()).isTrue());
        }

        @Test
        void dateTimeChangeInExceptionKeepsExpectedMetadata() {
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur4@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Weekly sync
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur4@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T120000Z
                DTEND:20260408T130000Z
                SUMMARY:Weekly sync
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                
                BEGIN:VEVENT
                UID:uid-recur4@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                RRULE:FREQ=WEEKLY
                SUMMARY:Weekly sync
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                
                BEGIN:VEVENT
                UID:uid-recur4@test
                RECURRENCE-ID:20260408T100000Z
                DTSTART:20260408T140000Z
                DTEND:20260408T150000Z
                SUMMARY:Weekly sync
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).singleElement()
                .satisfies(diff ->
                    assertThatJson(diff.serializeChanges().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "dtstart": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-08 12:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-08 14:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                },
                                "dtend": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-08 13:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-08 15:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                }
                            }"""));
        }
    }

    @Nested
    class SingleEventDiff {

        private static final String BASE_ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:uid-single@test
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            SUMMARY:Team meeting
            LOCATION:Room A
            DESCRIPTION:Discuss roadmap
            ATTENDEE:mailto:bob@example.com
            ORGANIZER:mailto:alice@example.com
            END:VEVENT
            END:VCALENDAR
            """;

        @Test
        void recipientNotAttendingReturnsNoDiff() {
            String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-single@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Team meeting
                ATTENDEE:mailto:charlie@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(ics), parse(ics));

            assertThat(diffs).isEmpty();
        }

        @Test
        void newRecipientAddedMarksIsNewEvent() {
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-single@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Team meeting
                ATTENDEE:mailto:charlie@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(BASE_ICS), parse(oldIcs));

            assertThat(diffs).singleElement()
                .satisfies(diff -> assertThat(diff.isNewEvent()).isTrue());
        }

        @Test
        void summaryChangeReturnsDiff() {
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-single@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Team meeting UPDATED
                LOCATION:Room A
                DESCRIPTION:Discuss roadmap
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(BASE_ICS));

            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThatJson(diff.serializeChanges().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "summary": {
                                    "previous": "Team meeting",
                                    "current": "Team meeting UPDATED"
                                }
                            }""");
                });
        }

        @Test
        void locationChangeReturnsDiff() {
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-single@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Team meeting
                LOCATION:Room B
                DESCRIPTION:Discuss roadmap
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(BASE_ICS));

            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThatJson(diff.serializeChanges().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "location": {
                                    "previous": "Room A",
                                    "current": "Room B"
                                }
                            }""");
                });
        }

        @Test
        void dtendChangeReturnsDiff() {
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-single@test
                DTSTART:20260401T100000Z
                DTEND:20260401T120000Z
                SUMMARY:Team meeting
                LOCATION:Room A
                DESCRIPTION:Discuss roadmap
                ATTENDEE:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(BASE_ICS));

            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThatJson(diff.serializeChanges().orElseThrow().toString())
                        .isEqualTo("""
                            {
                                "dtend": {
                                    "previous": {
                                        "isAllDay": false,
                                        "date": "2026-04-01 11:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    },
                                    "current": {
                                        "isAllDay": false,
                                        "date": "2026-04-01 12:00:00.000000",
                                        "timezone_type": 3,
                                        "timezone": "Z"
                                    }
                                }
                            }""");
                });
        }
    }

    @Nested
    class OrganizerAcceptedTransition {

        @Test
        void organizerTransitionFromNeedsActionToAcceptedReturnsDiff() {
            String oldIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-organizer@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Team meeting
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:alice@example.com
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;
            String newIcs = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Test//Test//EN
                METHOD:REQUEST
                BEGIN:VEVENT
                UID:uid-organizer@test
                DTSTART:20260401T100000Z
                DTEND:20260401T110000Z
                SUMMARY:Team meeting
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:alice@example.com
                ATTENDEE;PARTSTAT=ACCEPTED:mailto:bob@example.com
                ORGANIZER:mailto:alice@example.com
                END:VEVENT
                END:VCALENDAR
                """;

            List<EventDiff> diffs = CalendarDiffCalculator.calculate(
                RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).singleElement()
                .satisfies(diff -> {
                    assertThat(diff.isNewEvent()).isFalse();
                    assertThat(diff.serializeChanges()).isEmpty();
                });
        }
    }
}
