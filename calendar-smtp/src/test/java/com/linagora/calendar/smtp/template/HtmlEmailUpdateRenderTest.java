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

public class HtmlEmailUpdateRenderTest {
    private HtmlBodyRenderer htmlBodyRenderer;
    private I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory;

    @BeforeEach
    void setUp() throws Exception {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "event-update");

        htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString());
        i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
    }

    @Test
    void renderEventUpdateShouldSucceed() {
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
                    .put("location", ImmutableMap.of(
                        "value", "New Conference Room",
                        "urlEncodedValue", "New%20Conference%20Room",
                        "isLocationAValidURL", false,
                        "isLocationAnAbsoluteURL", false
                    ))
                    .put("hasResources", true)
                    .put("resources", ImmutableMap.of(
                        "projector1", ImmutableMap.of("cn", "Projector"),
                        "roomA", ImmutableMap.of("cn", "Room A")
                    ))
                    .put("description", "New agenda for the meeting.")
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/789")
                .put("yesLink", "https://calendar.example.com/event/123/yes")
                .put("maybeLink", "https://calendar.example.com/event/123/maybe")
                .put("noLink", "https://calendar.example.com/event/123/no")
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
            "translator", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><html><head><title></title><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style type="text/css">body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            a {
            color: #1a73e8;
            text-decoration: none;
            }
            @media only screen and (min-width: 720px) {
            .col-left {
            width: 66% !important;
            }
            .col-right {
            width: 34% !important;
            }
            .conference-box {
            max-width: none !important;
            background: transparent !important;
            padding: 0 0 0 16px !important;
            text-align: right !important;
            }
            .join-desktop {
            display: inline-block !important;
            }
            .join-mobile {
            display: none !important;
            }
            }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="margin: 0 8px 8px 8px; border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="font-size: 0;"><div class="col-left" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div style="display: inline-block; background: #fef7e0; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Alice Organizer</b>&nbsp;has updated an event</div><div style="margin: 0 0 16px 0;"><div style="font-size: 18px; font-weight: 600; line-height: 1.3;">Updated Meeting Title<span style="display: inline-block; vertical-align: middle; margin-left: 6px; padding: 1px 6px; background: #fef7e0; color: #b06000; font-size: 12px; font-weight: 600; line-height: 1.4; border-radius: 4px;">Changed</span></div><div style="margin-top: 2px; font-size: 12px; color: #5f6368; text-decoration: line-through;">Old Meeting Title</div></div></div></div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When<span style="display: inline-block; vertical-align: middle; margin-left: 6px; padding: 1px 6px; background: #fef7e0; color: #b06000; font-size: 12px; font-weight: 600; line-height: 1.4; border-radius: 4px;">Changed</span></div><div style="font-size: 14px; line-height: 1.5;">2025-07-06 14:00 - 15:00<span style="color: #5f6368;">&nbsp;(UTC)</span>&nbsp;<a href="https://calendar.example.com/event/789" style="color: #1a73e8; text-decoration: none;">See in Calendar</a><div style="margin-top: 2px; font-size: 12px; color: #5f6368; text-decoration: line-through;">2025-07-06 09:00 - 10:00</div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where<span style="display: inline-block; vertical-align: middle; margin-left: 6px; padding: 1px 6px; background: #fef7e0; color: #b06000; font-size: 12px; font-weight: 600; line-height: 1.4; border-radius: 4px;">Changed</span></div><div style="font-size: 14px; line-height: 1.5;">New Conference Room&nbsp;<a href="https://www.openstreetmap.org/search?query=New%20Conference%20Room" style="color: #1a73e8; text-decoration: none;">See in Map</a><div style="margin-top: 2px; font-size: 12px; color: #5f6368; text-decoration: line-through;">Old Conference Room</div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><a href="mailto:alice@domain.tld" style="color: #3c4043; text-decoration: none;"><b>Alice Organizer</b>&nbsp;alice@domain.tld</a><span style="color: #80868b;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><a href="mailto:bob@domain.tld" style="color: #3c4043; text-decoration: none;"><b>Bob Attendee</b>&nbsp;bob@domain.tld</a></div><div style="margin: 4px 0;"><a href="mailto:carol@domain.tld" style="color: #3c4043; text-decoration: none;"><b>Carol Attendee</b>&nbsp;carol@domain.tld</a></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Resources</div><div style="font-size: 14px; line-height: 1.5;"><span>Projector,&nbsp;Room A</span></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Will you attend this event?</div><div style="font-size: 14px; line-height: 1.5;"><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="border-collapse: collapse;"><tr><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/yes" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Yes</a></td><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/no" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">No</a></td><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/maybe" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Maybe</a></td></tr></table></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Notes<span style="display: inline-block; vertical-align: middle; margin-left: 6px; padding: 1px 6px; background: #fef7e0; color: #b06000; font-size: 12px; font-weight: 600; line-height: 1.4; border-radius: 4px;">Changed</span></div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">New agenda for the meeting.</div><div style="margin-top: 2px; font-size: 12px; color: #5f6368; text-decoration: line-through;"><div style="white-space: pre-line;">Old agenda for the meeting.</div></div></div></div></div></div><div style="border-top: 1px solid #e0e0e0; padding: 16px; font-size: 12px; line-height: 1.6; color: #5f6368;"><p style="margin: 0;">Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.</p></div></div></body></html>
            """.trim());
    }

    @Test
    void renderEventUpdateWithChangedTimeAllDayShouldShowPreviousAndNewAllDay() {
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
                        "fullDateTime", "2025-07-07 00:00",
                        "time", "00:00",
                        "timezone", "UTC",
                        "fullDate", "July 7, 2025"
                    ))
                    .put("end", ImmutableMap.of(
                        "date", "2025-07-08",
                        "fullDateTime", "2025-07-08 00:00",
                        "time", "00:00",
                        "timezone", "UTC",
                        "fullDate", "July 8, 2025"
                    ))
                    .put("location", ImmutableMap.of(
                        "value", "Conference Room",
                        "urlEncodedValue", "Conference%20Room",
                        "isLocationAValidURL", false,
                        "isLocationAnAbsoluteURL", false
                    ))
                    .put("hasResources", false)
                    .put("description", "All day event updated.")
                    .build())
                .put("seeInCalendarLink", "https://calendar.example.com/event/999")
                .put("changes", ImmutableMap.of(
                    "dtstart", ImmutableMap.of(
                        "previous", ImmutableMap.of(
                            "date", "2025-07-06",
                            "fullDateTime", "2025-07-06 00:00",
                            "time", "00:00",
                            "timezone", "UTC",
                            "fullDate", "July 6, 2025"
                        )
                    ),
                    "dtend", ImmutableMap.of(
                        "previous", ImmutableMap.of(
                            "date", "2025-07-07",
                            "fullDateTime", "2025-07-07 00:00",
                            "time", "00:00",
                            "timezone", "UTC",
                            "fullDate", "July 7, 2025"
                        )
                    ),
                    "isOldEventAllDay", true
                ))
                .build(),
            "translator", i18nFactory.forLocale(Locale.ENGLISH)
        );

        String result = htmlBodyRenderer.render(model);

        assertThat(result).isEqualToIgnoringNewLines("""
            <!DOCTYPE html><html><head><title></title><meta http-equiv="Content-Type" content="text/html; charset=UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style type="text/css">body {
            margin: 0;
            padding: 0;
            -webkit-text-size-adjust: 100%;
            -ms-text-size-adjust: 100%;
            }
            a {
            color: #1a73e8;
            text-decoration: none;
            }
            @media only screen and (min-width: 720px) {
            .col-left {
            width: 66% !important;
            }
            .col-right {
            width: 34% !important;
            }
            .conference-box {
            max-width: none !important;
            background: transparent !important;
            padding: 0 0 0 16px !important;
            text-align: right !important;
            }
            .join-desktop {
            display: inline-block !important;
            }
            .join-mobile {
            display: none !important;
            }
            }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="margin: 0 8px 8px 8px; border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="font-size: 0;"><div class="col-left" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div style="display: inline-block; background: #fef7e0; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Alice Organizer</b>&nbsp;has updated an event</div><div style="margin: 0 0 16px 0;"><div style="font-size: 18px; font-weight: 600; line-height: 1.3;">Updated All Day Meeting</div></div></div></div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When<span style="display: inline-block; vertical-align: middle; margin-left: 6px; padding: 1px 6px; background: #fef7e0; color: #b06000; font-size: 12px; font-weight: 600; line-height: 1.4; border-radius: 4px;">Changed</span></div><div style="font-size: 14px; line-height: 1.5;">July 7, 2025 - July 8, 2025 (All day)&nbsp;<a href="https://calendar.example.com/event/999" style="color: #1a73e8; text-decoration: none;">See in Calendar</a><div style="margin-top: 2px; font-size: 12px; color: #5f6368; text-decoration: line-through;">July 6, 2025 - July 7, 2025 (All day)</div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where</div><div style="font-size: 14px; line-height: 1.5;">Conference Room&nbsp;<a href="https://www.openstreetmap.org/search?query=Conference%20Room" style="color: #1a73e8; text-decoration: none;">See in Map</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><a href="mailto:alice@domain.tld" style="color: #3c4043; text-decoration: none;"><b>Alice Organizer</b>&nbsp;alice@domain.tld</a><span style="color: #80868b;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><a href="mailto:bob@domain.tld" style="color: #3c4043; text-decoration: none;"><b>Bob Attendee</b>&nbsp;bob@domain.tld</a></div><div style="margin: 4px 0;"><a href="mailto:carol@domain.tld" style="color: #3c4043; text-decoration: none;"><b>Carol Attendee</b>&nbsp;carol@domain.tld</a></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Will you attend this event?</div><div style="font-size: 14px; line-height: 1.5;"><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="border-collapse: collapse;"><tr><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Yes</a></td><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">No</a></td><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Maybe</a></td></tr></table></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Notes</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">All day event updated.</div></div></div></div></div><div style="border-top: 1px solid #e0e0e0; padding: 16px; font-size: 12px; line-height: 1.6; color: #5f6368;"><p style="margin: 0;">Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.</p></div></div></body></html>
            """.trim());
    }
}
