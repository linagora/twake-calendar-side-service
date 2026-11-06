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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;


class CalendarUtilTest {
    @ParameterizedTest
    @ValueSource(strings = {"BEFORE", "IN_VEVENT", "IN_VALARM"})
    void renderingShouldKeepABackslashNOfANonTextExtensionOnItsLine(String place) {
        String property = "X-FOO;VALUE=URI:file:///share/new\\notes";
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            %s
            BEGIN:VEVENT
            UID:a
            DTSTART:20260401T100000Z
            %s
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:DISPLAY
            %s
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.formatted(place.equals("BEFORE") ? property : "PRODID:x", place.equals("IN_VEVENT") ? property : "SUMMARY:x", place.equals("IN_VALARM") ? property : "DESCRIPTION:x");

        String rendered = CalendarUtil.parseIcs(ics).toString();

        assertThat(rendered).contains(property);
        assertThat(CalendarUtil.parseIcs(rendered).toString()).isEqualTo(rendered);
    }

    @Test
    void renderingShouldKeepABackslashNOfANonTextUnknownPropertyWithoutXPrefixOnItsLine() {
        // Given an event carrying an unknown property, without the X- prefix, whose DATE value holds a backslash-n
        String property = "UEXDATE;VALUE=DATE:ID:recurri\\nng-ui.comb@example.com";
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            DTSTART:20260401T100000Z
            %s
            END:VEVENT
            END:VCALENDAR
            """.formatted(property);

        // When the parsed calendar is rendered
        String rendered = CalendarUtil.parseIcs(ics).toString();

        // Then the property is written back as received, and the rendering parses back to itself
        assertThat(rendered).contains(property);
        assertThat(CalendarUtil.parseIcs(rendered).toString()).isEqualTo(rendered);
    }

    @Test
    void textExtensionsShouldStillBeDecoded() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            X-FOO:first\\nsecond
            X-BAR;VALUE=TEXT:first\\nsecond
            END:VEVENT
            END:VCALENDAR
            """;

        Component event = CalendarUtil.parseIcs(ics).getComponents().getFirst();

        assertThat(event.getProperty("X-FOO").orElseThrow().getValue()).isEqualTo("first\nsecond");
        assertThat(event.getProperty("X-BAR").orElseThrow().getValue()).isEqualTo("first\nsecond");
    }


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

    private static final String ICS_WITH_MIN_MAX_VERSION = """
        BEGIN:VCALENDAR
        VERSION:1.0;2.0
        PRODID:-//Test//EN
        BEGIN:VEVENT
        UID:a
        DTSTART:20251020T100000Z
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

    private static String icsWithObservanceRule(String dtStart, String rRule) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:Custom/Zone
            BEGIN:STANDARD
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            DTSTART:%s
            RRULE:%s
            END:STANDARD
            BEGIN:DAYLIGHT
            TZOFFSETFROM:+0100
            TZOFFSETTO:+0200
            DTSTART:%s
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:00000000-0000-0000-0000-000000000001
            DTSTART:20261026T114500Z
            SUMMARY:Test
            END:VEVENT
            END:VCALENDAR
            """.formatted(dtStart, rRule, dtStart);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void parseIcsShouldNotExpandSubDailyVTimezoneObservances() {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:Custom/Zone
            BEGIN:STANDARD
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            DTSTART:19701025T030000
            RRULE:FREQ=MINUTELY
            END:STANDARD
            END:VTIMEZONE
            END:VCALENDAR
            """;

        assertThatCode(() -> CalendarUtil.parseIcs(ics)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=SECONDLY",
        "FREQ=MINUTELY",
        "FREQ=HOURLY;BYMINUTE=0,30",
        "FREQ=DAILY",
        "FREQ=WEEKLY",
        "FREQ=YEARLY;BYHOUR=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23",
        "FREQ=YEARLY;BYYEARDAY=1,2,3,4,5,6,7,8,9,10",
        "FREQ=MONTHLY;BYMONTHDAY=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28"})
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void parseIcsShouldNotRegisterTimezoneWithTooManyTransitions(String rRule) {
        Calendar calendar = CalendarUtil.parseIcs(icsWithObservanceRule("16010101T030000", rRule));

        assertThat(CalendarUtil.CustomizedTimeZoneRegistry.hasBoundedObservances(
            (VTimeZone) calendar.getComponent(Component.VTIMEZONE).orElseThrow())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
        "FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=8,9,10,11,12,13,14;BYDAY=SU"})
    void parseIcsShouldRegisterRealWorldTimezonesSinceOutlookEpoch(String rRule) {
        Calendar calendar = CalendarUtil.parseIcs(icsWithObservanceRule("16010101T030000", rRule));

        assertThat(CalendarUtil.CustomizedTimeZoneRegistry.hasBoundedObservances(
            (VTimeZone) calendar.getComponent(Component.VTIMEZONE).orElseThrow())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=YEARLY;BYMONTH=10;BYDAY=SU;BYSETPOS=-1",
        "FREQ=YEARLY;BYMONTH=10",
        "FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=-30;BYDAY=SU"})
    void parseIcsShouldNotFailOnObservancesIcal4jCannotTurnIntoRules(String rRule) {
        Calendar calendar = CalendarUtil.parseIcs(icsWithObservanceRule("19701025T030000", rRule));

        assertThat(calendar.getComponent(Component.VEVENT)).isPresent();
    }

    @Test
    void parseIcsShouldResolveCustomTimezoneSinceOutlookEpoch() {
        Calendar calendar = CalendarUtil.parseIcs(icsWithTimezone("Custom Linagora Time")
            .replace("DTSTART:19700329T020000", "DTSTART:16010325T020000")
            .replace("DTSTART:19701025T030000", "DTSTART:16011028T030000"));

        assertThat(start(calendar)).isEqualTo(Instant.parse("2026-10-26T10:45:00Z"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parseIcsShouldRegisterABoundedNumberOfCustomTimezones() {
        String vTimeZones = IntStream.range(0, 2000)
            .mapToObj(i -> """
                BEGIN:VTIMEZONE
                TZID:Custom/Zone-%d
                BEGIN:STANDARD
                TZOFFSETFROM:+0200
                TZOFFSETTO:+0100
                DTSTART:19701025T030000
                RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
                END:STANDARD
                END:VTIMEZONE
                """.formatted(i))
            .collect(Collectors.joining());
        String ics = "BEGIN:VCALENDAR\nVERSION:2.0\n" + vTimeZones + "END:VCALENDAR\n";

        // Each registered custom zone takes an id from a JVM wide pool of 1500: the next parse must still succeed
        assertThatCode(() -> CalendarUtil.parseIcs(ics)).doesNotThrowAnyException();
        assertThat(start(CalendarUtil.parseIcs(icsWithTimezone("Custom Linagora Time"))))
            .isEqualTo(Instant.parse("2026-10-26T10:45:00Z"));
    }

    private static String customVTimeZone(String tzId, String dtStart, String rRule) {
        return """
            BEGIN:VTIMEZONE
            TZID:%s
            BEGIN:STANDARD
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            DTSTART:%s
            RRULE:%s
            END:STANDARD
            END:VTIMEZONE
            """.formatted(tzId, dtStart, rRule);
    }

    private static CalendarUtil.CustomizedTimeZoneRegistry registryOf(String vTimeZones) {
        CalendarUtil.CustomizedTimeZoneRegistry registry = new CalendarUtil.CustomizedTimeZoneRegistry();
        CalendarUtil.parseIcs("BEGIN:VCALENDAR\nVERSION:2.0\n" + vTimeZones + "END:VCALENDAR\n")
            .<VTimeZone>getComponents(Component.VTIMEZONE)
            .forEach(vTimeZone -> registry.register(new TimeZone(vTimeZone)));
        return registry;
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parseIcsShouldBoundTheExpansionOfCustomTimezonesAcrossTheCalendar() {
        String vTimeZones = IntStream.range(0, 1000)
            .mapToObj(i -> customVTimeZone("Custom/Zone-" + i, "09850512T030000", "FREQ=YEARLY;BYDAY=-1SU"))
            .collect(Collectors.joining());
        String ics = "BEGIN:VCALENDAR\nVERSION:2.0\n" + vTimeZones + "END:VCALENDAR\n";

        assertThatCode(() -> CalendarUtil.parseIcs(ics)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "09850512T030000|FREQ=YEARLY;BYDAY=-1SU",
        "19730101T030000|FREQ=YEARLY;BYDAY=SU,MO,TU,WE,TH,FR,SA",
        "19730101T030000|FREQ=MONTHLY;BYDAY=SU,MO,TU,WE,TH,FR,SA",
        "19730101T030000|FREQ=YEARLY;BYDAY=SU,MO,TU,WE,TH,FR,SA;BYSETPOS=-1",
        "16010101T030000|FREQ=YEARLY"})
    void parseIcsShouldNotRegisterTimezoneWhoseExpansionVisitsTooManyCandidates(String dtStartAndRule) {
        String[] parts = dtStartAndRule.split("\\|");
        Calendar calendar = CalendarUtil.parseIcs("BEGIN:VCALENDAR\nVERSION:2.0\n"
            + customVTimeZone("Custom/Zone", parts[0], parts[1]) + "END:VCALENDAR\n");

        assertThat(CalendarUtil.CustomizedTimeZoneRegistry.hasBoundedObservances(
            (VTimeZone) calendar.getComponent(Component.VTIMEZONE).orElseThrow())).isFalse();
    }

    @Test
    void customTimezonesBeyondTheCalendarWideExpansionLimitShouldNotBeRegistered() {
        String rRule = "FREQ=YEARLY;BYDAY=-1SU";

        CalendarUtil.CustomizedTimeZoneRegistry registry = registryOf(
            customVTimeZone("Custom/First", "18000512T030000", rRule) + customVTimeZone("Custom/Second", "18000512T030000", rRule));

        assertThat(registry.getTimeZone("Custom/First")).isNotNull();
        assertThat(registry.getTimeZone("Custom/Second")).isNull();
    }

    @Test
    void expansionLimitShouldApplyToEachRegistryIndependently() {
        String vTimeZone = customVTimeZone("Custom/Zone", "18000512T030000", "FREQ=YEARLY;BYDAY=-1SU");

        assertThat(registryOf(vTimeZone).getTimeZone("Custom/Zone")).isNotNull();
        assertThat(registryOf(vTimeZone).getTimeZone("Custom/Zone")).isNotNull();
    }

    @Test
    void parseIcsShouldResolveARealisticNumberOfCustomTimezones() {
        String otherZones = IntStream.range(0, 20)
            .mapToObj(i -> """
                BEGIN:VTIMEZONE
                TZID:Custom/Other-%d
                BEGIN:DAYLIGHT
                TZOFFSETFROM:+0100
                TZOFFSETTO:+0200
                DTSTART:19700329T020000
                RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
                END:DAYLIGHT
                BEGIN:STANDARD
                TZOFFSETFROM:+0200
                TZOFFSETTO:+0100
                DTSTART:19701025T030000
                RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
                END:STANDARD
                END:VTIMEZONE
                """.formatted(i))
            .collect(Collectors.joining());

        Calendar calendar = CalendarUtil.parseIcs(icsWithTimezone("Custom Linagora Time")
            .replace("DTSTART:19700329T020000", "DTSTART:16010325T020000")
            .replace("DTSTART:19701025T030000", "DTSTART:16011028T030000")
            .replace("BEGIN:VTIMEZONE", otherZones + "BEGIN:VTIMEZONE"));

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
    void copyShouldKeepUnreadableDateListValues() {
        VEvent vEvent = firstVEventWithTimezone("EXDATE;TZID=Europe/Paris:20351341T140000");

        assertThat(CalendarUtil.copy(vEvent).getProperty(Property.EXDATE)).get()
            .isSameAs(vEvent.getProperty(Property.EXDATE).orElseThrow());
    }

    @Test
    void copyShouldKeepDatePropertiesWithAnEmptyValue() {
        VEvent vEvent = firstVEventWithTimezone("DTSTART;TZID=Europe/Paris:");

        assertThat(CalendarUtil.copy(vEvent).getProperty(Property.DTSTART)).get()
            .isSameAs(vEvent.getProperty(Property.DTSTART).orElseThrow());
    }

    private static VEvent firstVEventWithTimezone(String extraProperty) {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            BEGIN:VTIMEZONE
            TZID:Europe/Paris
            BEGIN:STANDARD
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            DTSTART:19701025T030000
            RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:event-uid@test
            %s
            SUMMARY:Weekly meeting
            END:VEVENT
            END:VCALENDAR
            """.formatted(extraProperty));
        return (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();
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

    @Test
    void copyShouldKeepThePropertyGroupPrefix() {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            DTSTART:20251020T100000Z
            X-APPLE.FOO:bar
            END:VEVENT
            END:VCALENDAR
            """);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();

        VEvent copy = CalendarUtil.copy(vEvent);

        assertThat(copy.toString()).isEqualTo(vEvent.toString());
    }

    @Test
    void copyShouldKeepTheGroupPrefixOfKnownProperties() {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            DTSTART:20251020T100000Z
            SUMMARY:Weekly meeting
            A.SUMMARY:Grouped summary
            END:VEVENT
            END:VCALENDAR
            """);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();

        VEvent copy = CalendarUtil.copy(vEvent);

        assertThat(copy.toString()).isEqualTo(vEvent.toString());
    }

    @Test
    void copyShouldKeepGroupedPropertiesNamedAsComponentDelimiters() {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            DTSTART:20251020T100000Z
            D.END:Weekly meeting moved
            END:VEVENT
            END:VCALENDAR
            """);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();

        Calendar result = CalendarUtil.withSingleVEvent(calendar, CalendarUtil.copy(vEvent));

        assertThat(CalendarUtil.parseIcs(result.toString()).toString())
            .contains("D.END:Weekly meeting moved");
    }

    @Test
    void copyShouldKeepNonAlarmSubComponents() {
        VEvent vEvent = firstVEvent("""
            BEGIN:VLOCATION
            UID:room-1@example.com
            NAME:Room 1
            END:VLOCATION
            """);

        VEvent copy = CalendarUtil.copy(vEvent);

        assertThat(copy.toString()).isEqualTo(vEvent.toString());
    }

    @Test
    void copyShouldKeepNestedSubComponents() {
        VEvent vEvent = firstVEvent("""
            BEGIN:PARTICIPANT
            UID:participant-1@example.com
            PARTICIPANT-TYPE:ACTIVE
            BEGIN:VLOCATION
            UID:room-1@example.com
            NAME:Room 1
            END:VLOCATION
            END:PARTICIPANT
            """);

        VEvent copy = CalendarUtil.copy(vEvent);

        assertThat(copy.toString()).isEqualTo(vEvent.toString());
    }

    @Test
    void copyShouldKeepUnknownSubComponents() {
        VEvent vEvent = firstVEvent("""
            BEGIN:X-FOO
            X-BAR:baz
            END:X-FOO
            """);

        VEvent copy = CalendarUtil.copy(vEvent);

        assertThat(copy.toString()).isEqualTo(vEvent.toString());
    }

    @Test
    void copyShouldKeepSubComponentsOrder() {
        VEvent vEvent = firstVEvent("""
            BEGIN:VLOCATION
            UID:room-1@example.com
            NAME:Room 1
            END:VLOCATION
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            """);

        VEvent copy = CalendarUtil.copy(vEvent);

        assertThat(copy.toString()).isEqualTo(vEvent.toString());
    }

    @Test
    void copyShouldNotShareSubComponents() {
        VEvent vEvent = firstVEvent("""
            BEGIN:VLOCATION
            UID:room-1@example.com
            NAME:Room 1
            END:VLOCATION
            """);

        VEvent copy = CalendarUtil.copy(vEvent);
        copy.getComponentList().getAll().getFirst().add(new XProperty("X-ADDED", "value"));

        assertThat(vEvent.toString()).doesNotContain("X-ADDED");
    }

    private static VEvent firstVEvent(String subComponents) {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Calendar//EN
            BEGIN:VEVENT
            UID:vlocation@example.com
            DTSTAMP:20251001T080000Z
            DTSTART:20251020T100000Z
            DURATION:PT1H
            RRULE:FREQ=WEEKLY;COUNT=3
            SUMMARY:Weekly
            %sEND:VEVENT
            END:VCALENDAR
            """.formatted(subComponents));
        return (VEvent) calendar.getComponent(Component.VEVENT).orElseThrow();
    }

    @Test
    void copyShouldNotFailOnVersionWithSemicolon() {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            DTSTART:20251020T100000Z
            VERSION:A;B
            END:VEVENT
            END:VCALENDAR
            """);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThatCode(() -> CalendarUtil.copy(vEvent)).doesNotThrowAnyException();
    }

    @Test
    void copyShouldKeepVEventVersionWithSemicolon() {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:a
            DTSTART:20251020T100000Z
            VERSION:1.0;2.0
            END:VEVENT
            END:VCALENDAR
            """);
        VEvent vEvent = (VEvent) calendar.getComponent(Component.VEVENT).get();

        assertThat(CalendarUtil.copy(vEvent).getProperty(Property.VERSION).get().getValue())
            .isEqualTo("1.0;2.0");
    }

    @Test
    void parseIcsShouldKeepAMinMaxVersion() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITH_MIN_MAX_VERSION);

        assertThat(calendar.getProperty(Property.VERSION).get().getValue())
            .isEqualTo("1.0;2.0");
    }

    @Test
    void copyOfShouldKeepAMinMaxVersion() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITH_MIN_MAX_VERSION);

        assertThat(CalendarUtil.copyOf(CalendarUtil.copyOf(calendar)).getProperty(Property.VERSION).get().getValue())
            .isEqualTo("1.0;2.0");
    }

    @Test
    void calendarCopyShouldKeepAMinMaxVersion() {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITH_MIN_MAX_VERSION);

        assertThat(calendar.copy().getProperty(Property.VERSION).get().getValue())
            .isEqualTo("1.0;2.0");
    }

    @Test
    void parseIcsShouldKeepVersionWithoutMinimum() {
        Calendar calendar = CalendarUtil.parseIcs("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:a
            DTSTART:20251020T100000Z
            END:VEVENT
            END:VCALENDAR
            """);

        assertThat(calendar.getProperty(Property.VERSION).get().getValue())
            .isEqualTo("2.0");
    }
}
