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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

public class HtmlEmailInviteRenderTest {
    private HtmlBodyRenderer htmlBodyRenderer;
    private I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory;

    @BeforeEach
    void setUp() throws Exception {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "event-invite");

        htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString());
        i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
    }

    @Test
    void renderEventInviteShouldSucceed() {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("cn", "Carol Attendee", "email", "carol@domain.tld")
                    ))
                    .put("summary", "Team Meeting")
                    .put("allDay", false)
                    .put("start", ImmutableMap.of(
                        "date", "2025-06-27",
                        "fullDateTime", "2025-06-27 10:00",
                        "time", "10:00",
                        "timezone", "Europe/Paris",
                        "fullDate", "2025-06-27"
                    ))
                    .put("end", ImmutableMap.of(
                        "date", "2025-06-27",
                        "fullDateTime", "2025-06-27 11:00",
                        "time", "11:00",
                        "fullDate", "2025-06-27"
                    ))
                    .put("location", ImmutableMap.of(
                        "value", "Conference Room",
                        "isLocationAValidURL", false,
                        "isLocationAnAbsoluteURL", false
                    ))
                    .put("hasResources", true)
                    .put("resources", ImmutableMap.of(
                        "projector1", ImmutableMap.of("cn", "Projector"),
                        "roomA", ImmutableMap.of("cn", "Room A")
                    ))
                    .put("description", "Discuss project updates.")
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/123")
                .put("yesLink", "https://calendar.example.com/event/123/yes")
                .put("maybeLink", "https://calendar.example.com/event/123/maybe")
                .put("noLink", "https://calendar.example.com/event/123/no")
                .build(),
            "translator", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
            padding: 0;
            }
            body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            table,
            td {
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
              .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
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
                td.invitation-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix invitation-title-container" style="background: #deffe1; border-radius: 4px; border: 1px solid #deffe1; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Alice Organizer</span><span>&nbsp;has invited you in to an event</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Team Meeting</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="display: inline-block;">2025-06-27 10:00 - 11:00</span><span style="color: #787878; font-weight: 400; display: inline-block;">&nbsp;Europe/Paris</span>&nbsp;(<a class="link" href="https://calendar.example.com/event/123" style="text-decoration: none; color: #4d91c9;">See in Calendar</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td>Conference Room (<a href="https://www.openstreetmap.org/search?query=Conference Room">See in Map</a>)</td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice Organizer</span><span style="color: #787878;">&nbsp;&lt;alice@domain.tld&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">Bob Attendee</span><span style="color: #787878;">&nbsp;&lt;bob@domain.tld&gt;</span></li><li><span style="font-weight: 500">Carol Attendee</span><span style="color: #787878;">&nbsp;&lt;carol@domain.tld&gt;</span></li></ul></td></tr><tr><td valign="top"><strong>Resources</strong></td><td><span>Projector,&nbsp;Room A</span></td></tr><tr><td valign="top"><strong>Notes</strong></td><td>Discuss project updates.</td></tr></table></td></tr><tr><td class="part-table" align="left" style="background: #f7f7f7; font-size: 0px; padding: 20px; padding-top: 16px; padding-bottom: 16px; word-break: break-word;"><table cellpadding="0" cellspacing="0" width="100%" border="0" style="color:#000000;font-family:Roboto;font-size:13px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td class="part-message-container" colspan="3" style="font-size: 16px; font-family: Roboto; color: #333; font-weight: 500; padding-bottom: 12px;">Will you attend this event?</td></tr><tr><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" href="https://calendar.example.com/event/123/yes" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: green; font-size: 18px;">&nbsp;&check;&nbsp;</span>&nbsp;Yes</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" href="https://calendar.example.com/event/123/maybe" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: orange; font-weight: 500;">&nbsp;?&nbsp;</span>&nbsp;Maybe</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" href="https://calendar.example.com/event/123/no" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: red; font-size: 18px;">&nbsp;&#x2715;&nbsp;</span>&nbsp;No</a></td><td class="part-button-container--last" style="width: 100%;" width="100%">&nbsp;</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix" style="font-size:0px;text-align:left;direction:ltr;display:inline-block;vertical-align:top;width:100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td class="warning-text" align="left" style="background-color: #fffaea; border-radius: 4px; font-size: 0px; padding: 12px; word-break: break-word;" bgcolor="#fffaea"><div style="font-family:Roboto;font-size:12px;line-height:1;text-align:left;color:#808080;">&#x26A0; Forwarding this invitation could allow any recipient to send a response to the organizer and be added to the guest list, or invite others regardless of their own invitation status, or to modify your RSVP.</div></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
    }

    @Test
    void renderAllDayEventShouldIncludeAllDayLabel() {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("cn", "Carol Attendee", "email", "carol@domain.tld")
                    ))
                    .put("summary", "All Day Workshop")
                    .put("allDay", true)
                    .put("start", ImmutableMap.of(
                        "date", "2025-07-01",
                        "fullDate", "July 1, 2025"
                    ))
                    .put("end", ImmutableMap.of(
                        "date", "2025-07-02",
                        "fullDate", "July 2, 2025"
                    ))
                    .put("hasResources", false)
                    .build())
                .build(),
            "translator", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
            padding: 0;
            }
            body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            table,
            td {
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
              .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
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
                td.invitation-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix invitation-title-container" style="background: #deffe1; border-radius: 4px; border: 1px solid #deffe1; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Alice</span><span>&nbsp;has invited you in to an event</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">All Day Workshop</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="display: inline-block;">July 1, 2025 - July 2, 2025 (All day)</span></td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice</span><span style="color: #787878;">&nbsp;&lt;alice@domain.tld&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">Bob Attendee</span><span style="color: #787878;">&nbsp;&lt;bob@domain.tld&gt;</span></li><li><span style="font-weight: 500">Carol Attendee</span><span style="color: #787878;">&nbsp;&lt;carol@domain.tld&gt;</span></li></ul></td></tr></table></td></tr><tr><td class="part-table" align="left" style="background: #f7f7f7; font-size: 0px; padding: 20px; padding-top: 16px; padding-bottom: 16px; word-break: break-word;"><table cellpadding="0" cellspacing="0" width="100%" border="0" style="color:#000000;font-family:Roboto;font-size:13px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td class="part-message-container" colspan="3" style="font-size: 16px; font-family: Roboto; color: #333; font-weight: 500; padding-bottom: 12px;">Will you attend this event?</td></tr><tr><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: green; font-size: 18px;">&nbsp;&check;&nbsp;</span>&nbsp;Yes</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: orange; font-weight: 500;">&nbsp;?&nbsp;</span>&nbsp;Maybe</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: red; font-size: 18px;">&nbsp;&#x2715;&nbsp;</span>&nbsp;No</a></td><td class="part-button-container--last" style="width: 100%;" width="100%">&nbsp;</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix" style="font-size:0px;text-align:left;direction:ltr;display:inline-block;vertical-align:top;width:100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td class="warning-text" align="left" style="background-color: #fffaea; border-radius: 4px; font-size: 0px; padding: 12px; word-break: break-word;" bgcolor="#fffaea"><div style="font-family:Roboto;font-size:12px;line-height:1;text-align:left;color:#808080;">&#x26A0; Forwarding this invitation could allow any recipient to send a response to the organizer and be added to the guest list, or invite others regardless of their own invitation status, or to modify your RSVP.</div></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
    }

    @Test
    void renderWithValidLocationURLShouldGenerateHyperlink() {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("cn", "Carol Attendee", "email", "carol@domain.tld")
                    ))
                    .put("summary", "Online Call")
                    .put("allDay", false)
                    .put("start", ImmutableMap.of("date", "2025-07-03", "fullDateTime", "2025-07-03 15:00", "time", "15:00", "timezone", "UTC"))
                    .put("end", ImmutableMap.of("date", "2025-07-03", "fullDateTime", "2025-07-03 16:00", "time", "16:00"))
                    .put("location", ImmutableMap.of(
                        "value", "https://meet.example.com/room",
                        "isLocationAValidURL", true,
                        "isLocationAnAbsoluteURL", true
                    ))
                    .put("hasResources", false)
                    .build())
                .build(),
            "translator", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><head><title></title><!-- [if !mso] <!--><meta http-equiv="X-UA-Compatible" content="IE=edge"/><!-- <![endif]--><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/><style type="text/css">#outlook a {
            padding: 0;
            }
            body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            table,
            td {
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
              .mj-outlook-group-fix { width:100% !important; }--><!-- [if !mso] <!--><link href="https://fonts.googleapis.com/css?family=Roboto:300,400,500,700" rel="stylesheet" type="text/css"/><style type="text/css">@import url(https://fonts.googleapis.com/css?family=Roboto:300,400,500,700);</style><!-- <![endif]--><style type="text/css">@media only screen and (min-width:480px) {
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
                td.invitation-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix invitation-title-container" style="background: #deffe1; border-radius: 4px; border: 1px solid #deffe1; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Alice</span><span>&nbsp;has invited you in to an event</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;">Online Call</div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="display: inline-block;">2025-07-03 15:00 - 16:00</span><span style="color: #787878; font-weight: 400; display: inline-block;">&nbsp;UTC</span></td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td><a class="link" href="https://meet.example.com/room" style="text-decoration: none; color: #4d91c9;">https://meet.example.com/room</a></td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice</span><span style="color: #787878;">&nbsp;&lt;alice@domain.tld&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">Bob Attendee</span><span style="color: #787878;">&nbsp;&lt;bob@domain.tld&gt;</span></li><li><span style="font-weight: 500">Carol Attendee</span><span style="color: #787878;">&nbsp;&lt;carol@domain.tld&gt;</span></li></ul></td></tr></table></td></tr><tr><td class="part-table" align="left" style="background: #f7f7f7; font-size: 0px; padding: 20px; padding-top: 16px; padding-bottom: 16px; word-break: break-word;"><table cellpadding="0" cellspacing="0" width="100%" border="0" style="color:#000000;font-family:Roboto;font-size:13px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td class="part-message-container" colspan="3" style="font-size: 16px; font-family: Roboto; color: #333; font-weight: 500; padding-bottom: 12px;">Will you attend this event?</td></tr><tr><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: green; font-size: 18px;">&nbsp;&check;&nbsp;</span>&nbsp;Yes</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: orange; font-weight: 500;">&nbsp;?&nbsp;</span>&nbsp;Maybe</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" target="_blank" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: red; font-size: 18px;">&nbsp;&#x2715;&nbsp;</span>&nbsp;No</a></td><td class="part-button-container--last" style="width: 100%;" width="100%">&nbsp;</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix" style="font-size:0px;text-align:left;direction:ltr;display:inline-block;vertical-align:top;width:100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td class="warning-text" align="left" style="background-color: #fffaea; border-radius: 4px; font-size: 0px; padding: 12px; word-break: break-word;" bgcolor="#fffaea"><div style="font-family:Roboto;font-size:12px;line-height:1;text-align:left;color:#808080;">&#x26A0; Forwarding this invitation could allow any recipient to send a response to the organizer and be added to the guest list, or invite others regardless of their own invitation status, or to modify your RSVP.</div></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>""".trim());
    }
}
