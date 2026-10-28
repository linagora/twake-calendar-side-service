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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.i18n.I18NTranslator.PropertiesI18NTranslator;
import com.linagora.calendar.smtp.template.content.model.CounterContentModelBuilder;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.smtp.template.content.model.ReplyContentModelBuilder;

import net.fortuna.ical4j.model.parameter.PartStat;

class HtmlBodyRendererTest {
    static FileSystem fileSystem = FileSystemImpl.forTesting();

    @Test
    void renderShouldSucceed() throws Exception {
        File templateDirectory = fileSystem.getFile("classpath://templates");
        HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.getAbsolutePath());

        Map<String, Object> model = ImmutableMap.of(
            "content", Map.of(
                "baseUrl", "http://localhost:8080",
                "jobFailedList", List.of(ImmutableMap.of("email", "email1@domain.tld"),
                    ImmutableMap.of("email", "email2@domain.tld")),
                "jobSucceedCount", 9,
                "jobFailedCount", 2));

        String result = htmlBodyRenderer.render(model);

        assertThat(result.trim())
            .isEqualTo("""
                <!DOCTYPE html><html class="mail"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div class="wrapper mail-content"><div class="grid-container"><div class="header"><div class="logo"><a href="http://localhost:8080"><img src="cid:logo" alt="OpenPaas Logo"></a></div><div class="subject"><div class="title">Import Report</div></div></div><div class="import"><span>Your Import is done see below the report :<ul><li> Contact(s) imported successfully</li><li> Contact(s) not imported</li></ul></span></div></div></div></body></html>""".trim());
    }

    @Nested
    class EventReplyTest {
        private PropertiesI18NTranslator.Factory translatorFactory;
        private HtmlBodyRenderer testee;

        @BeforeEach
        void setUp() throws Exception {
            Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
                "app", "src", "main", "resources", "templates", "event-reply");

            translatorFactory = new PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
            testee = HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString());
        }

        private Map<String, Object> sampleModel(Locale locale) throws MalformedURLException {
            ZonedDateTime start = ZonedDateTime.parse("2025-06-27T14:00:00+07:00[Asia/Ho_Chi_Minh]");
            ZonedDateTime end = start.plusMinutes(30);

            I18NTranslator translator = translatorFactory.forLocale(locale);
            Map<String, Object> model = ReplyContentModelBuilder.builder()
                .eventSummary("Daily Standup")
                .eventAllDay(false)
                .eventStart(start)
                .eventEnd(Optional.of(end))
                .eventLocation(Optional.of("Meeting Room 1"))
                .eventAttendee(new PersonModel("Bob", "bob@example.com"), PartStat.ACCEPTED)
                .eventOrganizer(new PersonModel("Alice", "alice@example.com"))
                .eventResources(List.of(new PersonModel("Andre anton", "anton@example.com"), new PersonModel("Celine", "celine@example.com")))
                .eventDescription(Optional.of("Discuss project updates and blockers"))
                .locale(locale)
                .timeZoneDisplay(ZoneId.of("Asia/Ho_Chi_Minh"))
                .translator(translator)
                .eventInCalendarLink(new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL()))
                .senderDisplayName("Bob")
                .buildAsMap();

            return ImmutableMap.<String, Object>builder()
                .putAll(model)
                .put("translator", translator)
                .build();
        }

        @Test
        void shouldSucceedWithEnglishLocale() throws Exception {
            String renderResult = testee.render(sampleModel(Locale.ENGLISH));
            assertThat(renderResult.trim())
                .isEqualToIgnoringNewLines("""
                    <!DOCTYPE html><html><head><title></title><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style type="text/css">body {
                    margin: 0;
                    padding: 0;
                    -webkit-text-size-adjust: 100%;
                    -ms-text-size-adjust: 100%;
                    }
                    a {
                    color: #1a73e8;
                    text-decoration: none;
                    }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="margin: 0 8px 8px 8px; border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="display: inline-block; background: #B9F6CA; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Bob</b>&nbsp;has accepted this invitation</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Daily Standup</div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When</div><div style="font-size: 14px; line-height: 1.5;">Friday, 27 June 2025 14:00 - 14:30<span style="color: #5f6368;">&nbsp;(Asia/Ho_Chi_Minh)</span>&nbsp;<a href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="color: #1a73e8; text-decoration: none;">See in Calendar</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where</div><div style="font-size: 14px; line-height: 1.5;">Meeting Room 1&nbsp;<a href="https://www.openstreetmap.org/search?query=Meeting%20Room%201" style="color: #1a73e8; text-decoration: none;">See in Map</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice</b>&nbsp;<span style="color: #3c4043;">alice@example.com</span><span style="color: #80868b;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><b>Bob</b>&nbsp;<span style="color: #3c4043;">bob@example.com</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Resources</div><div style="font-size: 14px; line-height: 1.5;">Andre anton, Celine</div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Notes</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Discuss project updates and blockers</div></div></div></div></div></div></body></html>""".trim());
        }

        @Test
        void shouldSucceedWithI18N() throws Exception {
            String renderResult = testee.render(sampleModel(Locale.FRANCE));

            assertThat(renderResult.trim())
                .isEqualToIgnoringNewLines("""
                    <!DOCTYPE html><html><head><title></title><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style type="text/css">body {
                    margin: 0;
                    padding: 0;
                    -webkit-text-size-adjust: 100%;
                    -ms-text-size-adjust: 100%;
                    }
                    a {
                    color: #1a73e8;
                    text-decoration: none;
                    }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="margin: 0 8px 8px 8px; border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="display: inline-block; background: #B9F6CA; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Bob</b>&nbsp;a accept&eacute; cet &eacute;v&eacute;nement</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Daily Standup</div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Quand</div><div style="font-size: 14px; line-height: 1.5;">vendredi, 27 juin 2025 14:00 - 14:30<span style="color: #5f6368;">&nbsp;(Asia/Ho_Chi_Minh)</span>&nbsp;<a href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="color: #1a73e8; text-decoration: none;">Voir dans le Calendrier</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">O&ugrave;</div><div style="font-size: 14px; line-height: 1.5;">Meeting Room 1&nbsp;<a href="https://www.openstreetmap.org/search?query=Meeting%20Room%201" style="color: #1a73e8; text-decoration: none;">Voir sur la Carte</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice</b>&nbsp;<span style="color: #3c4043;">alice@example.com</span><span style="color: #80868b;">&nbsp;- organisateur</span></div><div style="margin: 4px 0;"><b>Bob</b>&nbsp;<span style="color: #3c4043;">bob@example.com</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Ressources</div><div style="font-size: 14px; line-height: 1.5;">Andre anton, Celine</div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Remarques</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Discuss project updates and blockers</div></div></div></div></div></div></body></html>""".trim());
        }

    }

    @Nested
    class EventCounterTest {
        private PropertiesI18NTranslator.Factory translatorFactory;
        private HtmlBodyRenderer testee;

        @BeforeEach
        void setUp() throws Exception {
            Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
                "app", "src", "main", "resources", "templates", "event-counter");

            translatorFactory = new PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
            testee = HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString());
        }

        @Test
        void shouldSucceedWithEnglishLocale() throws Exception {
            String renderResult = testee.render(sampleModel(Locale.ENGLISH));

            assertThat(renderResult.trim())
                .isEqualTo("""
                    <!DOCTYPE html><html><head><title></title><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style type="text/css">body {
                    margin: 0;
                    padding: 0;
                    -webkit-text-size-adjust: 100%;
                    -ms-text-size-adjust: 100%;
                    }
                    a {
                    color: #1a73e8;
                    text-decoration: none;
                    }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="margin: 0 8px 8px 8px; border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="display: inline-block; background: #DAECFE; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Editor 123</b>&nbsp;has proposed changes to the event</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Daily Standup</div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Proposed time</div><div style="font-size: 14px; line-height: 1.5;">Friday, 27 June 2025 15:00 - 15:30<span style="color: #5f6368;">&nbsp;(Asia/Ho_Chi_Minh)</span>&nbsp;<a href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="color: #1a73e8; text-decoration: none;">See in Calendar</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Comment</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Can we move it one hour later?</div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Current time</div><div style="font-size: 14px; line-height: 1.5;">Friday, 27 June 2025 14:00 - 14:30<span style="color: #5f6368;">&nbsp;(Asia/Ho_Chi_Minh)</span></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where</div><div style="font-size: 14px; line-height: 1.5;">Meeting Room 1&nbsp;<a href="https://www.openstreetmap.org/search?query=Meeting%20Room%201" style="color: #1a73e8; text-decoration: none;">See in Map</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice</b>&nbsp;<span style="color: #3c4043;">alice@example.com</span><span style="color: #80868b;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><b>Bob</b>&nbsp;<span style="color: #3c4043;">bob@example.com</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Resources</div><div style="font-size: 14px; line-height: 1.5;">Andre anton, Celine</div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Notes</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Discuss project updates and blockers</div></div></div></div></div></div></body></html>""".trim());
        }

        @Test
        void shouldSucceedWithI18N() throws Exception {
            String renderResult = testee.render(sampleModel(Locale.FRANCE));

            assertThat(renderResult.trim())
                .isEqualTo("""
                    <!DOCTYPE html><html><head><title></title><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style type="text/css">body {
                    margin: 0;
                    padding: 0;
                    -webkit-text-size-adjust: 100%;
                    -ms-text-size-adjust: 100%;
                    }
                    a {
                    color: #1a73e8;
                    text-decoration: none;
                    }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="margin: 0 8px 8px 8px; border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="display: inline-block; background: #DAECFE; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Editor 123</b>&nbsp;a propos&eacute; des changements &agrave; l'&eacute;v&egrave;nement</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Daily Standup</div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Horaire propos&eacute;</div><div style="font-size: 14px; line-height: 1.5;">vendredi, 27 juin 2025 15:00 - 15:30<span style="color: #5f6368;">&nbsp;(Asia/Ho_Chi_Minh)</span>&nbsp;<a href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="color: #1a73e8; text-decoration: none;">Voir dans le Calendrier</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Commentaire</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Can we move it one hour later?</div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Horaire actuel</div><div style="font-size: 14px; line-height: 1.5;">vendredi, 27 juin 2025 14:00 - 14:30<span style="color: #5f6368;">&nbsp;(Asia/Ho_Chi_Minh)</span></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">O&ugrave;</div><div style="font-size: 14px; line-height: 1.5;">Meeting Room 1&nbsp;<a href="https://www.openstreetmap.org/search?query=Meeting%20Room%201" style="color: #1a73e8; text-decoration: none;">Voir sur la Carte</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice</b>&nbsp;<span style="color: #3c4043;">alice@example.com</span><span style="color: #80868b;">&nbsp;- organisateur</span></div><div style="margin: 4px 0;"><b>Bob</b>&nbsp;<span style="color: #3c4043;">bob@example.com</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Ressources</div><div style="font-size: 14px; line-height: 1.5;">Andre anton, Celine</div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Remarques</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Discuss project updates and blockers</div></div></div></div></div></div></body></html>""".trim());
        }

        private Map<String, Object> sampleModel(Locale locale) throws MalformedURLException {

            ZonedDateTime newStart = ZonedDateTime.parse("2025-06-27T15:00:00+07:00[Asia/Ho_Chi_Minh]");
            ZonedDateTime newEnd = newStart.plusMinutes(30);

            CounterContentModelBuilder.NewEventModel newEventModel = CounterContentModelBuilder.NewEventModel
                .builder()
                .allDay(false)
                .start(newStart)
                .end(Optional.of(newEnd))
                .comment(Optional.of("Can we move it one hour later?"))
                .build();

            ZonedDateTime oldStart = ZonedDateTime.parse("2025-06-27T14:00:00+07:00[Asia/Ho_Chi_Minh]");
            ZonedDateTime oldEnd = oldStart.plusMinutes(30);

            I18NTranslator translator = translatorFactory.forLocale(locale);
            CounterContentModelBuilder.OldEventModel oldEventModel = CounterContentModelBuilder.OldEventModel
                .builder()
                .summary("Daily Standup")
                .allDay(false)
                .start(oldStart)
                .end(Optional.of(oldEnd))
                .location(Optional.of("Meeting Room 1"))
                .description(Optional.of("Discuss project updates and blockers"))
                .attendee(List.of(new PersonModel("Bob", "bob@example.com")))
                .organizer(new PersonModel("Alice", "alice@example.com"))
                .resources(List.of(new PersonModel("Andre anton", "anton@example.com"), new PersonModel("Celine", "celine@example.com")))
                .build();

            Map<String, Object> model = CounterContentModelBuilder.builder()
                .oldEvent(oldEventModel)
                .newEvent(newEventModel)
                .editorDisplayName("Editor 123")
                .locale(locale)
                .zoneToDisplay(ZoneId.of("Asia/Ho_Chi_Minh"))
                .translator(translator)
                .eventInCalendarLink(new EventInCalendarLinkFactory(URI.create("http://localhost:3000/").toURL()))
                .buildAsMap();

            return ImmutableMap.<String, Object>builder()
                .putAll(model)
                .put("translator", translator)
                .build();
        }
    }
}
