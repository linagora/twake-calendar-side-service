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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.Changes;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.StringChange;
import com.linagora.calendar.amqp.model.CalendarEventNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventUpdateNotificationEmail;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.HtmlBodyRenderer;

/**
 * An update notification may report an added summary, location or description with an absent
 * ({@code null}) previous value: the update mail must still be rendered, the property tagged as added.
 */
class UpdateNotificationAbsentPreviousValueTest {

    private static final Locale LOCALE = Locale.ENGLISH;
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final String TEMPLATE_NAME = "event-update";
    private static final StringChange ADDED_VALUE = new StringChange(null, "Added value");

    private static final String ICS = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        METHOD:REQUEST
        BEGIN:VEVENT
        UID:uid-absent-previous@test
        DTSTART:20260401T100000Z
        DTEND:20260401T110000Z
        SUMMARY:Added value
        LOCATION:Added value
        DESCRIPTION:Added value
        ORGANIZER;CN=Alice Organizer:mailto:alice@domain.tld
        ATTENDEE;CN=Bob Attendee;PARTSTAT=ACCEPTED:mailto:bob@domain.tld
        END:VEVENT
        END:VCALENDAR
        """;

    @Test
    void updateMailShouldRenderWhenSummaryHasNoPreviousValue() throws Exception {
        Map<String, Object> pugModel = toPugModel(new Changes(Optional.empty(), Optional.empty(),
            Optional.of(ADDED_VALUE), Optional.empty(), Optional.empty()));

        assertThat(render(pugModel)).isNotBlank().doesNotContain("null");
        assertThat(pugModel).containsEntry("subject.summary", "Added value");
    }

    @Test
    void updateMailShouldRenderWhenLocationHasNoPreviousValue() throws Exception {
        Map<String, Object> pugModel = toPugModel(new Changes(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(ADDED_VALUE), Optional.empty()));

        assertThat(render(pugModel)).isNotBlank().doesNotContain("null");
    }

    @Test
    void updateMailShouldRenderWhenDescriptionHasNoPreviousValue() throws Exception {
        Map<String, Object> pugModel = toPugModel(new Changes(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of(ADDED_VALUE)));

        assertThat(render(pugModel)).isNotBlank().doesNotContain("null");
    }

    private static Map<String, Object> toPugModel(Changes changes) throws Exception {
        CalendarEventNotificationEmail base = new CalendarEventNotificationEmail(
            new MailAddress("alice@domain.tld"),
            new MailAddress("bob@domain.tld"),
            CalendarUtil.parseIcs(ICS),
            "/calendars/base/event.ics");
        return new CalendarEventUpdateNotificationEmail(base, Optional.of(changes))
            .toPugModel(LOCALE, ZONE, null, false, Optional.empty());
    }

    private static String render(Map<String, Object> pugModel) throws Exception {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", TEMPLATE_NAME);
        I18NTranslator translator = new I18NTranslator.PropertiesI18NTranslator.Factory(
            templateDirectory.resolve("translations").toFile())
            .forLocale(LOCALE);

        return HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString())
            .render(ImmutableMap.<String, Object>builder()
                .putAll(pugModel)
                .put("translator", translator)
                .build());
    }
}
