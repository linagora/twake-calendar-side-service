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

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import jakarta.mail.internet.AddressException;

import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.EventUid;

public class EventFieldConverterTest {

    @Test
    void fromBasicCreatedMessageShouldSucceed() throws AddressException {
        String json = """
            {
                 "eventPath": "\\/calendars\\/6801fcef72cc50005a04e5fb\\/6801fcef72cc50005a04e5fb\\/a0b5a363-e56f-490b-bfa7-89111b0fdd9b.ics",
                 "event": [
                     "vcalendar",
                     [
                         [
                             "version",
                             {},
                             "text",
                             "2.0"
                         ],
                         [
                             "prodid",
                             {},
                             "text",
                             "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                         ]
                     ],
                     [
                         [
                             "vtimezone",
                             [
                                 [
                                     "tzid",
                                     {},
                                     "text",
                                     "Asia\\/Jakarta"
                                 ]
                             ],
                             [
                                 [
                                     "standard",
                                     [
                                         [
                                             "tzoffsetfrom",
                                             {},
                                             "utc-offset",
                                             "+07:00"
                                         ],
                                         [
                                             "tzoffsetto",
                                             {},
                                             "utc-offset",
                                             "+07:00"
                                         ],
                                         [
                                             "tzname",
                                             {},
                                             "text",
                                             "WIB"
                                         ],
                                         [
                                             "dtstart",
                                             {},
                                             "date-time",
                                             "1970-01-01T00:00:00"
                                         ]
                                     ],
                                     []
                                 ]
                             ]
                         ],
                         [
                             "vevent",
                             [
                                 [
                                     "uid",
                                     {},
                                     "text",
                                     "a0b5a363-e56f-490b-bfa7-89111b0fdd9b"
                                 ],
                                 [
                                     "transp",
                                     {},
                                     "text",
                                     "OPAQUE"
                                 ],
                                 [
                                     "dtstart",
                                     {
                                         "tzid": "Asia\\/Saigon"
                                     },
                                     "date-time",
                                     "2025-04-19T11:00:00"
                                 ],
                                 [
                                     "dtend",
                                     {
                                         "tzid": "Asia\\/Saigon"
                                     },
                                     "date-time",
                                     "2025-04-19T11:30:00"
                                 ],
                                 [
                                     "class",
                                     {},
                                     "text",
                                     "PUBLIC"
                                 ],
                                 [
                                     "summary",
                                     {},
                                     "text",
                                     "Title 1"
                                 ],
                                 [
                                     "description",
                                     {},
                                     "text",
                                     "note tung"
                                 ],
                                 [
                                     "organizer",
                                     {
                                         "cn": "John1 Doe1"
                                     },
                                     "cal-address",
                                     "mailto:user1@open-paas.org"
                                 ],
                                 [
                                     "attendee",
                                     {
                                         "partstat": "NEEDS-ACTION",
                                         "rsvp": "TRUE",
                                         "role": "REQ-PARTICIPANT",
                                         "cutype": "INDIVIDUAL",
                                         "cn": "John2 Doe2",
                                         "schedule-status": "1.1"
                                     },
                                     "cal-address",
                                     "mailto:user2@open-paas.org"
                                 ],
                                 [
                                     "attendee",
                                     {
                                         "partstat": "ACCEPTED",
                                         "rsvp": "FALSE",
                                         "role": "CHAIR",
                                         "cutype": "INDIVIDUAL"
                                     },
                                     "cal-address",
                                     "mailto:user1@open-paas.org"
                                 ],
                                 [
                                     "dtstamp",
                                     {},
                                     "date-time",
                                     "2025-04-18T07:47:48Z"
                                 ],
                                 [
                                     "location",
                                     {},
                                     "text",
                                     "Room 42, Main Office"
                                 ]
                             ],
                             []
                         ]
                     ]
                 ],
                 "import": true,
                 "etag": "\\"f066260d3a4fca51ae0de0618e9555cc\\""
             }""";

        CalendarEventMessage createdMessage = CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));

        assertThat(createdMessage.eventPath)
            .isEqualTo("/calendars/6801fcef72cc50005a04e5fb/6801fcef72cc50005a04e5fb/a0b5a363-e56f-490b-bfa7-89111b0fdd9b.ics");
        assertThat(createdMessage.isImport).isTrue();

        Set<EventFields> eventProperties = EventFieldConverter.from(createdMessage).events();

        assertThat(eventProperties).hasSize(1);
        EventFields eventFieldsActual = eventProperties.iterator().next();

        EventFields eventFieldsExpected = EventFields.builder()
            .calendarURL(new CalendarURL(new OpenPaaSId("6801fcef72cc50005a04e5fb"), new OpenPaaSId("6801fcef72cc50005a04e5fb")))
            .uid(new EventUid("a0b5a363-e56f-490b-bfa7-89111b0fdd9b"))
            .summary("Title 1")
            .location("Room 42, Main Office")
            .description("note tung")
            .clazz("PUBLIC")
            .start(Instant.parse("2025-04-19T04:00:00Z"))
            .end(Instant.parse("2025-04-19T04:30:00Z"))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .organizer(EventFields.Person.of("John1 Doe1", "user1@open-paas.org"))
            .addAttendee(EventFields.Person.of("John2 Doe2", "user2@open-paas.org"))
            .addAttendee(EventFields.Person.of(null, "user1@open-paas.org"))
            .build();

        assertThat(eventFieldsActual).isEqualTo(eventFieldsExpected);
    }

    @Test
    void fromCreatedMessageWithRecurrenceEventShouldSucceed() {
        String json = """
            {
                     "eventPath": "\\/calendars\\/68242f7f7617140059448fb4\\/68242f7f7617140059448fb4\\/87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6.ics",
                     "event": [
                         "vcalendar",
                         [
                             [
                                 "version",
                                 {},
                                 "text",
                                 "2.0"
                             ],
                             [
                                 "prodid",
                                 {},
                                 "text",
                                 "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                             ]
                         ],
                         [
                             [
                                 "vtimezone",
                                 [
                                     [
                                         "tzid",
                                         {},
                                         "text",
                                         "Asia\\/Jakarta"
                                     ]
                                 ],
                                 [
                                     [
                                         "standard",
                                         [
                                             [
                                                 "tzoffsetfrom",
                                                 {},
                                                 "utc-offset",
                                                 "+07:00"
                                             ],
                                             [
                                                 "tzoffsetto",
                                                 {},
                                                 "utc-offset",
                                                 "+07:00"
                                             ],
                                             [
                                                 "tzname",
                                                 {},
                                                 "text",
                                                 "WIB"
                                             ],
                                             [
                                                 "dtstart",
                                                 {},
                                                 "date-time",
                                                 "1970-01-01T00:00:00"
                                             ]
                                         ],
                                         []
                                     ]
                                 ]
                             ],
                             [
                                 "vevent",
                                 [
                                     [
                                         "uid",
                                         {},
                                         "text",
                                         "87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6"
                                     ],
                                     [
                                         "transp",
                                         {},
                                         "text",
                                         "OPAQUE"
                                     ],
                                     [
                                         "dtstart",
                                         {
                                             "tzid": "Asia\\/Saigon"
                                         },
                                         "date-time",
                                         "2025-05-16T11:00:00"
                                     ],
                                     [
                                         "dtend",
                                         {
                                             "tzid": "Asia\\/Saigon"
                                         },
                                         "date-time",
                                         "2025-05-16T11:30:00"
                                     ],
                                     [
                                         "class",
                                         {},
                                         "text",
                                         "PUBLIC"
                                     ],
                                     [
                                         "summary",
                                         {},
                                         "text",
                                         "Re3"
                                     ],
                                     [
                                         "rrule",
                                         {},
                                         "recur",
                                         {
                                             "freq": "WEEKLY",
                                             "count": 4,
                                             "byday": "TH"
                                         }
                                     ],
                                     [
                                         "organizer",
                                         {
                                             "cn": "John1 Doe1"
                                         },
                                         "cal-address",
                                         "mailto:user1@open-paas.org"
                                     ],
                                     [
                                         "attendee",
                                         {
                                             "partstat": "ACCEPTED",
                                             "rsvp": "FALSE",
                                             "role": "CHAIR",
                                             "cutype": "INDIVIDUAL"
                                         },
                                         "cal-address",
                                         "mailto:user1@open-paas.org"
                                     ],
                                     [
                                         "dtstamp",
                                         {},
                                         "date-time",
                                         "2025-05-14T06:08:28Z"
                                     ]
                                 ],
                                 []
                             ]
                         ]
                     ],
                     "import": false,
                     "etag": "\\"c4b9e923145228ae00e1242df7454e4f\\""
                 }""";

        CalendarEventMessage createdMessage = CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
        Set<EventFields> eventProperties = EventFieldConverter.from(createdMessage).events();
        assertThat(eventProperties).hasSize(1);
        EventFields eventFieldsActual = eventProperties.iterator().next();
        assertThat(eventFieldsActual.isRecurrentMaster()).isTrue();
    }

    @Test
    void fromCreatedMessageWithResourceShouldSucceed() throws Exception {
        String json = """
            {
                "eventPath": "\\/calendars\\/68242f7f7617140059448fb4\\/68242f7f7617140059448fb4\\/9114801a-2b53-467e-b093-a7916922ebfa.ics",
                "event": [
                    "vcalendar",
                    [
                        [
                            "version",
                            {},
                            "text",
                            "2.0"
                        ],
                        [
                            "prodid",
                            {},
                            "text",
                            "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                        ]
                    ],
                    [
                        [
                            "vtimezone",
                            [
                                [
                                    "tzid",
                                    {},
                                    "text",
                                    "Asia\\/Jakarta"
                                ]
                            ],
                            [
                                [
                                    "standard",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "WIB"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-01-01T00:00:00"
                                        ]
                                    ],
                                    []
                                ]
                            ]
                        ],
                        [
                            "vevent",
                            [
                                [
                                    "uid",
                                    {},
                                    "text",
                                    "9114801a-2b53-467e-b093-a7916922ebfa"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {
                                        "tzid": "Europe\\/Kiev"
                                    },
                                    "date-time",
                                    "2025-05-21T11:00:00"
                                ],
                                [
                                    "dtend",
                                    {
                                        "tzid": "Europe\\/Kiev"
                                    },
                                    "date-time",
                                    "2025-05-21T11:30:00"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "PUBLIC"
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "Resource"
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "NEEDS-ACTION",
                                        "rsvp": "TRUE",
                                        "role": "REQ-PARTICIPANT",
                                        "cutype": "INDIVIDUAL",
                                        "cn": "John7 Doe7",
                                        "schedule-status": "1.1"
                                    },
                                    "cal-address",
                                    "mailto:user7@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "TENTATIVE",
                                        "rsvp": "TRUE",
                                        "role": "REQ-PARTICIPANT",
                                        "cutype": "RESOURCE",
                                        "cn": "Test resource",
                                        "schedule-status": "5.1"
                                    },
                                    "cal-address",
                                    "mailto:a111@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "TENTATIVE",
                                        "rsvp": "TRUE",
                                        "role": "REQ-PARTICIPANT",
                                        "cutype": "RESOURCE",
                                        "cn": "Test resource2",
                                        "schedule-status": "5.1"
                                    },
                                    "cal-address",
                                    "mailto:b222@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "rsvp": "FALSE",
                                        "role": "CHAIR",
                                        "cutype": "INDIVIDUAL"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "dtstamp",
                                    {},
                                    "date-time",
                                    "2025-05-14T06:49:36Z"
                                ]
                            ],
                            []
                        ]
                    ]
                ],
                "import": false,
                "etag": "\\"bb4e95379613d94178c1a75483f24e77\\""
            }""";


        CalendarEventMessage createdMessage = CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
        Set<EventFields> eventProperties = EventFieldConverter.from(createdMessage).events();
        assertThat(eventProperties).hasSize(1);
        EventFields eventFieldsActual = eventProperties.iterator().next();

        assertThat(eventFieldsActual.resources())
            .containsExactlyInAnyOrder(EventFields.Person.of("Test resource", "a111@open-paas.org"),
                EventFields.Person.of("Test resource2", "b222@open-paas.org"));

        assertThat(eventFieldsActual.attendees())
            .containsExactlyInAnyOrder(EventFields.Person.of(null, "user1@open-paas.org"),
                EventFields.Person.of("John7 Doe7", "user7@open-paas.org"));
    }

    @Test
    void fromCreatedMessageWithAllDayShouldSucceed() {
        String json = """
            {
                "eventPath": "\\/calendars\\/68242f7f7617140059448fb4\\/68242f7f7617140059448fb4\\/71a48b8d-396f-4363-bac9-05a32bada4c6.ics",
                "event": [
                    "vcalendar",
                    [
                        [
                            "version",
                            {},
                            "text",
                            "2.0"
                        ],
                        [
                            "prodid",
                            {},
                            "text",
                            "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                        ]
                    ],
                    [
                        [
                            "vtimezone",
                            [
                                [
                                    "tzid",
                                    {},
                                    "text",
                                    "Asia\\/Jakarta"
                                ]
                            ],
                            [
                                [
                                    "standard",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "WIB"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-01-01T00:00:00"
                                        ]
                                    ],
                                    []
                                ]
                            ]
                        ],
                        [
                            "vevent",
                            [
                                [
                                    "uid",
                                    {},
                                    "text",
                                    "71a48b8d-396f-4363-bac9-05a32bada4c6"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {},
                                    "date",
                                    "2025-05-23"
                                ],
                                [
                                    "dtend",
                                    {},
                                    "date",
                                    "2025-05-26"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "CONFIDENTIAL"
                                ],
                                [
                                    "location",
                                    {},
                                    "text",
                                    "location2"
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "Full topping"
                                ],
                                [
                                    "description",
                                    {},
                                    "text",
                                    "Note 9"
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "NEEDS-ACTION",
                                        "rsvp": "TRUE",
                                        "role": "REQ-PARTICIPANT",
                                        "cutype": "INDIVIDUAL",
                                        "cn": "John9 Doe9",
                                        "schedule-status": "1.1"
                                    },
                                    "cal-address",
                                    "mailto:user9@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "rsvp": "FALSE",
                                        "role": "CHAIR",
                                        "cutype": "INDIVIDUAL"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "dtstamp",
                                    {},
                                    "date-time",
                                    "2025-05-14T07:07:37Z"
                                ]
                            ],
                            [
                                [
                                    "valarm",
                                    [
                                        [
                                            "trigger",
                                            {},
                                            "duration",
                                            "-PT1H"
                                        ],
                                        [
                                            "action",
                                            {},
                                            "text",
                                            "EMAIL"
                                        ],
                                        [
                                            "attendee",
                                            {},
                                            "cal-address",
                                            "mailto:user1@open-paas.org"
                                        ],
                                        [
                                            "summary",
                                            {},
                                            "text",
                                            "Full topping"
                                        ],
                                        [
                                            "description",
                                            {},
                                            "text",
                                            "This is an automatic alarm sent by OpenPaas\\\\nThe event Full topping will start in 9 days\\\\nstart: Fri May 23 2025 04:00:00 GMT+0300 \\\\nend: Mon May 26 2025 04:00:00 GMT+0300 \\\\nlocation: location2 \\\\nclass: CONFIDENTIAL \\\\n"
                                        ]
                                    ],
                                    []
                                ]
                            ]
                        ]
                    ]
                ],
                "import": false,
                "etag": "\\"bc8b928eb972fb9b6f6a2fd80a763b83\\""
            }""";

        CalendarEventMessage createdMessage = CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));
        Set<EventFields> eventProperties = EventFieldConverter.from(createdMessage).events();
        assertThat(eventProperties).hasSize(1);
        EventFields eventFieldsActual = eventProperties.iterator().next();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(eventFieldsActual.allDay()).isTrue();
            softly.assertThat(eventFieldsActual.start())
                .isEqualTo(Instant.parse("2025-05-23T00:00:00Z"));
            softly.assertThat(eventFieldsActual.end())
                .isEqualTo(Instant.parse("2025-05-26T00:00:00Z"));
            softly.assertThat(eventFieldsActual.durationInDays())
                .isEqualTo(3);
        }));
    }


    @Test
    void fromDeletedMessageShouldSucceed() {
        String json = """
            {
                "eventPath": "\\/calendars\\/6801fcef72cc50005a04e5fb\\/6801fcef72cc50005a04e5fb\\/a0b5a363-e56f-490b-bfa7-89111b0fdd9b.ics",
                "event": [
                    "vcalendar",
                    [
                        [
                            "version",
                            {},
                            "text",
                            "2.0"
                        ],
                        [
                            "prodid",
                            {},
                            "text",
                            "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                        ]
                    ],
                    [
                        [
                            "vtimezone",
                            [
                                [
                                    "tzid",
                                    {},
                                    "text",
                                    "Asia\\/Jakarta"
                                ]
                            ],
                            [
                                [
                                    "standard",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "WIB"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-01-01T00:00:00"
                                        ]
                                    ],
                                    []
                                ]
                            ]
                        ],
                        [
                            "vevent",
                            [
                                [
                                    "uid",
                                    {},
                                    "text",
                                    "a0b5a363-e56f-490b-bfa7-89111b0fdd9b"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-04-19T11:00:00"
                                ],
                                [
                                    "dtend",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-04-19T11:30:00"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "PUBLIC"
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "Title 1"
                                ],
                                [
                                    "description",
                                    {},
                                    "text",
                                    "note tung"
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "role": "REQ-PARTICIPANT",
                                        "cutype": "INDIVIDUAL",
                                        "cn": "John2 Doe2",
                                        "schedule-status": "2.0"
                                    },
                                    "cal-address",
                                    "mailto:user2@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "rsvp": "FALSE",
                                        "role": "CHAIR",
                                        "cutype": "INDIVIDUAL"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "dtstamp",
                                    {},
                                    "date-time",
                                    "2025-04-18T07:47:48Z"
                                ]
                            ],
                            []
                        ]
                    ]
                ],
                "import": false
            }
            """;
        CalendarEventMessage.Deleted deletedMessage = CalendarEventMessage.Deleted.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertThat(deletedMessage.extractEventUid())
            .containsExactlyInAnyOrder(new EventUid("a0b5a363-e56f-490b-bfa7-89111b0fdd9b"));
        assertThat(deletedMessage.extractCalendarURL())
            .isEqualTo(new CalendarURL(new OpenPaaSId("6801fcef72cc50005a04e5fb"), new OpenPaaSId("6801fcef72cc50005a04e5fb")));
    }

    @Test
    void fromUpdateMessageWithRecurrenceEventShouldSucceed() throws Exception {
        String json = """
            {
                "eventPath": "\\/calendars\\/68242f7f7617140059448fb4\\/68242f7f7617140059448fb4\\/87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6.ics",
                "event": [
                    "vcalendar",
                    [
                        [
                            "version",
                            {},
                            "text",
                            "2.0"
                        ],
                        [
                            "prodid",
                            {},
                            "text",
                            "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                        ]
                    ],
                    [
                        [
                            "vtimezone",
                            [
                                [
                                    "tzid",
                                    {},
                                    "text",
                                    "Asia\\/Jakarta"
                                ]
                            ],
                            [
                                [
                                    "standard",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "WIB"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-01-01T00:00:00"
                                        ]
                                    ],
                                    []
                                ]
                            ]
                        ],
                        [
                            "vevent",
                            [
                                [
                                    "uid",
                                    {},
                                    "text",
                                    "87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-05-16T11:00:00"
                                ],
                                [
                                    "dtend",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-05-16T11:30:00"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "PUBLIC"
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "Re3"
                                ],
                                [
                                    "rrule",
                                    {},
                                    "recur",
                                    {
                                        "freq": "WEEKLY",
                                        "count": 4,
                                        "byday": "TH"
                                    }
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "rsvp": "FALSE",
                                        "role": "CHAIR",
                                        "cutype": "INDIVIDUAL"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "dtstamp",
                                    {},
                                    "date-time",
                                    "2025-05-14T06:08:28Z"
                                ]
                            ],
                            []
                        ],
                        [
                            "vevent",
                            [
                                [
                                    "uid",
                                    {},
                                    "text",
                                    "87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-05-29T13:00:00"
                                ],
                                [
                                    "dtend",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-05-29T13:30:00"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "PUBLIC"
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "Re3"
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "dtstamp",
                                    {},
                                    "date-time",
                                    "2025-05-14T06:08:28Z"
                                ],
                                [
                                    "recurrence-id",
                                    {},
                                    "date-time",
                                    "2025-05-29T04:00:00Z"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "rsvp": "FALSE",
                                        "role": "CHAIR",
                                        "cutype": "INDIVIDUAL",
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "sequence",
                                    {},
                                    "integer",
                                    1
                                ]
                            ],
                            []
                        ]
                    ]
                ],
                "import": false,
                "old_event": [
                    "vcalendar",
                    [
                        [
                            "version",
                            {},
                            "text",
                            "2.0"
                        ],
                        [
                            "prodid",
                            {},
                            "text",
                            "-\\/\\/Sabre\\/\\/Sabre VObject 4.2.2\\/\\/EN"
                        ]
                    ],
                    [
                        [
                            "vtimezone",
                            [
                                [
                                    "tzid",
                                    {},
                                    "text",
                                    "Asia\\/Jakarta"
                                ]
                            ],
                            [
                                [
                                    "standard",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+07:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "WIB"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-01-01T00:00:00"
                                        ]
                                    ],
                                    []
                                ]
                            ]
                        ],
                        [
                            "vevent",
                            [
                                [
                                    "uid",
                                    {},
                                    "text",
                                    "87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-05-16T11:00:00"
                                ],
                                [
                                    "dtend",
                                    {
                                        "tzid": "Asia\\/Saigon"
                                    },
                                    "date-time",
                                    "2025-05-16T11:30:00"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "PUBLIC"
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "Re3"
                                ],
                                [
                                    "rrule",
                                    {},
                                    "recur",
                                    {
                                        "freq": "WEEKLY",
                                        "count": 4,
                                        "byday": "TH"
                                    }
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "John1 Doe1"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "attendee",
                                    {
                                        "partstat": "ACCEPTED",
                                        "rsvp": "FALSE",
                                        "role": "CHAIR",
                                        "cutype": "INDIVIDUAL"
                                    },
                                    "cal-address",
                                    "mailto:user1@open-paas.org"
                                ],
                                [
                                    "dtstamp",
                                    {},
                                    "date-time",
                                    "2025-05-14T06:08:28Z"
                                ]
                            ],
                            []
                        ]
                    ]
                ],
                "etag": "\\"e0cea89930c0a1511025be631510ce60\\""
            }""";

        CalendarEventMessage.CreatedOrUpdated updatedMessage = CalendarEventMessage.CreatedOrUpdated.deserialize(json.getBytes(StandardCharsets.UTF_8));

        CalendarURL calendarURL = updatedMessage.extractCalendarURL();
        assertThat(calendarURL)
            .isEqualTo(new CalendarURL(new OpenPaaSId("68242f7f7617140059448fb4"), new OpenPaaSId("68242f7f7617140059448fb4")));

        CalendarEvents calendarEvents = updatedMessage.extractCalendarEvents();

        EventFields masterEvents = EventFields.builder()
            .calendarURL(calendarURL)
            .uid("87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6")
            .start(Instant.parse("2025-05-16T04:00:00Z"))
            .end(Instant.parse("2025-05-16T04:30:00Z"))
            .clazz("PUBLIC")
            .summary("Re3")
            .isRecurrentMaster(true)
            .dtStamp(Instant.parse("2025-05-14T06:08:28Z"))
            .organizer(EventFields.Person.of("John1 Doe1", "user1@open-paas.org"))
            .addAttendee(EventFields.Person.of(null, "user1@open-paas.org"))
            .build();

        EventFields recurrenceEvent = EventFields.builder()
            .calendarURL(calendarURL)
            .uid("87d9d3ab-e2f5-4613-8e9c-dbc11afa69e6")
            .start(Instant.parse("2025-05-29T06:00:00Z"))
            .end(Instant.parse("2025-05-29T06:30:00Z"))
            .clazz("PUBLIC")
            .summary("Re3")
            .isRecurrentMaster(false)
            .organizer(EventFields.Person.of("John1 Doe1", "user1@open-paas.org"))
            .addAttendee(EventFields.Person.of("John1 Doe1", "user1@open-paas.org"))
            .dtStamp(Instant.parse("2025-05-14T06:08:28Z"))
            .build();

        assertThat(calendarEvents)
            .isEqualTo(CalendarEvents.of(masterEvents, recurrenceEvent));
    }
}