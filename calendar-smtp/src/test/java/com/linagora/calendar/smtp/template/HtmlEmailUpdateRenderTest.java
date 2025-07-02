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
import java.util.Locale;
import java.util.Map;

import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

public class HtmlEmailUpdateRenderTest {
    static FileSystem fileSystem = FileSystemImpl.forTesting();

    @Test
    void renderEventUpdateShouldSucceed() throws Exception {
        File templateDirectory = fileSystem.getFile("classpath://templates/email/event.update");
        HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.getAbsolutePath());
        I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(fileSystem.getFile("classpath://"));

        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("cn", "Carol Attendee", "email", "carol@domain.tld")
                    ))
                    .put("summary", "Updated Meeting Title")
                    .put("allDay", false)
                    .put("start", ImmutableMap.of(
                        "date", "2025-07-06",
                        "fullDateTime", "2025-07-06 14:00",
                        "time", "14:00",
                        "timezone", "UTC",
                        "fullDate", "2025-07-06"
                    ))
                    .put("end", ImmutableMap.of(
                        "date", "2025-07-06",
                        "fullDateTime", "2025-07-06 15:00",
                        "time", "15:00",
                        "fullDate", "2025-07-06"
                    ))
                    .put("location", "New Conference Room")
                    .put("isLocationAValidURL", false)
                    .put("isLocationAnAbsoluteURL", false)
                    .put("hasResources", true)
                    .put("resources", ImmutableMap.of(
                        "projector1", ImmutableMap.of("cn", "Projector"),
                        "roomA", ImmutableMap.of("cn", "Room A")
                    ))
                    .put("description", "New agenda for the meeting.")
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/789")
                .put("changes", ImmutableMap.of(
                    "summary", ImmutableMap.of(
                        "previous", "Old Meeting Title"
                    ),
                    "dtstart", ImmutableMap.of(
                        "previous", ImmutableMap.of(
                            "date", "2025-07-06",
                            "fullDateTime", "2025-07-06 09:00",
                            "time", "09:00",
                            "timezone", "UTC",
                            "fullDate", "2025-07-06"
                        )
                    ),
                    "dtend", ImmutableMap.of(
                        "previous", ImmutableMap.of(
                            "date", "2025-07-06",
                            "fullDateTime", "2025-07-06 10:00",
                            "time", "10:00",
                            "timezone", "UTC",
                            "fullDate", "2025-07-06"
                        )
                    ),
                    "location", ImmutableMap.of(
                        "previous", "Old Conference Room"
                    ),
                    "description", ImmutableMap.of(
                        "previous", "Old agenda for the meeting."
                    ),
                    "isOldEventAllDay", false
                ))
                .build(),
            "translate", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
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
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td.invitation-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix invitation-title-container" style="background: #fffcde; border-radius: 4px; border: 1px solid #fffcde; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Alice Organizer</span><span>&nbsp;has updated a meeting</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;"><span style="color: orange; font-weight: bold;">Changed:&nbsp;</span><span style="text-decoration: line-through;">Old Meeting Title</span><span>&nbsp;➡&nbsp;</span><span>Updated Meeting Title</span></div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="color: orange; font-weight: bold;">Changed:&nbsp;</span><span style="text-decoration: line-through; display: inline-block;">2025-07-06 09:00 - 10:00</span><span>&nbsp;➡&nbsp;</span><span style="display: inline-block;">2025-07-06 14:00 - 15:00</span><span style="color: #787878; font-weight: 400; display: inline-block;">&nbsp;UTC</span>&nbsp;(<a class="link" href="https://calendar.example.com/event/789" style="text-decoration: none; color: #4d91c9;">See in Calendar</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td><span style="color: orange; font-weight: bold;">Changed:&nbsp;</span><span style="text-decoration: line-through;">Old Conference Room</span><span>&nbsp;➡&nbsp;</span>New Conference Room (<a href="https://www.openstreetmap.org/search?query=New Conference Room">See in Map</a>)</td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice Organizer</span><span style="color: #787878;">&nbsp;&lt;alice@domain.tld&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">Bob Attendee</span><span style="color: #787878;">&nbsp;&lt;bob@domain.tld&gt;</span></li><li><span style="font-weight: 500">Carol Attendee</span><span style="color: #787878;">&nbsp;&lt;carol@domain.tld&gt;</span></li></ul></td></tr><tr><td valign="top"><strong>Resources</strong></td><td><span>Projector,&nbsp;Room A</span></td></tr><tr><td valign="top"><strong>Notes</strong></td><td><span style="color: orange; font-weight: bold;">Changed:&nbsp;</span><span style="text-decoration: line-through;">Old agenda for the meeting.</span><span>&nbsp;➡&nbsp;</span><span>New agenda for the meeting.</span></td></tr></table></td></tr><tr><td class="part-table" align="left" style="background: #f7f7f7; font-size: 0px; padding: 20px; padding-top: 16px; padding-bottom: 16px; word-break: break-word;"><table cellpadding="0" cellspacing="0" width="100%" border="0" style="color:#000000;font-family:Roboto;font-size:13px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td class="part-message-container" colspan="3" style="font-size: 16px; font-family: Roboto; color: #333; font-weight: 500; padding-bottom: 12px;">Will you attend this event?</td></tr><tr><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" onclick="return false;" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: green; font-size: 18px;">&nbsp;&check;&nbsp;</span>&nbsp;Yes</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" onclick="return false;" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: orange; font-weight: 500;">&nbsp;?&nbsp;</span>&nbsp;Maybe</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: red; font-size: 18px;">&nbsp;&#x2715;&nbsp;</span>&nbsp;No</a></td><td class="part-button-container--last" style="width: 100%;" width="100%">&nbsp;</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix" style="font-size:0px;text-align:left;direction:ltr;display:inline-block;vertical-align:top;width:100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td class="warning-text" align="left" style="background-color: #fffaea; border-radius: 4px; font-size: 0px; padding: 12px; word-break: break-word;" bgcolor="#fffaea"><div style="font-family:Roboto;font-size:12px;line-height:1;text-align:left;color:#808080;">&#x26A0; Forwarding this invitation could allow any recipient to send a response to the organizer and be added to the guest list, or invite others regardless of their own invitation status, or to modify your RSVP.</div></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>
            """);
    }

    @Test
    void renderEventUpdateWithChangedTimeAllDayShouldShowPreviousAndNewAllDay() throws Exception {
        File templateDirectory = fileSystem.getFile("classpath://templates/email/event.update");
        HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.getAbsolutePath());
        I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(fileSystem.getFile("classpath://"));

        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("cn", "Carol Attendee", "email", "carol@domain.tld")
                    ))
                    .put("summary", "Updated All Day Meeting")
                    .put("allDay", true)
                    .put("start", ImmutableMap.of(
                        "date", "2025-07-07",
                        "fullDate", "July 7, 2025"
                    ))
                    .put("end", ImmutableMap.of(
                        "date", "2025-07-08",
                        "fullDate", "July 8, 2025"
                    ))
                    .put("location", "Conference Room")
                    .put("isLocationAValidURL", false)
                    .put("isLocationAnAbsoluteURL", false)
                    .put("hasResources", false)
                    .put("description", "All day event updated.")
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/999")
                .put("changes", ImmutableMap.of(
                    "dtstart", ImmutableMap.of(
                        "previous", ImmutableMap.of(
                            "date", "2025-07-06",
                            "fullDate", "July 6, 2025"
                        )
                    ),
                    "dtend", ImmutableMap.of(
                        "previous", ImmutableMap.of(
                            "date", "2025-07-07",
                            "fullDate", "July 7, 2025"
                        )
                    ),
                    "isOldEventAllDay", true
                ))
                .build(),
            "translate", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
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
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;padding-bottom:0;padding-top:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td.invitation-title-container-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix invitation-title-container" style="background: #fffcde; border-radius: 4px; border: 1px solid #fffcde; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" width="100%"><tbody><tr><td style="vertical-align:top;padding-top:4px;padding-bottom:4px;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:0;padding-top:8px;padding-right:20px;padding-bottom:8px;padding-left:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:16px;line-height:1;text-align:left;color:#222222;"><span style="font-weight: 500;">Alice Organizer</span><span>&nbsp;has updated a meeting</span></div></td></tr></tbody></table></td></tr></tbody></table></div><!--if mso | IEtd.content-outlook(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix content" style="border: 1px solid #ccc; border-radius: 4px; margin-top: 24px; font-size: 0px; text-align: left; direction: ltr; display: inline-block; vertical-align: top; width: 100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td align="left" style="font-size:0px;padding:20px;word-break:break-word;"><div style="font-family:Roboto;font-size:20px;font-weight:500;line-height:1;text-align:left;color:#434343;"><span>Updated All Day Meeting</span></div></td></tr><tr><td align="left" style="font-size:0px;padding:20px;padding-top:0px;word-break:break-word;"><table cellpadding="4px" cellspacing="0" width="100%" border="0" style="color:#434343;font-family:Roboto;font-size:14px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td valign="top" style="min-width: 80px;"><strong>Time</strong></td><td><span style="color: orange; font-weight: bold;">Changed:&nbsp;</span><span style="text-decoration: line-through; display: inline-block;">July 6, 2025 - July 7, 2025 (All day)</span><span>&nbsp;➡&nbsp;</span><span style="display: inline-block;">July 7, 2025 - July 8, 2025 (All day)</span>&nbsp;(<a class="link" href="https://calendar.example.com/event/999" style="text-decoration: none; color: #4d91c9;">See in Calendar</a>)</td></tr><tr><td style="min-width: 96px;" valign="top"><strong>Location</strong></td><td>Conference Room (<a href="https://www.openstreetmap.org/search?query=Conference Room">See in Map</a>)</td></tr><tr><td valign="top"><strong>Attendees</strong></td><td><ul style="padding-inline-start: 16px; margin: 0;"><li><span style="font-weight: 500">Alice Organizer</span><span style="color: #787878;">&nbsp;&lt;alice@domain.tld&gt;</span><span style="font-weight: 500">&nbsp;(Organizer)</span></li><li><span style="font-weight: 500">Bob Attendee</span><span style="color: #787878;">&nbsp;&lt;bob@domain.tld&gt;</span></li><li><span style="font-weight: 500">Carol Attendee</span><span style="color: #787878;">&nbsp;&lt;carol@domain.tld&gt;</span></li></ul></td></tr><tr><td valign="top"><strong>Notes</strong></td><td><span>All day event updated.</span></td></tr></table></td></tr><tr><td class="part-table" align="left" style="background: #f7f7f7; font-size: 0px; padding: 20px; padding-top: 16px; padding-bottom: 16px; word-break: break-word;"><table cellpadding="0" cellspacing="0" width="100%" border="0" style="color:#000000;font-family:Roboto;font-size:13px;line-height:22px;table-layout:auto;width:100%;border:none;"><tr><td class="part-message-container" colspan="3" style="font-size: 16px; font-family: Roboto; color: #333; font-weight: 500; padding-bottom: 12px;">Will you attend this event?</td></tr><tr><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" onclick="return false;" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: green; font-size: 18px;">&nbsp;&check;&nbsp;</span>&nbsp;Yes</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" onclick="return false;" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: orange; font-weight: 500;">&nbsp;?&nbsp;</span>&nbsp;Maybe</a></td><td class="part-button-container" style="white-space: nowrap;"><a class="part-button" style="display: block; color: #333; text-align: center; margin-right: 8px; min-width: 64px; font-size: 16px; text-decoration: none; padding: 6px 8px; background: #fff; border-radius: 18px; border: 1px solid #ccc;"><span style="color: red; font-size: 18px;">&nbsp;&#x2715;&nbsp;</span>&nbsp;No</a></td><td class="part-button-container--last" style="width: 100%;" width="100%">&nbsp;</td></tr></table></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IEtable(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
              tr
                td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:20px 0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(width='600px')
                  table(align='center' border='0' cellpadding='0' cellspacing='0' style='width:600px;' width='600')
                    tr
                      td(style='line-height:0px;font-size:0px;mso-line-height-rule:exactly;')--><div style="margin:0px auto;max-width:600px;"><table align="center" border="0" cellpadding="0" cellspacing="0" role="presentation" style="width:100%;"><tbody><tr><td style="direction:ltr;font-size:0px;padding:0;text-align:center;"><!--if mso | IEtable(role='presentation' border='0' cellpadding='0' cellspacing='0')
              tr
                td(style='vertical-align:top;width:600px;')--><div class="mj-column-per-100 mj-outlook-group-fix" style="font-size:0px;text-align:left;direction:ltr;display:inline-block;vertical-align:top;width:100%;"><table border="0" cellpadding="0" cellspacing="0" role="presentation" style="vertical-align:top;" width="100%"><tbody><tr><td class="warning-text" align="left" style="background-color: #fffaea; border-radius: 4px; font-size: 0px; padding: 12px; word-break: break-word;" bgcolor="#fffaea"><div style="font-family:Roboto;font-size:12px;line-height:1;text-align:left;color:#808080;">&#x26A0; Forwarding this invitation could allow any recipient to send a response to the organizer and be added to the guest list, or invite others regardless of their own invitation status, or to modify your RSVP.</div></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></td></tr></tbody></table></div><!--if mso | IE--></div>
            """);
    }
}
