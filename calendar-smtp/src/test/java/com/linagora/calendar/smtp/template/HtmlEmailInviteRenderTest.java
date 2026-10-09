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
                        "urlEncodedValue", "Conference%20Room",
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
            }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="font-size: 0;"><div class="col-left" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div style="display: inline-block; background: #e8f0fe; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Alice Organizer</b>&nbsp;has invited you in to an event</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Team Meeting</div></div></div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When</div><div style="font-size: 14px; line-height: 1.5;">2025-06-27 10:00 - 11:00<span style="color: #5f6368;">&nbsp;(Europe/Paris)</span>&nbsp;<a href="https://calendar.example.com/event/123" style="color: #1a73e8; text-decoration: none;">See in Calendar</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where</div><div style="font-size: 14px; line-height: 1.5;">Conference Room&nbsp;<a href="https://www.openstreetmap.org/search?query=Conference%20Room" style="color: #1a73e8; text-decoration: none;">See in Map</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice Organizer</b>&nbsp;<span style="color: #5f6368;">alice@domain.tld</span><span style="color: #5f6368;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><b>Bob Attendee</b>&nbsp;<span style="color: #5f6368;">bob@domain.tld</span></div><div style="margin: 4px 0;"><b>Carol Attendee</b>&nbsp;<span style="color: #5f6368;">carol@domain.tld</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Resources</div><div style="font-size: 14px; line-height: 1.5;"><span>Projector,&nbsp;Room A</span></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Will you attend this event?</div><div style="font-size: 14px; line-height: 1.5;"><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="border-collapse: collapse;"><tr><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/yes" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Yes</a></td><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/no" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">No</a></td><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/maybe" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Maybe</a></td></tr></table></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Notes</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Discuss project updates.</div></div></div></div></div><div style="border-top: 1px solid #e0e0e0; padding: 16px; font-size: 12px; line-height: 1.6; color: #5f6368;"><p style="margin: 0;">Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.</p></div></div></body></html>""".trim());
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
            }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="font-size: 0;"><div class="col-left" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div style="display: inline-block; background: #e8f0fe; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Alice</b>&nbsp;has invited you in to an event</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">All Day Workshop</div></div></div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When</div><div style="font-size: 14px; line-height: 1.5;">July 1, 2025 - July 2, 2025 (All day)</div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice</b>&nbsp;<span style="color: #5f6368;">alice@domain.tld</span><span style="color: #5f6368;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><b>Bob Attendee</b>&nbsp;<span style="color: #5f6368;">bob@domain.tld</span></div><div style="margin: 4px 0;"><b>Carol Attendee</b>&nbsp;<span style="color: #5f6368;">carol@domain.tld</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Will you attend this event?</div><div style="font-size: 14px; line-height: 1.5;"><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="border-collapse: collapse;"><tr><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Yes</a></td><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">No</a></td><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Maybe</a></td></tr></table></div></div></div></div><div style="border-top: 1px solid #e0e0e0; padding: 16px; font-size: 12px; line-height: 1.6; color: #5f6368;"><p style="margin: 0;">Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.</p></div></div></body></html>""".trim());
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
            }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="font-size: 0;"><div class="col-left" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div style="display: inline-block; background: #e8f0fe; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Alice</b>&nbsp;has invited you in to an event</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Online Call</div></div></div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When</div><div style="font-size: 14px; line-height: 1.5;">2025-07-03 15:00 - 16:00<span style="color: #5f6368;">&nbsp;(UTC)</span></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where</div><div style="font-size: 14px; line-height: 1.5;"><a href="https://meet.example.com/room" style="color: #1a73e8; text-decoration: none;">https://meet.example.com/room</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice</b>&nbsp;<span style="color: #5f6368;">alice@domain.tld</span><span style="color: #5f6368;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><b>Bob Attendee</b>&nbsp;<span style="color: #5f6368;">bob@domain.tld</span></div><div style="margin: 4px 0;"><b>Carol Attendee</b>&nbsp;<span style="color: #5f6368;">carol@domain.tld</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Will you attend this event?</div><div style="font-size: 14px; line-height: 1.5;"><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="border-collapse: collapse;"><tr><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Yes</a></td><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">No</a></td><td style="padding: 4px 4px 0 0;"><a target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Maybe</a></td></tr></table></div></div></div></div><div style="border-top: 1px solid #e0e0e0; padding: 16px; font-size: 12px; line-height: 1.6; color: #5f6368;"><p style="margin: 0;">Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.</p></div></div></body></html>""".trim());
    }

    @Test
    void renderWithVideoConferenceShouldGenerateHyperlink() {
        Map<String, Object> model = ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("event", ImmutableMap.builder()
                    .put("videoConferenceLink", "https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d")
                    .put("organizer", ImmutableMap.of("cn", "Alice Organizer", "email", "alice@domain.tld"))
                    .put("attendees", ImmutableMap.of(
                        "bob@domain.tld", ImmutableMap.of("cn", "Bob Attendee", "email", "bob@domain.tld"),
                        "carol@domain.tld", ImmutableMap.of("cn", "Carol Attendee", "email", "carol@domain.tld")))
                    .put("summary", "Team Meeting")
                    .put("allDay", false)
                    .put("start", ImmutableMap.of(
                        "date", "2025-06-27",
                        "fullDateTime", "2025-06-27 10:00",
                        "time", "10:00",
                        "timezone", "Europe/Paris",
                        "fullDate", "2025-06-27"))
                    .put("end", ImmutableMap.of(
                        "date", "2025-06-27",
                        "fullDateTime", "2025-06-27 11:00",
                        "time", "11:00",
                        "fullDate", "2025-06-27"))
                    .put("location", ImmutableMap.of(
                        "value", "Conference Room",
                        "urlEncodedValue", "Conference%20Room",
                        "isLocationAValidURL", false,
                        "isLocationAnAbsoluteURL", false))
                    .put("hasResources", true)
                    .put("resources", ImmutableMap.of(
                        "projector1", ImmutableMap.of("cn", "Projector"),
                        "roomA", ImmutableMap.of("cn", "Room A")))
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
            }</style></head><body style="margin: 0; padding: 0; background: #ffffff;"><div style="border: 1px solid #e0e0e0; border-radius: 8px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1a1a1a;"><div style="padding: 16px 16px 0 16px;"><div style="font-size: 0;"><div class="col-left" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div style="display: inline-block; background: #e8f0fe; font-size: 13px; line-height: 1.4; padding: 4px 8px; border-radius: 4px; margin-bottom: 8px;"><b>Alice Organizer</b>&nbsp;has invited you in to an event</div><div style="font-size: 18px; font-weight: 600; line-height: 1.3; margin: 0 0 16px 0;">Team Meeting</div></div><div class="col-right" style="display: inline-block; vertical-align: top; width: 100%; font-size: 14px;"><div class="conference-box" style="box-sizing: border-box; max-width: 400px; margin-bottom: 16px; padding: 14px; background: #f8f9fa; border-radius: 8px; text-align: left;"><a class="join-desktop" href="https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d" target="_blank" style="display: none; mso-hide: all; margin-bottom: 18px; padding: 10px 18px; background: #1a73e8; color: #ffffff; font-size: 14px; font-weight: 500; border-radius: 6px; text-decoration: none; white-space: nowrap;">&#128249;&nbsp; Join with Twake Visio</a><div style="font-size: 14px; font-weight: 700; margin: 0 0 6px 0;">Video conference</div><a href="https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d" target="_blank" style="font-size: 14px; color: #1a73e8; text-decoration: none; word-break: break-all;">https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d</a><a class="join-mobile" href="https://jitsi.linagora.com/11bb0e17-9b2d-433e-992f-1d797b8c9a5d" target="_blank" style="display: block; margin-top: 12px; padding: 10px 16px; background: #1a73e8; color: #ffffff; font-size: 14px; font-weight: 600; text-align: center; border-radius: 20px; text-decoration: none;">&#128249;&nbsp; Join with Twake Visio</a></div></div></div><div style="max-width: 620px;"><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">When</div><div style="font-size: 14px; line-height: 1.5;">2025-06-27 10:00 - 11:00<span style="color: #5f6368;">&nbsp;(Europe/Paris)</span>&nbsp;<a href="https://calendar.example.com/event/123" style="color: #1a73e8; text-decoration: none;">See in Calendar</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Where</div><div style="font-size: 14px; line-height: 1.5;">Conference Room&nbsp;<a href="https://www.openstreetmap.org/search?query=Conference%20Room" style="color: #1a73e8; text-decoration: none;">See in Map</a></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Participants</div><div style="font-size: 14px; line-height: 1.5;"><div style="margin: 4px 0;"><b>Alice Organizer</b>&nbsp;<span style="color: #5f6368;">alice@domain.tld</span><span style="color: #5f6368;">&nbsp;- organizer</span></div><div style="margin: 4px 0;"><b>Bob Attendee</b>&nbsp;<span style="color: #5f6368;">bob@domain.tld</span></div><div style="margin: 4px 0;"><b>Carol Attendee</b>&nbsp;<span style="color: #5f6368;">carol@domain.tld</span></div></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Resources</div><div style="font-size: 14px; line-height: 1.5;"><span>Projector,&nbsp;Room A</span></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Will you attend this event?</div><div style="font-size: 14px; line-height: 1.5;"><table role="presentation" border="0" cellpadding="0" cellspacing="0" style="border-collapse: collapse;"><tr><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/yes" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Yes</a></td><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/no" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">No</a></td><td style="padding: 4px 4px 0 0;"><a href="https://calendar.example.com/event/123/maybe" target="_blank" style="display: inline-block; padding: 6px 18px; border: 1px solid #c9c9c9; border-radius: 999px; background: #ffffff; color: #1a1a1a; font-size: 14px; text-align: center; text-decoration: none; white-space: nowrap;">Maybe</a></td></tr></table></div></div><div style="margin-bottom: 16px;"><div style="font-size: 14px; font-weight: 700; margin: 0 0 4px 0;">Notes</div><div style="font-size: 14px; line-height: 1.5;"><div style="white-space: pre-line;">Discuss project updates.</div></div></div></div></div><div style="border-top: 1px solid #e0e0e0; padding: 16px; font-size: 12px; line-height: 1.6; color: #5f6368;"><p style="margin: 0;">Forwarding this invitation could allow any recipient to send a response to the organizer, be added to the guest list, invite others regardless of their own invitation status, or modify your RSVP.</p></div></div></body></html>""".trim());
    }
}
