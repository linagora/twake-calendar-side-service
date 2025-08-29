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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.linagora.calendar.dav.CalendarUtil;

import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;

import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;

class CalendarEventNotificationEmailDeserializeTest {

    static final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

    @Test
    void shouldDeserializeFromJsonWhenInvite() throws Exception {
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:a260076a-4a24-42b7-9969-7a67917176a5
            SUMMARY:Test Event
            DTSTART;TZID=Europe/Paris:20250621T073000
            DTEND;TZID=Europe/Paris:20250621T080000
            END:VEVENT
            END:VCALENDAR
            """;
        String json = """
            {
                "senderEmail": "twake-calendar-dev@linagora.com",
                "recipientEmail": "btellier@linagora.com",
                "method": "REQUEST",
                "event": "%s",
                "notify": true,
                "calendarURI": "67e26ebbecd9f300255a9f80",
                "eventPath": "/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-71741739-8806-4a9e-98b0-cc386d248832.ics",
                "isNewEvent": true
            }
            """.formatted(eventIcs.replace("\n", "\\r\\n"));

        CalendarEventNotificationEmailDTO calendarEvent = mapper.readValue(json, CalendarEventNotificationEmailDTO.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(calendarEvent.senderEmail().asString()).isEqualTo("twake-calendar-dev@linagora.com");
            softly.assertThat(calendarEvent.recipientEmail().asString()).isEqualTo("btellier@linagora.com");
            softly.assertThat(calendarEvent.method()).isEqualTo(ImmutableMethod.REQUEST);
            softly.assertThat(calendarEvent.event()).isEqualTo(CalendarUtil.parseIcs(eventIcs));
            softly.assertThat(calendarEvent.notifyEvent()).isTrue();
            softly.assertThat(calendarEvent.calendarURI()).isEqualTo("67e26ebbecd9f300255a9f80");
            softly.assertThat(calendarEvent.eventPath()).isEqualTo("/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-71741739-8806-4a9e-98b0-cc386d248832.ics");
            softly.assertThat(calendarEvent.isNewEvent().get()).isTrue();
        });
    }

    @Test
    void shouldDeserializeFromJsonWhenUpdate() throws Exception {
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:a260076a-4a24-42b7-9969-7a67917176a5
            SUMMARY:Test Event
            DTSTART;TZID=Europe/Paris:20250621T073000
            DTEND;TZID=Europe/Paris:20250621T080000
            END:VEVENT
            END:VCALENDAR
            """;
        String json = """
            {
                "senderEmail": "twake-calendar-dev@linagora.com",
                "recipientEmail": "btellier@linagora.com",
                "method": "REQUEST",
                "event": "%s",
                "notify": true,
                "calendarURI": "67e26ebbecd9f300255a9f80",
                "eventPath": "/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-71741739-8806-4a9e-98b0-cc386d248832.ics",
                "changes": {
                    "summary": {
                        "previous": "Old summary",
                        "current": "Test Event"
                    },
                    "location": {
                        "previous": "office1",
                        "current": "office2"
                    },
                    "description": {
                        "previous": "detail1",
                        "current": "detail2"
                    },
                    "dtstart": {
                        "previous": {
                            "isAllDay": false,
                            "date": "2025-06-21 08:00:00.000000",
                            "timezone_type": 3,
                            "timezone": "Europe\\/Paris"
                        },
                        "current": {
                            "isAllDay": false,
                            "date": "2025-06-21 07:30:00.000000",
                            "timezone_type": 3,
                            "timezone": "Europe\\/Paris"
                        }
                    },
                    "dtend": {
                        "previous": {
                            "isAllDay": false,
                            "date": "2025-06-21 08:30:00.000000",
                            "timezone_type": 3,
                            "timezone": "Europe\\/Paris"
                        },
                        "current": {
                            "isAllDay": false,
                            "date": "2025-06-21 08:00:00.000000",
                            "timezone_type": 3,
                            "timezone": "Europe\\/Paris"
                        }
                    }
                }
            }
            """.formatted(eventIcs.replace("\n", "\\r\\n"));

        CalendarEventNotificationEmailDTO calendarEvent = mapper.readValue(json, CalendarEventNotificationEmailDTO.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(calendarEvent.senderEmail().asString()).isEqualTo("twake-calendar-dev@linagora.com");
            softly.assertThat(calendarEvent.recipientEmail().asString()).isEqualTo("btellier@linagora.com");
            softly.assertThat(calendarEvent.method()).isEqualTo(ImmutableMethod.REQUEST);
            softly.assertThat(calendarEvent.event()).isEqualTo(CalendarUtil.parseIcs(eventIcs));
            softly.assertThat(calendarEvent.notifyEvent()).isTrue();
            softly.assertThat(calendarEvent.calendarURI()).isEqualTo("67e26ebbecd9f300255a9f80");
            softly.assertThat(calendarEvent.eventPath()).isEqualTo("/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-71741739-8806-4a9e-98b0-cc386d248832.ics");
            softly.assertThat(calendarEvent.changes().get().summary()).isPresent();
            softly.assertThat(calendarEvent.changes().get().summary().get().previous()).isEqualTo("Old summary");
            softly.assertThat(calendarEvent.changes().get().summary().get().current()).isEqualTo("Test Event");
            softly.assertThat(calendarEvent.changes().get().location()).isPresent();
            softly.assertThat(calendarEvent.changes().get().location().get().previous()).isEqualTo("office1");
            softly.assertThat(calendarEvent.changes().get().location().get().current()).isEqualTo("office2");
            softly.assertThat(calendarEvent.changes().get().description()).isPresent();
            softly.assertThat(calendarEvent.changes().get().description().get().previous()).isEqualTo("detail1");
            softly.assertThat(calendarEvent.changes().get().description().get().current()).isEqualTo("detail2");
            softly.assertThat(calendarEvent.changes().get().dtstart()).isPresent();
            softly.assertThat(calendarEvent.changes().get().dtstart().get().previous().isAllDay()).isFalse();
            softly.assertThat(calendarEvent.changes().get().dtstart().get().previous().date()).isEqualTo("2025-06-21 08:00:00.000000");
            softly.assertThat(calendarEvent.changes().get().dtstart().get().previous().timezoneType()).isEqualTo(3);
            softly.assertThat(calendarEvent.changes().get().dtstart().get().previous().timezone()).isEqualTo("Europe/Paris");
            softly.assertThat(calendarEvent.changes().get().dtstart().get().current().isAllDay()).isFalse();
            softly.assertThat(calendarEvent.changes().get().dtstart().get().current().date()).isEqualTo("2025-06-21 07:30:00.000000");
            softly.assertThat(calendarEvent.changes().get().dtstart().get().current().timezoneType()).isEqualTo(3);
            softly.assertThat(calendarEvent.changes().get().dtstart().get().current().timezone()).isEqualTo("Europe/Paris");
            softly.assertThat(calendarEvent.changes().get().dtend()).isPresent();
            softly.assertThat(calendarEvent.changes().get().dtend().get().previous().isAllDay()).isFalse();
            softly.assertThat(calendarEvent.changes().get().dtend().get().previous().date()).isEqualTo("2025-06-21 08:30:00.000000");
            softly.assertThat(calendarEvent.changes().get().dtend().get().previous().timezoneType()).isEqualTo(3);
            softly.assertThat(calendarEvent.changes().get().dtend().get().previous().timezone()).isEqualTo("Europe/Paris");
            softly.assertThat(calendarEvent.changes().get().dtend().get().current().isAllDay()).isFalse();
            softly.assertThat(calendarEvent.changes().get().dtend().get().current().date()).isEqualTo("2025-06-21 08:00:00.000000");
            softly.assertThat(calendarEvent.changes().get().dtend().get().current().timezoneType()).isEqualTo(3);
            softly.assertThat(calendarEvent.changes().get().dtend().get().current().timezone()).isEqualTo("Europe/Paris");
        });
    }

    @Test
    void shouldDeserializeFromJsonWhenCancel() throws Exception {
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:a260076a-4a24-42b7-9969-7a67917176a5
            SUMMARY:Test Event
            DTSTART;TZID=Europe/Paris:20250621T073000
            DTEND;TZID=Europe/Paris:20250621T080000
            END:VEVENT
            END:VCALENDAR
            """;
        String json = """
            {
                "senderEmail": "twake-calendar-dev@linagora.com",
                "recipientEmail": "btellier@linagora.com",
                "method": "CANCEL",
                "event": "%s",
                "notify": true,
                "calendarURI": "67e26ebbecd9f300255a9f80",
                "eventPath": "/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-71741739-8806-4a9e-98b0-cc386d248832.ics"
            }
            """.formatted(eventIcs.replace("\n", "\\r\\n"));

        CalendarEventNotificationEmailDTO calendarEvent = mapper.readValue(json, CalendarEventNotificationEmailDTO.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(calendarEvent.senderEmail().asString()).isEqualTo("twake-calendar-dev@linagora.com");
            softly.assertThat(calendarEvent.recipientEmail().asString()).isEqualTo("btellier@linagora.com");
            softly.assertThat(calendarEvent.method()).isEqualTo(ImmutableMethod.CANCEL);
            softly.assertThat(calendarEvent.event()).isEqualTo(CalendarUtil.parseIcs(eventIcs));
            softly.assertThat(calendarEvent.notifyEvent()).isTrue();
            softly.assertThat(calendarEvent.calendarURI()).isEqualTo("67e26ebbecd9f300255a9f80");
            softly.assertThat(calendarEvent.eventPath()).isEqualTo("/calendars/5f50a663bdaffe002629099c/5f50a663bdaffe002629099c/sabredav-71741739-8806-4a9e-98b0-cc386d248832.ics");
        });
    }

    @Test
    void shouldDeserializeFromJsonWhenReply() throws Exception {
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REPLY
            BEGIN:VTIMEZONE
            TZID:Asia/Jakarta
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:a92de371-8529-45f8-948a-70ddf27bbc09
            DTSTAMP:20250619T083908Z
            SEQUENCE:0
            DTSTART:20250623T050000Z
            DTEND:20250623T053000Z
            SUMMARY:test111
            ORGANIZER;CN=John1 Doe1:mailto:user1@open-paas.org
            ATTENDEE;PARTSTAT=ACCEPTED;CN=John2 Doe2:mailto:user2@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """;
        String json = """
            {
              "senderEmail": "user2@open-paas.org",
              "recipientEmail": "user1@open-paas.org",
              "method": "REPLY",
              "event": "%s",
              "notify": true,
              "calendarURI": "6853ca6c1cbe800055fd838b",
              "eventPath": "/calendars/6853ca6c1cbe800055fd838a/6853ca6c1cbe800055fd838a/a92de371-8529-45f8-948a-70ddf27bbc09.ics"
            }
            """.formatted(eventIcs.replace("\n", "\\r\\n"));

        CalendarEventNotificationEmailDTO calendarEvent = mapper.readValue(json, CalendarEventNotificationEmailDTO.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(calendarEvent.senderEmail().asString()).isEqualTo("user2@open-paas.org");
            softly.assertThat(calendarEvent.recipientEmail().asString()).isEqualTo("user1@open-paas.org");
            softly.assertThat(calendarEvent.method()).isEqualTo(ImmutableMethod.REPLY);
            softly.assertThat(calendarEvent.event()).isEqualTo(CalendarUtil.parseIcs(eventIcs));
            softly.assertThat(calendarEvent.notifyEvent()).isTrue();
            softly.assertThat(calendarEvent.calendarURI()).isEqualTo("6853ca6c1cbe800055fd838b");
            softly.assertThat(calendarEvent.eventPath()).isEqualTo("/calendars/6853ca6c1cbe800055fd838a/6853ca6c1cbe800055fd838a/a92de371-8529-45f8-948a-70ddf27bbc09.ics");
        });
    }

    @Test
    void shouldDeserializeFromJsonWhenCounter() throws Exception {
        String eventIcs = """
            BEGIN:VCALENDAR
            METHOD:COUNTER
            BEGIN:VTIMEZONE
            TZID:Asia/Jakarta
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:a92de371-8529-45f8-948a-70ddf27bbc09
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250623T130000
            DTEND;TZID=Asia/Saigon:20250623T133000
            CLASS:PUBLIC
            SUMMARY:test111
            ORGANIZER;CN=John1 Doe1:mailto:user1@open-paas.org
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=John2 Doe2:mailto:user2@open-paas.org
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:user1@open-paas.org
            DTSTAMP:20250619T083735Z
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
            """;
        String oldEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            BEGIN:VTIMEZONE
            TZID:Asia/Jakarta
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:a92de371-8529-45f8-948a-70ddf27bbc09
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250623T120000
            DTEND;TZID=Asia/Saigon:20250623T123000
            CLASS:PUBLIC
            SUMMARY:test111
            ORGANIZER;CN=John1 Doe1:mailto:user1@open-paas.org
            ATTENDEE;PARTSTAT=DECLINED;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=John2 Doe2;SCHEDULE-STATUS=2.0:mailto:user2@open-paas.org
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:user1@open-paas.org
            DTSTAMP:20250619T083735Z
            END:VEVENT
            END:VCALENDAR
            """;
        String json = """
            {
              "senderEmail": "user2@open-paas.org",
              "recipientEmail": "user1@open-paas.org",
              "method": "COUNTER",
              "event": "%s",
              "notify": true,
              "calendarURI": "6853ca6c1cbe800055fd838b",
              "eventPath": "/calendars/6853ca6c1cbe800055fd838a/6853ca6c1cbe800055fd838a/a92de371-8529-45f8-948a-70ddf27bbc09.ics",
              "oldEvent": "%s"
            }
            """.formatted(eventIcs.replace("\n", "\\r\\n"), oldEventIcs.replace("\n", "\\r\\n"));

        CalendarEventNotificationEmailDTO calendarEvent = mapper.readValue(json, CalendarEventNotificationEmailDTO.class);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(calendarEvent.senderEmail().asString()).isEqualTo("user2@open-paas.org");
            softly.assertThat(calendarEvent.recipientEmail().asString()).isEqualTo("user1@open-paas.org");
            softly.assertThat(calendarEvent.method()).isEqualTo(ImmutableMethod.COUNTER);
            softly.assertThat(calendarEvent.event()).isEqualTo(CalendarUtil.parseIcs(eventIcs));
            softly.assertThat(calendarEvent.oldEvent().get()).isEqualTo(CalendarUtil.parseIcs(oldEventIcs));
            softly.assertThat(calendarEvent.notifyEvent()).isTrue();
            softly.assertThat(calendarEvent.calendarURI()).isEqualTo("6853ca6c1cbe800055fd838b");
            softly.assertThat(calendarEvent.eventPath()).isEqualTo("/calendars/6853ca6c1cbe800055fd838a/6853ca6c1cbe800055fd838a/a92de371-8529-45f8-948a-70ddf27bbc09.ics");
        });
    }
}
