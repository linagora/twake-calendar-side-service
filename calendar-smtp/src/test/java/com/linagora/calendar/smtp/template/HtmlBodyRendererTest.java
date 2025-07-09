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
                    <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
                        padding: 0;
                    }
                    
                    body {
                        margin: 0;
                        padding: 0;
                        -webkit-text-size-adjust: 100%;
                        -ms-text-size-adjust: 100%;
                    }
                    
                    table, td {
                        border-collapse: collapse;
                        mso-table-lspace: 0pt;
                        mso-table-rspace: 0pt;
                    }
                    
                    img {
                        border: 0;
                        height: auto;
                        line-height: 100%;
                        outline: none;
                        text-decoration: none;
                        -ms-interpolation-mode: bicubic;
                    }
                    
                    p {
                        display: block;
                        margin: 13px 0;
                    }</style><!--if msoxml
                      o:officedocumentsettings
                        o:allowpng
                          o:pixelsperinch 96--><!--if lte mso 11style(type='text/css').
                      .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width: 480px) {
                        .mj-column-per-100 {
                            width: 100% !important;
                            max-width: 100%;
                        }
                    }</style><style type="text/css"></style></head><div style=""><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                      tr
                        td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td(width='600px')
                          table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                            tr
                              td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td.reply-title-container-outlook(style='vertical-align:top;width:600px;')
                    --><div class="mj-column-per-100 mj-outlook-group-fix reply-title-container" style="background: #deffe1; border-radius: 4px; border: 1px solid #deffe1; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Bob</span><span>&nbsp;has accepted this invitation</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Daily Standup</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="display: inline-block;">Friday, 27 June 2025 14:00 - 14:30</span><span style="color: #787878; font-weight: 400; display: inline-block;">&nbsp;Asia/Ho_Chi_Minh</span>&nbsp;(<a class="link" href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="text-decoration: none; color: #4d91c9;">See in Calendar</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td>Meeting Room 1 (<a href="https://www.openstreetmap.org/search?query=Meeting Room 1">See in Map</a>)</td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice</span><span style="color: #787878;">&nbsp;&lt;alice@example.com&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span><li><span style="font-weight: 500">Bob</span><span style="color: #787878;">&nbsp;&lt;bob@example.com&gt;</span></li></li></ul></td></tr><tr><td valign="top"><strong>Resources</strong></td><td><span>Andre anton, Celine</span></td></tr><tr><td valign="top"><strong>Notes</strong></td><td>Discuss project updates and blockers</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
        }

        @Test
        void shouldSucceedWithI18N() throws Exception {
            String renderResult = testee.render(sampleModel(Locale.FRANCE));

            assertThat(renderResult.trim())
                .isEqualToIgnoringNewLines("""
                    <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
                        padding: 0;
                    }
                    
                    body {
                        margin: 0;
                        padding: 0;
                        -webkit-text-size-adjust: 100%;
                        -ms-text-size-adjust: 100%;
                    }
                    
                    table, td {
                        border-collapse: collapse;
                        mso-table-lspace: 0pt;
                        mso-table-rspace: 0pt;
                    }
                    
                    img {
                        border: 0;
                        height: auto;
                        line-height: 100%;
                        outline: none;
                        text-decoration: none;
                        -ms-interpolation-mode: bicubic;
                    }
                    
                    p {
                        display: block;
                        margin: 13px 0;
                    }</style><!--if msoxml
                      o:officedocumentsettings
                        o:allowpng
                          o:pixelsperinch 96--><!--if lte mso 11style(type='text/css').
                      .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width: 480px) {
                        .mj-column-per-100 {
                            width: 100% !important;
                            max-width: 100%;
                        }
                    }</style><style type="text/css"></style></head><div style=""><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                      tr
                        td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td(width='600px')
                          table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                            tr
                              td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td.reply-title-container-outlook(style='vertical-align:top;width:600px;')
                    --><div class="mj-column-per-100 mj-outlook-group-fix reply-title-container" style="background: #deffe1; border-radius: 4px; border: 1px solid #deffe1; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Bob</span><span>&nbsp;a accept&eacute; cet &eacute;v&eacute;nement</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Daily Standup</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Heure</strong></td><td><span style="display: inline-block;">vendredi, 27 juin 2025 14:00 - 14:30</span><span style="color: #787878; font-weight: 400; display: inline-block;">&nbsp;Asia/Ho_Chi_Minh</span>&nbsp;(<a class="link" href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="text-decoration: none; color: #4d91c9;">Voir dans le Calendrier</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Emplacement</strong></td><td>Meeting Room 1 (<a href="https://www.openstreetmap.org/search?query=Meeting Room 1">Voir sur la Carte</a>)</td></tr><tr><td valign="top"><strong>Participants</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice</span><span style="color: #787878;">&nbsp;&lt;alice@example.com&gt;</span><span style="font-weight: 500">&nbsp;(Organisateur)</span><li><span style="font-weight: 500">Bob</span><span style="color: #787878;">&nbsp;&lt;bob@example.com&gt;</span></li></li></ul></td></tr><tr><td valign="top"><strong>Ressources</strong></td><td><span>Andre anton, Celine</span></td></tr><tr><td valign="top"><strong>Remarques</strong></td><td>Discuss project updates and blockers</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
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
                    <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a { padding:0; }
                    body { margin:0;padding:0;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%; }
                    table, td { border-collapse:collapse;mso-table-lspace:0pt;mso-table-rspace:0pt; }
                    img { border:0;height:auto;line-height:100%; outline:none;text-decoration:none;-ms-interpolation-mode:bicubic; }
                    p { display:block;margin:13px 0; }</style><!--if msoxml
                      o:officedocumentsettings
                        o:allowpng
                          o:pixelsperinch 96--><!--if lte mso 11style(type='text/css').
                      .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
                        .mj-column-per-100 { width:100% !important; max-width: 100%; }
                    }</style><style type="text/css"></style></head><div style=""><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                      tr
                        td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:32px;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td(width='600px')
                          table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                            tr
                              td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td.propose-time-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix propose-time-title-container" style="background: #eaeefe; border-radius: 4px; border: 1px solid #eaeefe; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Editor 123</span><span>&nbsp;has proposed changes to the event</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">The details of the proposal</div></td></tr><tr><td align="left" style="font-size:0px;padding:16px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>New time</strong></td><td><span style="display: inline-block;">Friday, 27 June 2025 15:00 - 15:30</span><span style="display: inline-block; color: #787878; font-weight: 400;">&nbsp;Asia/Ho_Chi_Minh</span></td></tr></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Daily Standup</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="display: inline-block;">Friday, 27 June 2025 14:00 - 14:30</span><span style="display: inline-block; color: #787878; font-weight: 400;">&nbsp;Asia/Ho_Chi_Minh</span>&nbsp;(<a class="link" href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="text-decoration: none; color: #4d91c9;">See in Calendar</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td>Meeting Room 1 (<a href="https://www.openstreetmap.org/search?query=Meeting Room 1">See in Map</a>)</td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice</span><span style="color: #787878;">&nbsp;&lt;alice@example.com&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span><li><span style="font-weight: 500">Bob</span><span style="color: #787878;">&nbsp;&lt;bob@example.com&gt;</span></li></li></ul></td></tr><tr><td valign="top"><strong>Resources</strong></td><td><span>Andre anton, Celine</span></td></tr><tr><td valign="top"><strong>Notes</strong></td><td>Discuss project updates and blockers</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
        }

        @Test
        void shouldSucceedWithI18N() throws Exception {
            String renderResult = testee.render(sampleModel(Locale.FRANCE));

            assertThat(renderResult.trim())
                .isEqualTo("""
                    <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a { padding:0; }
                    body { margin:0;padding:0;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%; }
                    table, td { border-collapse:collapse;mso-table-lspace:0pt;mso-table-rspace:0pt; }
                    img { border:0;height:auto;line-height:100%; outline:none;text-decoration:none;-ms-interpolation-mode:bicubic; }
                    p { display:block;margin:13px 0; }</style><!--if msoxml
                      o:officedocumentsettings
                        o:allowpng
                          o:pixelsperinch 96--><!--if lte mso 11style(type='text/css').
                      .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
                        .mj-column-per-100 { width:100% !important; max-width: 100%; }
                    }</style><style type="text/css"></style></head><div style=""><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                      tr
                        td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:32px;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td(width='600px')
                          table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                            tr
                              td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
                      tr
                        td.propose-time-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix propose-time-title-container" style="background: #eaeefe; border-radius: 4px; border: 1px solid #eaeefe; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Editor 123</span><span>&nbsp;a propos&eacute; des changements &agrave; l'&eacute;v&egrave;nement</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Les d&eacute;tails de la proposition</div></td></tr><tr><td align="left" style="font-size:0px;padding:16px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Nouveau temps</strong></td><td><span style="display: inline-block;">vendredi, 27 juin 2025 15:00 - 15:30</span><span style="display: inline-block; color: #787878; font-weight: 400;">&nbsp;Asia/Ho_Chi_Minh</span></td></tr></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Daily Standup</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Heure</strong></td><td><span style="display: inline-block;">vendredi, 27 juin 2025 14:00 - 14:30</span><span style="display: inline-block; color: #787878; font-weight: 400;">&nbsp;Asia/Ho_Chi_Minh</span>&nbsp;(<a class="link" href="http://localhost:3000/calendar/#/calendar?start=06-27-2025" style="text-decoration: none; color: #4d91c9;">Voir dans le Calendrier</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Emplacement</strong></td><td>Meeting Room 1 (<a href="https://www.openstreetmap.org/search?query=Meeting Room 1">Voir sur la Carte</a>)</td></tr><tr><td valign="top"><strong>Participants</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice</span><span style="color: #787878;">&nbsp;&lt;alice@example.com&gt;</span><span style="font-weight: 500">&nbsp;(Organisateur)</span><li><span style="font-weight: 500">Bob</span><span style="color: #787878;">&nbsp;&lt;bob@example.com&gt;</span></li></li></ul></td></tr><tr><td valign="top"><strong>Ressources</strong></td><td><span>Andre anton, Celine</span></td></tr><tr><td valign="top"><strong>Remarques</strong></td><td>Discuss project updates and blockers</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
        }

        private Map<String, Object> sampleModel(Locale locale) throws MalformedURLException {

            ZonedDateTime newStart = ZonedDateTime.parse("2025-06-27T15:00:00+07:00[Asia/Ho_Chi_Minh]");
            ZonedDateTime newEnd = newStart.plusMinutes(30);

            CounterContentModelBuilder.NewEventModel newEventModel = CounterContentModelBuilder.NewEventModel
                .builder()
                .allDay(false)
                .start(newStart)
                .end(Optional.of(newEnd))
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
