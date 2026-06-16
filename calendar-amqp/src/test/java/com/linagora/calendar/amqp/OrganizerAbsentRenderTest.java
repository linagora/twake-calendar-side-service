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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.model.CalendarEventBookingConfirmedNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventCancelNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventCounterNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventInviteNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventReplyNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventUpdateNotificationEmail;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.HtmlBodyRenderer;
import com.linagora.calendar.smtp.template.content.model.AlarmContentModelBuilder;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.PersonModel;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.Method;

/**
 * Strong functional guarantee that the notification e-mail templates are still rendered
 * (and do not blow up) when the VEVENT carries no ORGANIZER property.
 *
 * <p>Each test drives the production {@code toPugModel(...)} of a notification model with an
 * organizer-less event, then feeds the resulting model into the real Pug template, exactly like
 * {@code MessageGenerator} does in production. A missing organizer must degrade gracefully: the
 * organizer simply renders empty and the participation action links are omitted, but the template
 * must still produce a valid HTML body.
 */
class OrganizerAbsentRenderTest {

    private static final Locale LOCALE = Locale.ENGLISH;
    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static final String ICS_WITHOUT_ORGANIZER = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        METHOD:REQUEST
        BEGIN:VEVENT
        UID:uid-no-organizer@test
        DTSTART:20260401T100000Z
        DTEND:20260401T110000Z
        SUMMARY:Team Meeting
        LOCATION:Conference Room
        ATTENDEE;CN=Bob Attendee;PARTSTAT=ACCEPTED:mailto:bob@domain.tld
        END:VEVENT
        END:VCALENDAR
        """;

    private static CalendarEventNotificationEmail notificationEmail() throws Exception {
        Calendar calendar = CalendarUtil.parseIcs(ICS_WITHOUT_ORGANIZER);
        return new CalendarEventNotificationEmail(
            new MailAddress("sender@domain.tld"),
            new MailAddress("bob@domain.tld"),
            new Method(Method.VALUE_REQUEST),
            calendar,
            true,
            "/calendars/uri",
            "/calendars/base/event.ics");
    }

    private static Path templateDirectory(String templateName) {
        return Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", templateName);
    }

    private static I18NTranslator translator(String templateName) {
        return new I18NTranslator.PropertiesI18NTranslator.Factory(
            templateDirectory(templateName).resolve("translations").toFile())
            .forLocale(LOCALE);
    }

    private static EventInCalendarLinkFactory linkFactory() throws Exception {
        return new EventInCalendarLinkFactory(URI.create("http://localhost").toURL());
    }

    private static String render(String templateName, Map<String, Object> pugModel) throws Exception {
        HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory(templateName).toAbsolutePath().toString());

        Map<String, Object> renderModel = ImmutableMap.<String, Object>builder()
            .putAll(pugModel)
            .put("translator", translator(templateName))
            .build();

        return htmlBodyRenderer.render(renderModel);
    }

    @Test
    void inviteTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        CalendarEventInviteNotificationEmail email = new CalendarEventInviteNotificationEmail(notificationEmail());

        Map<String, Object> pugModel = email.toPugModel(LOCALE, ZONE, null, false, Optional.empty());
        String html = render("event-invite", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .contains("bob@domain.tld")
            .doesNotContain("null");
        assertThat(pugModel).containsEntry("subject.organizer", "");
    }

    @Test
    void updateTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        CalendarEventUpdateNotificationEmail email =
            new CalendarEventUpdateNotificationEmail(notificationEmail(), Optional.empty());

        Map<String, Object> pugModel = email.toPugModel(LOCALE, ZONE, null, false, Optional.empty());
        String html = render("event-update", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .contains("bob@domain.tld")
            .doesNotContain("null");
        assertThat(pugModel).containsEntry("subject.organizer", "");
    }

    @Test
    void cancelTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        CalendarEventCancelNotificationEmail email = new CalendarEventCancelNotificationEmail(notificationEmail());

        Map<String, Object> pugModel = email.toPugModel(LOCALE, ZONE, null, false);
        String html = render("event-cancel", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .contains("bob@domain.tld")
            .doesNotContain("null");
        assertThat(pugModel).containsEntry("subject.organizer", "");
    }

    @Test
    void replyTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        CalendarEventReplyNotificationEmail email = new CalendarEventReplyNotificationEmail(notificationEmail());

        Map<String, Object> pugModel = email.toReplyContentModelBuilder()
            .locale(LOCALE)
            .timeZoneDisplay(ZONE)
            .translator(translator("event-reply"))
            .eventInCalendarLink(linkFactory())
            .senderDisplayName("Bob Attendee")
            .buildAsMap();

        String html = render("event-reply", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .doesNotContain("null");
    }

    @Test
    void counterTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        Calendar oldEvent = CalendarUtil.parseIcs(ICS_WITHOUT_ORGANIZER);
        CalendarEventCounterNotificationEmail email =
            new CalendarEventCounterNotificationEmail(notificationEmail(), oldEvent);

        Map<String, Object> pugModel = email.toCounterContentModelBuilder()
            .locale(LOCALE)
            .zoneToDisplay(ZONE)
            .translator(translator("event-counter"))
            .eventInCalendarLink(linkFactory())
            .buildAsMap();

        String html = render("event-counter", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .doesNotContain("null");
    }

    @Test
    void bookingConfirmedTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:uid-no-organizer@test
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            SUMMARY:Team Meeting
            ATTENDEE;CN=Bob Attendee;PARTSTAT=ACCEPTED:mailto:bob@domain.tld
            X-PUBLICLY-CREATOR:proposer@domain.tld
            END:VEVENT
            END:VCALENDAR
            """;
        Calendar calendar = CalendarUtil.parseIcs(ics);
        CalendarEventNotificationEmail base = new CalendarEventNotificationEmail(
            new MailAddress("sender@domain.tld"),
            new MailAddress("bob@domain.tld"),
            new Method(Method.VALUE_REQUEST),
            calendar,
            true,
            "/calendars/uri",
            "/calendars/base/event.ics");
        CalendarEventBookingConfirmedNotificationEmail email = new CalendarEventBookingConfirmedNotificationEmail(base);

        Map<String, Object> pugModel = email.toPugModel(LOCALE, ZONE, translator("event-booking-confirmed"),
            new MailAddress("proposer@domain.tld"));

        String html = render("event-booking-confirmed", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .doesNotContain("null");
        assertThat(pugModel).containsEntry("subject.organizer", "");
    }

    @Test
    void alarmTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        // Mirrors AlarmTriggerService: an absent organizer becomes PersonModel.EMPTY.
        Map<String, Object> pugModel = AlarmContentModelBuilder.builder()
            .duration(Duration.ofMinutes(5))
            .summary("Team Meeting")
            .location(Optional.of("Conference Room"))
            .organizer(PersonModel.EMPTY)
            .attendees(List.of(new PersonModel("Bob Attendee", "bob@domain.tld")))
            .resources(List.of())
            .description(Optional.empty())
            .videoconference(Optional.empty())
            .locale(LOCALE)
            .buildAsMap();

        String html = render("event-alarm", pugModel);

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .contains("bob@domain.tld")
            .doesNotContain("null");
    }

    @Test
    void resourceRequestTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        String html = render("resource-request", ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", organizerLessEventModel())
                .put("seeInCalendarLink", "https://calendar.example.com/event/123")
                .put("acceptLink", "https://calendar.example.com/accept")
                .put("declineLink", "https://calendar.example.com/decline")
                .put("resourceName", "Projector")
                .build()));

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .contains("bob@domain.tld")
            .doesNotContain("null");
    }

    @Test
    void resourceReplyTemplateShouldRenderWhenOrganizerIsAbsent() throws Exception {
        String html = render("resource-reply", ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", organizerLessEventModel())
                .put("resourceName", "Projector")
                .put("approved", true)
                .build()));

        assertThat(html)
            .isNotBlank()
            .contains("Team Meeting")
            .contains("bob@domain.tld")
            .doesNotContain("null");
    }

    private static Map<String, Object> organizerLessEventModel() {
        return ImmutableMap.<String, Object>builder()
            .put("organizer", PersonModel.EMPTY.toPugModel())
            .put("attendees", ImmutableMap.of(
                "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld")))
            .put("summary", "Team Meeting")
            .put("allDay", false)
            .put("start", ImmutableMap.of("date", "2026-04-01", "fullDateTime", "2026-04-01 10:00", "time", "10:00", "timezone", "UTC", "fullDate", "2026-04-01"))
            .put("end", ImmutableMap.of("date", "2026-04-01", "fullDateTime", "2026-04-01 11:00", "time", "11:00", "fullDate", "2026-04-01"))
            .put("hasResources", false)
            .build();
    }
}
