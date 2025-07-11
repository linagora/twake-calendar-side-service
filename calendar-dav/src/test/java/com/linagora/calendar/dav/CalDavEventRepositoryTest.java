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

package com.linagora.calendar.dav;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.dto.CalendarEventReportResponse;
import com.linagora.calendar.dav.dto.VCalendarDto;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.parameter.PartStat;
import net.javacrumbs.jsonunit.core.Option;

public class CalDavEventRepositoryTest {

    private static final String datePattern = "yyyyMMdd'T'HHmmss";

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static DavTestHelper davTestHelper;

    private CalDavEventRepository testee;
    private CalDavClient calDavClient;

    private OpenPaaSUser organizer;
    private OpenPaaSUser attendee;

    @BeforeAll
    static void setUp() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
    }

    @BeforeEach
    void setupEach() throws Exception {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
        testee = new CalDavEventRepository(calDavClient);

        organizer = sabreDavExtension.newTestUser();
        attendee = sabreDavExtension.newTestUser();
    }

    @Test
    void updatePartStatShouldReturnCorrectResponse() {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid, PartStat.NEEDS_ACTION);

        CalendarEventReportResponse result = testee.updatePartStat(attendee.username(), attendee.id(),
            new EventUid(eventUid), PartStat.ACCEPTED).block();

        assertThat(result).isNotNull();

        assertThatJson(asJson(VCalendarDto.from(result)))
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  "vcalendar",
                  [
                    ["version", {}, "text", "2.0"],
                    ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.1.3//EN"],
                    ["calscale", {}, "text", "GREGORIAN"]
                  ],
                  [
                    [
                      "vtimezone",
                      [
                        ["tzid", {}, "text", "Asia/Ho_Chi_Minh"]
                      ],
                      [
                        [
                          "standard",
                          [
                            ["tzoffsetfrom", {}, "utc-offset", "+07:00"],
                            ["tzoffsetto", {}, "utc-offset", "+07:00"],
                            ["tzname", {}, "text", "ICT"],
                            ["dtstart", {}, "date-time", "1970-01-01T00:00:00"]
                          ],
                          []
                        ]
                      ]
                    ],
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "%s"],
                        ["dtstamp", {}, "date-time", "${json-unit.any-string}"],
                        ["sequence", {}, "integer", 2],
                        ["dtstart", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["dtend", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["summary", {}, "text", "Twake Calendar - Sprint planning #04"],
                        ["organizer", { "cn": "Van Tung TRAN", "schedule-status": "1.1" }, "cal-address", "mailto:%s"],
                        ["attendee", { "cn": "Benoît TELLIER", "partstat": "ACCEPTED" }, "cal-address", "mailto:%s"]
                      ],
                      []
                    ]
                  ]
                ]
                """.formatted(
                eventUid,
                organizer.username().asString(),
                attendee.username().asString()
            ));
    }

    @Test
    void updatePartStatShouldPersistAcceptedStatusOnDavServer() {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid, PartStat.NEEDS_ACTION);

        testee.updatePartStat(attendee.username(), attendee.id(), new EventUid(eventUid), PartStat.ACCEPTED).block();

        CalendarEventReportResponse response = calDavClient.calendarReportByUid(attendee.username(), attendee.id(), eventUid).block();

        assertThat(response).isNotNull();
        assertThat(response.value().toPrettyString()).contains("\"partstat\" : \"ACCEPTED\"");
    }

    @Test
    void updatePartStatShouldAllowChangingStatus() {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid, PartStat.NEEDS_ACTION);

        testee.updatePartStat(attendee.username(), attendee.id(), new EventUid(eventUid), PartStat.ACCEPTED)
            .block();

        Supplier<String> calendarDataSupplier = () -> calDavClient
            .calendarReportByUid(attendee.username(), attendee.id(), eventUid)
            .block().value().toPrettyString();

        assertThat(calendarDataSupplier.get()).contains("\"partstat\" : \"ACCEPTED\"")
            .doesNotContain("\"partstat\" : \"DECLINED\"");

        testee.updatePartStat(attendee.username(), attendee.id(), new EventUid(eventUid), PartStat.DECLINED)
            .block();

        assertThat(calendarDataSupplier.get()).contains("\"partstat\" : \"DECLINED\"")
            .doesNotContain("\"partstat\" : \"ACCEPTED\"");
    }

    @Test
    void updatePartStatShouldBeIdempotentForSameParticipationStatus() {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid, PartStat.NEEDS_ACTION);

        testee.updatePartStat(attendee.username(), attendee.id(), new EventUid(eventUid), PartStat.ACCEPTED).block();

        testee.updatePartStat(attendee.username(), attendee.id(), new EventUid(eventUid), PartStat.ACCEPTED).block();

        CalendarEventReportResponse response = calDavClient
            .calendarReportByUid(attendee.username(), attendee.id(), eventUid)
            .block();

        assertThat(response).isNotNull();
        assertThat(response.value().toPrettyString()).contains("\"partstat\" : \"ACCEPTED\"");
    }

    @Test
    void updatePartStatShouldThrowWhenEventUidNotFound() {
        String nonExistingEventUid = UUID.randomUUID().toString();

        assertThatThrownBy(() -> testee.updatePartStat(
            attendee.username(),
            attendee.id(),
            new EventUid(nonExistingEventUid),
            PartStat.ACCEPTED).block())
            .isInstanceOf(CalendarEventNotFoundException.class)
            .hasMessageContaining(nonExistingEventUid)
            .hasMessageContaining(attendee.username().asString());
    }

    @Test
    void updatePartStatShouldThrowWhenCalendarNotFound() {
        String eventUid = UUID.randomUUID().toString();
        upsertCalendarForTest(eventUid, PartStat.NEEDS_ACTION);

        OpenPaaSId calendarIdNotFound = new OpenPaaSId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> testee.updatePartStat(
            attendee.username(), calendarIdNotFound,
            new EventUid(eventUid),
            PartStat.ACCEPTED).block())
            .isInstanceOf(CalendarEventNotFoundException.class)
            .hasMessageContaining(calendarIdNotFound.value())
            .hasMessageContaining(attendee.username().asString());
    }

    @Test
    void updatePartStatShouldSupportRecurrenceEventWithRecurrenceId() {
        String eventUid = UUID.randomUUID().toString();

        String calendarData = String.format("""
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Twake//Recurrence Test//EN
                BEGIN:VEVENT
                UID:%1$s
                DTSTAMP:%2$sZ
                DTSTART;TZID=Asia/Ho_Chi_Minh:%3$s
                DTEND;TZID=Asia/Ho_Chi_Minh:%4$s
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:Recurring Standup Meeting
                ORGANIZER;CN=Van Tung TRAN:mailto:%5$s
                ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:%6$s
                END:VEVENT
                BEGIN:VEVENT
                UID:%1$s
                RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:%7$s
                DTSTAMP:%2$sZ
                DTSTART;TZID=Asia/Ho_Chi_Minh:%8$s
                DTEND;TZID=Asia/Ho_Chi_Minh:%9$s
                SUMMARY:Modified Instance
                ORGANIZER;CN=Van Tung TRAN:mailto:%5$s
                ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:%6$s
                END:VEVENT
                END:VCALENDAR
                """,
            eventUid,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")),
            dateTime(0, 10, 0),
            dateTime(0, 10, 30),
            organizer.username().asString(),
            attendee.username().asString(),
            dateTime(1, 10, 0),
            dateTime(1, 11, 0),
            dateTime(1, 11, 30));

        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);
        waitForEventCreation(attendee);

        CalendarEventReportResponse result = testee.updatePartStat(
            attendee.username(), attendee.id(), new EventUid(eventUid), PartStat.ACCEPTED).block();

        assertThat(result).isNotNull();
        assertThatJson(asJson(VCalendarDto.from(result)))
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                [
                  "vcalendar",
                  [
                    ["version", {}, "text", "2.0"],
                    ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.1.3//EN"],
                    ["calscale", {}, "text", "GREGORIAN"]
                  ],
                  [
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "%s"],
                        ["dtstamp", {}, "date-time", "${json-unit.any-string}"],
                        ["dtstart", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["dtend", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["rrule", {}, "recur", { "freq": "DAILY", "count": 3 }],
                        ["summary", {}, "text", "Recurring Standup Meeting"],
                        ["organizer", { "cn": "Van Tung TRAN", "schedule-status": "1.1" }, "cal-address", "mailto:%s"],
                        ["sequence", {}, "integer", 1],
                        ["attendee", { "cn": "Benoît TELLIER", "partstat": "ACCEPTED" }, "cal-address", "mailto:%s"]
                      ],
                      []
                    ],
                    [
                      "vevent",
                      [
                        ["uid", {}, "text", "%s"],
                        ["recurrence-id", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["dtstamp", {}, "date-time", "${json-unit.any-string}"],
                        ["dtstart", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["dtend", { "tzid": "Asia/Ho_Chi_Minh" }, "date-time", "${json-unit.any-string}"],
                        ["summary", {}, "text", "Modified Instance"],
                        ["organizer", { "cn": "Van Tung TRAN" }, "cal-address", "mailto:%s"],
                        ["attendee", { "partstat": "ACCEPTED", "cn": "Benoît TELLIER" }, "cal-address", "mailto:%s"]
                      ],
                      []
                    ]
                  ]
                ]
                """.formatted(
                eventUid,
                organizer.username().asString(),
                attendee.username().asString(),
                eventUid,
                organizer.username().asString(),
                attendee.username().asString()
            ));

    }

    private void waitForEventCreation(OpenPaaSUser user) {
        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(davTestHelper.findFirstEventId(user))
                .withFailMessage("Event not created for user: " + user.username())
                .isPresent());
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        PartStat partStat) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String startDateTime = LocalDateTime.now().plusDays(3).format(dateTimeFormatter);
        String endDateTime = LocalDateTime.now().plusDays(3).plusHours(1).format(dateTimeFormatter);

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:{dtStamp}Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{startDateTime}
            DTEND;TZID=Asia/Ho_Chi_Minh:{endDateTime}
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{startDateTime}", startDateTime)
            .replace("{endDateTime}", endDateTime)
            .replace("{dtStamp}", LocalDateTime.now().format(dateTimeFormatter))
            .replace("{partStat}", partStat.getValue());
    }

    private String dateTime(int plusDays, int hour, int minute) {
        return LocalDateTime.now()
            .plusDays(plusDays)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .format(DateTimeFormatter.ofPattern(datePattern));
    }

    private String asJson(VCalendarDto dto) {
        try {
            return new ObjectMapper().writeValueAsString(dto.value());
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert VCalendarDto to JSON", e);
        }
    }

    private void upsertCalendarForTest(String eventUid, PartStat partStat) {
        davTestHelper.upsertCalendar(
            organizer,
            generateCalendarData(eventUid, organizer.username().asString(), attendee.username().asString(), partStat),
            eventUid);
        waitForEventCreation(attendee);
    }
}
