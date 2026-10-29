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

package com.linagora.calendar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;

class CalendarUtilTest {

    private static final String ICS_WITHOUT_METHOD = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Twake Calendar//Public Booking//EN
        BEGIN:VEVENT
        UID:8e4d5652-3525-4511-b660-1873ba3df559
        DTSTAMP:20260707T094407Z
        DTSTART:20260710T150000Z
        SUMMARY:Test
        END:VEVENT
        END:VCALENDAR
        """;

    private static final String ICS_WITH_TIMEZONE_TEMPLATE = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Sabre//Sabre VObject 5.0.0//EN
        CALSCALE:GREGORIAN
        BEGIN:VTIMEZONE
        TZID:%s
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
        UID:00000000-0000-0000-0000-000000000001
        DTSTAMP:20261023T113502Z
        DTSTART;TZID=%s:20261026T114500
        DTEND;TZID=%s:20261026T123000
        SUMMARY:Test
        END:VEVENT
        END:VCALENDAR
        """;

    private static final String ICS_WITH_UNKNOWN_PROPERTY = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:event-1
        DTSTART:20251020T100000Z
        MSUMARY:All day
        END:VEVENT
        BEGIN:VEVENT
        UID:event-2
        DTSTART:20251021T100000Z
        SUMMARY:Other
        END:VEVENT
        END:VCALENDAR
        """;

    private static String icsWithTimezone(String tzId) {
        return ICS_WITH_TIMEZONE_TEMPLATE.formatted(tzId, tzId, tzId);
    }

    private static Instant start(Calendar calendar) {
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();
        return ((ZonedDateTime) vEvent.getDateTimeStart().getDate()).toInstant();
    }

    @Test
    void parseIcsShouldNotExhaustTimezoneIdPool() {
        // ical4j allocates one id per registered VTIMEZONE out of a pool of 1500, released only once
        // the registry is garbage collected. Parsed calendars reference their registry, so retaining them pins those ids.
        List<Calendar> retainedCalendars = new ArrayList<>();

        assertThatCode(() -> {
            for (int i = 0; i < 2000; i++) {
                retainedCalendars.add(CalendarUtil.parseIcs(icsWithTimezone("Europe/Paris")));
            }
        }).doesNotThrowAnyException();
        assertThat(retainedCalendars).hasSize(2000);
    }

    @Test
    void parseIcsShouldResolveKnownTimezone() {
        Calendar calendar = CalendarUtil.parseIcs(icsWithTimezone("Europe/Paris"));

        assertThat(start(calendar)).isEqualTo(Instant.parse("2026-10-26T10:45:00Z"));
    }

    @Test
    void parseIcsShouldResolveWindowsTimezoneAlias() {
        Calendar calendar = CalendarUtil.parseIcs(icsWithTimezone("Romance Standard Time"));

        assertThat(start(calendar)).isEqualTo(Instant.parse("2026-10-26T10:45:00Z"));
    }

    @Test
    void parseIcsShouldResolveCustomTimezoneFromItsDefinition() {
        Calendar calendar = CalendarUtil.parseIcs(icsWithTimezone("Custom Linagora Time"));

        assertThat(start(calendar)).isEqualTo(Instant.parse("2026-10-26T10:45:00Z"));
    }

    @Test
    void withMethodShouldAddMethodWhenMissing() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITHOUT_METHOD);

        assertThat(CalendarUtil.withMethod(calendar, ImmutableMethod.REQUEST).toString())
            .contains("METHOD:REQUEST");
    }

    @Test
    void withMethodShouldOverrideExistingMethod() {
        Calendar calendar = CalendarUtil.withMethod(CalendarUtil.parseIcs(ICS_WITHOUT_METHOD), ImmutableMethod.REQUEST);

        assertThat(CalendarUtil.withMethod(calendar, ImmutableMethod.CANCEL).toString())
            .contains("METHOD:CANCEL")
            .doesNotContain("METHOD:REQUEST");
    }

    @Test
    void withMethodShouldNotMutateSuppliedCalendar() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITHOUT_METHOD);

        CalendarUtil.withMethod(calendar, ImmutableMethod.REQUEST);

        assertThat(calendar.toString())
            .doesNotContain("METHOD:");
    }

    @Test
    void withMethodShouldSupportUnknownProperties() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITH_UNKNOWN_PROPERTY);

        assertThat(CalendarUtil.withMethod(calendar, ImmutableMethod.REQUEST).toString())
            .contains("METHOD:REQUEST")
            .contains("MSUMARY:All day");
    }

    @Test
    void withVEventsShouldSupportUnknownProperties() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITH_UNKNOWN_PROPERTY);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();

        assertThat(CalendarUtil.withSingleVEvent(calendar, vEvent).toString())
            .contains("UID:event-1")
            .contains("MSUMARY:All day")
            .doesNotContain("UID:event-2");
    }

    @Test
    void withVEventsShouldKeepTimezones() {
        Calendar calendar = CalendarUtil.parseIcs(icsWithTimezone("Custom Linagora Time"));
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();

        assertThat(CalendarUtil.withSingleVEvent(calendar, vEvent).toString())
            .contains("BEGIN:VTIMEZONE")
            .contains("TZID:Custom Linagora Time");
    }

    @Test
    void withVEventsShouldNotMutateTemplate() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITH_UNKNOWN_PROPERTY);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();

        Calendar result = CalendarUtil.withSingleVEvent(calendar, vEvent);
        result.add(ImmutableMethod.CANCEL);

        assertThat(calendar.toString())
            .contains("UID:event-2")
            .doesNotContain("METHOD:");
    }

    @Test
    void withSingleVEventShouldIgnoreUnparsableTemplateVEvents() {
        Calendar template = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:event-uid@test
            DTSTART;TZID=Europe/Paris:20261013T140000
            RRULE:FREQ=WEEKLY;BYDAY=TU
            SUMMARY:Weekly meeting
            END:VEVENT
            BEGIN:VEVENT
            UID:event-uid@test
            RECURRENCE-ID;TZID=Europe/Paris:20261340T140000
            DTSTART;TZID=Europe/Paris:20261020T160000
            SUMMARY:Weekly meeting moved
            END:VEVENT
            END:VCALENDAR
            """);
        VEvent master = (VEvent) template.getComponents(Component.VEVENT).getFirst();

        Calendar calendar = CalendarUtil.withSingleVEvent(template, master);

        assertThat(calendar.toString())
            .contains("METHOD:REQUEST", "SUMMARY:Weekly meeting")
            .doesNotContain("RECURRENCE-ID", "Weekly meeting moved");
    }
}
