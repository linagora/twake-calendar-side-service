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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.CalendarDiffCalculator.DateTimePropertyChange;
import com.linagora.calendar.amqp.CalendarDiffCalculator.EventDiff;
import com.linagora.calendar.amqp.CalendarDiffCalculator.PropertyChange;
import com.linagora.calendar.amqp.CalendarDiffCalculator.StringPropertyChange;
import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;

class CalendarDiffCalculatorTest {

    private static final String RECIPIENT = "bob@example.com";

    private CalendarDiffCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CalendarDiffCalculator();
    }

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
            List<EventDiff> diffs = calculator.calculate(
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

            List<EventDiff> diffs = calculator.calculate(
                RECIPIENT, parse(newIcs), parse(MASTER_AND_EXCEPTION_OLD));

            assertThat(diffs).hasSize(1);
            EventDiff diff = diffs.get(0);
            assertThat(diff.isNewEvent()).isFalse();
            assertThat(diff.vevent().getProperty(Property.RECURRENCE_ID)).isPresent();
            assertThat(diff.changes()).isPresent();
            assertThat(diff.changes().get()).singleElement()
                .isInstanceOfSatisfying(StringPropertyChange.class, change -> {
                    assertThat(change.propertyName()).isEqualTo(Property.SUMMARY);
                    assertThat(change.previousValue().value()).isEqualTo("Weekly sync (moved)");
                    assertThat(change.currentValue().value()).isEqualTo("Weekly sync UPDATED");
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

            List<EventDiff> diffs = calculator.calculate(
                RECIPIENT, parse(newIcs), parse(MASTER_AND_EXCEPTION_OLD));

            assertThat(diffs).hasSize(2);
            assertThat(diffs).allMatch(diff -> !diff.isNewEvent());
            assertThat(diffs).allMatch(diff -> diff.changes().isPresent());
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

            List<EventDiff> diffs = calculator.calculate(RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).isNewEvent()).isFalse();
            assertThat(diffs.get(0).changes()).isPresent();
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

            List<EventDiff> diffs = calculator.calculate(RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).isNewEvent()).isTrue();
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

            List<EventDiff> diffs = calculator.calculate(RECIPIENT, parse(newIcs), parse(oldIcs));

            assertThat(diffs).hasSize(1);
            assertThat(diffs.get(0).changes()).isPresent();

            List<PropertyChange> changes = diffs.get(0).changes().get();
            PropertyChange dtStartChange = changes.stream()
                .filter(change -> change.propertyName().equals(Property.DTSTART))
                .findFirst()
                .orElseThrow();

            assertThat(dtStartChange).isInstanceOfSatisfying(DateTimePropertyChange.class, change -> {
                assertThat(change.previousValue().timezoneType()).isEqualTo(3);
                assertThat(change.previousValue().isAllDay()).isFalse();
                assertThat(change.previousValue().date()).isEqualTo("2026-04-08 12:00:00.000000");
                assertThat(change.currentValue().date()).isEqualTo("2026-04-08 14:00:00.000000");
            });
        }
    }
}
