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

package com.linagora.calendar.amqp.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.Changes;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.DateTimeChange;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.DateTimeValue;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.HtmlBodyRenderer;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;

class CalendarEventUpdateNotificationEmailTest {

    private static final Locale LOCALE = Locale.ENGLISH;
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final DateTimeValue CURRENT = new DateTimeValue(false, "2026-04-01 10:30:00.000000", 3, "UTC");
    private static final DateTimeValue READABLE_PREVIOUS = new DateTimeValue(false, "2026-04-01 11:00:00.000000", 3, "UTC");

    private static final String TEMPLATE = "event-update";
    private static final DateTimeValue PREVIOUS_START = new DateTimeValue(false, "2026-04-01 09:30:00.000000", 3, "UTC");
    private static final DateTimeValue CURRENT_START = new DateTimeValue(false, "2026-04-01 10:30:00.000000", 3, "UTC");
    private static final DateTimeValue CURRENT_END = new DateTimeValue(false, "2026-04-01 11:30:00.000000", 3, "UTC");

    private static final String ICS = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        METHOD:REQUEST
        BEGIN:VEVENT
        UID:uid-update@test
        DTSTART:20260401T103000Z
        DTEND:20260401T113000Z
        SUMMARY:Team Meeting
        ORGANIZER;CN=Alice Organizer:mailto:alice@domain.tld
        ATTENDEE;CN=Bob Attendee;PARTSTAT=ACCEPTED:mailto:bob@domain.tld
        END:VEVENT
        END:VCALENDAR
        """;

    private static CalendarEventNotificationEmail baseEmail() throws Exception {
        return new CalendarEventNotificationEmail(
            new MailAddress("alice@domain.tld"),
            new MailAddress("bob@domain.tld"),
            CalendarUtil.parseIcs(ICS),
            "/calendars/base/event.ics");
    }

    private static CalendarEventNotificationEmailDTO.Changes changes(Optional<DateTimeChange> dtstart, Optional<DateTimeChange> dtend) {
        return new CalendarEventNotificationEmailDTO.Changes(dtstart, dtend, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static DateTimeChange previousStart(String date) {
        return new DateTimeChange(new DateTimeValue(false, date, 3, "UTC"), CURRENT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> changesModel(CalendarEventNotificationEmailDTO.Changes changes) throws Exception {
        Map<String, Object> pugModel = new CalendarEventUpdateNotificationEmail(baseEmail(), Optional.of(changes))
            .toPugModel(LOCALE, ZONE, null, false, Optional.empty());
        Map<String, Object> content = (Map<String, Object>) pugModel.get("content");
        return (Map<String, Object>) content.get("changes");
    }

    @SuppressWarnings("unchecked")
    private static Object previousTime(Map<String, Object> changesModel, String key, String field) {
        Map<String, Object> change = (Map<String, Object>) changesModel.get(key);
        return ((Map<String, Object>) change.get("previous")).get(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-04-01 10:00:00.000000",
        "2026-04-01 10:00:00",
        "2026-04-01 10:00:00.5",
        "2026-04-01 10:00:00.123456789",
        "2026-04-01T10:00:00",
        "2026-04-01T10:00:00.000"
    })
    void toPugModelShouldParsePreviousDateLeniently(String date) throws Exception {
        Map<String, Object> changesModel = changesModel(changes(Optional.of(previousStart(date)), Optional.empty()));

        assertThat(previousTime(changesModel, "dtstart", "date")).isEqualTo("2026-04-01");
        assertThat(previousTime(changesModel, "dtstart", "time")).isEqualTo("10:00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a date", "2026-04-01", "2026-13-01 10:00:00", "2026-04-01 10:00:00.0000000000"})
    void toPugModelShouldOmitAnUnreadablePreviousDate(String date) throws Exception {
        Map<String, Object> changesModel = changesModel(changes(Optional.of(previousStart(date)), Optional.empty()));

        assertThat(changesModel).doesNotContainKey("dtstart");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Not/AZone", "Europe/+14:00Paris", "CEST"})
    void toPugModelShouldOmitAnUnknownPreviousTimezone(String timezone) throws Exception {
        DateTimeChange dtstart = new DateTimeChange(new DateTimeValue(false, "2026-04-01 10:00:00", 3, timezone), CURRENT);

        Map<String, Object> changesModel = changesModel(changes(Optional.of(dtstart), Optional.empty()));

        assertThat(changesModel).doesNotContainKey("dtstart");
    }

    @Test
    void toPugModelShouldOmitAMissingPreviousTimezone() throws Exception {
        DateTimeChange dtstart = new DateTimeChange(new DateTimeValue(false, "2026-04-01 10:00:00", 3, null), CURRENT);

        Map<String, Object> changesModel = changesModel(changes(Optional.of(dtstart), Optional.empty()));

        assertThat(changesModel).doesNotContainKey("dtstart");
    }

    @Test
    void toPugModelShouldResolveAWindowsPreviousTimezone() throws Exception {
        DateTimeChange dtstart = new DateTimeChange(new DateTimeValue(false, "2026-04-01 10:00:00", 3, "W. Europe Standard Time"), CURRENT);

        Map<String, Object> changesModel = changesModel(changes(Optional.of(dtstart), Optional.empty()));

        assertThat(previousTime(changesModel, "dtstart", "time")).isEqualTo("08:00");
    }

    @Test
    void toPugModelShouldOmitAMissingPreviousValue() throws Exception {
        DateTimeChange dtstart = new DateTimeChange(null, CURRENT);

        Map<String, Object> changesModel = changesModel(changes(Optional.of(dtstart), Optional.empty()));

        assertThat(changesModel)
            .doesNotContainKey("dtstart")
            .containsEntry("isOldEventAllDay", false);
    }

    @Test
    void toPugModelShouldOmitAMissingPreviousDate() throws Exception {
        Map<String, Object> changesModel = changesModel(changes(Optional.of(previousStart(null)), Optional.empty()));

        assertThat(changesModel).doesNotContainKey("dtstart");
    }

    @Test
    void toPugModelShouldOmitBothPreviousTimesWhenOneIsUnreadable() throws Exception {
        DateTimeChange dtend = new DateTimeChange(READABLE_PREVIOUS, CURRENT);

        Map<String, Object> changesModel = changesModel(changes(Optional.of(previousStart("garbage")), Optional.of(dtend)));

        assertThat(changesModel)
            .doesNotContainKey("dtstart")
            .doesNotContainKey("dtend");
    }

    @Test
    void toPugModelShouldKeepBothPreviousTimesWhenReadable() throws Exception {
        DateTimeChange dtend = new DateTimeChange(READABLE_PREVIOUS, CURRENT);

        Map<String, Object> changesModel = changesModel(changes(Optional.of(previousStart("2026-04-01 10:00:00")), Optional.of(dtend)));

        assertThat(previousTime(changesModel, "dtstart", "time")).isEqualTo("10:00");
        assertThat(previousTime(changesModel, "dtend", "time")).isEqualTo("11:00");
    }
    @Test
    void toPugModelShouldOmitADateTimeChangeWithoutPrevious() throws Exception {
        Map<String, Object> changes = changesModel(dtendChanges(new DateTimeChange(null, CURRENT_END)));

        assertThat(changes).doesNotContainKey("dtend")
            .containsEntry("isOldEventAllDay", false);
    }

    @Test
    void toPugModelShouldOmitADateTimeChangeWhosePreviousHasNoDate() throws Exception {
        Map<String, Object> changes = changesModel(dtendChanges(
            new DateTimeChange(new DateTimeValue(false, null, 3, "UTC"), CURRENT_END)));

        assertThat(changes).doesNotContainKey("dtend");
    }

    @Test
    void toPugModelShouldOmitADateTimeChangeWhosePreviousHasNoTimezone() throws Exception {
        Map<String, Object> changes = changesModel(dtendChanges(
            new DateTimeChange(new DateTimeValue(false, "2026-04-01 10:30:00.000000", 3, null), CURRENT_END)));

        assertThat(changes).doesNotContainKey("dtend");
    }

    @Test
    void toPugModelShouldOmitADateTimeChangeWhosePreviousIsUnreadable() throws Exception {
        Map<String, Object> changes = changesModel(dtendChanges(
            new DateTimeChange(new DateTimeValue(false, "68727003T130000", 3, "Not/AZone"), CURRENT_END)));

        assertThat(changes).doesNotContainKey("dtend");
    }

    @Test
    void toPugModelShouldOmitBothPreviousTimesWhenOneIsMissing() throws Exception {
        Changes changes = new Changes(
            Optional.of(new DateTimeChange(PREVIOUS_START, CURRENT_START)),
            Optional.of(new DateTimeChange(null, CURRENT_END)),
            Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(changesModel(changes))
            .doesNotContainKey("dtstart")
            .doesNotContainKey("dtend");
    }

    @Test
    void updateTemplateShouldRenderWhenADateTimeChangeHasNoPrevious() throws Exception {
        Map<String, Object> pugModel = email(dtendChanges(new DateTimeChange(null, CURRENT_END)))
            .toPugModel(LOCALE, ZONE, linkFactory(), true, Optional.empty());

        assertThat(render(pugModel))
            .isNotBlank()
            .contains("Team Meeting")
            .doesNotContain("null");
    }

    @Test
    void toPugModelShouldUsePreviousEndAllDayFlagWhenPreviousStartIsMissing() throws Exception {
        Changes changes = new Changes(
            Optional.of(new DateTimeChange(null, CURRENT_START)),
            Optional.of(new DateTimeChange(new DateTimeValue(true, "2026-04-01 00:00:00.000000", 3, "UTC"), CURRENT_END)),
            Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(changesModel(changes)).containsEntry("isOldEventAllDay", true);
    }

    private static Changes dtendChanges(DateTimeChange dtendChange) {
        return new Changes(Optional.empty(), Optional.of(dtendChange),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static CalendarEventUpdateNotificationEmail email(Changes changes) throws Exception {
        CalendarEventNotificationEmail base = new CalendarEventNotificationEmail(
            new MailAddress("bob@example.com"),
            new MailAddress("alice@example.com"),
            CalendarUtil.parseIcs(ICS),
            "/calendars/base/cal/event.ics");
        return new CalendarEventUpdateNotificationEmail(base, Optional.of(changes));
    }

    private static EventInCalendarLinkFactory linkFactory() throws Exception {
        return new EventInCalendarLinkFactory(URI.create("http://localhost").toURL());
    }

    private static Path templateDirectory() {
        return Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", TEMPLATE);
    }

    private static String render(Map<String, Object> pugModel) throws Exception {
        I18NTranslator translator = new I18NTranslator.PropertiesI18NTranslator.Factory(
            templateDirectory().resolve("translations").toFile())
            .forLocale(LOCALE);
        Map<String, Object> renderModel = ImmutableMap.<String, Object>builder()
            .putAll(pugModel)
            .put("translator", translator)
            .build();
        return HtmlBodyRenderer.forPath(templateDirectory().toAbsolutePath().toString()).render(renderModel);
    }
}
