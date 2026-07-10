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

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventAuditLogConsumerTest {
    private static final String EVENT_OWNER = "64b111111111111111111111";
    private static final String CALENDAR_EVENT_NOTIFICATION = """
        {
           "eventPath": "/calendars/base1/calendar1/3423434.ics",
           "event": []
        }
        """;

    @ParameterizedTest
    @MethodSource("eventAmqpMessageSamplesFromExistingConsumerTests")
    void formatMessageShouldUseHumanReadableTemplateAndResourcePathForEventSamples(EventAmqpMessageSample sample) {
        assertThat(EventAuditLogConsumer.formatMessage(sample.amqpMessage(), sample.exchangeName()))
            .isEqualTo(sample.expectedMessage() + " [" + sample.expectedPath() + "]");
    }

    @ParameterizedTest
    @MethodSource("eventAmqpMessageSamplesFromExistingConsumerTests")
    void extractOwnerShouldUsePathFromExistingEventAmqpMessageSamples(EventAmqpMessageSample sample) {
        assertThat(EventAuditLogConsumer.extractOwner(sample.amqpMessage()))
            .contains(sample.expectedOwner());
    }

    @Test
    void formatMessageShouldNotLeakPrivateEventPayloadFromIndexerConsumerSample() {
        String amqpMessage = calendarEventFromIndexerConsumer(EVENT_OWNER);

        assertThat(EventAuditLogConsumer.formatMessage(amqpMessage, "calendar:event:created"))
            .doesNotContain("VEVENT")
            .doesNotContain("Title 1")
            .doesNotContain("John1 Doe1")
            .doesNotContain("mailto:user1@open-paas.org");
    }

    @Test
    void extractOwnerShouldPreferExplicitOwnerField() {
        String amqpMessage = """
            {
              "owner": "explicit-owner",
              "eventPath": "/calendars/path-owner/calendar/event.ics"
            }
            """;

        assertThat(EventAuditLogConsumer.extractOwner(amqpMessage))
            .contains("explicit-owner");
    }

    @Test
    void extractConnectedUserShouldReturnPrincipalWhenFieldIsPresent() {
        String amqpMessage = """
            {
              "eventPath": "/calendars/base1/calendar1/3423434.ics",
              "connectedUser": "principals/users/64b111111111111111111111"
            }
            """;

        assertThat(EventAuditLogConsumer.extractConnectedUser(amqpMessage))
            .contains("principals/users/64b111111111111111111111");
    }

    @Test
    void extractConnectedUserShouldReturnDelegatePrincipalWhenActingOnAnotherOwnerCalendar() {
        String amqpMessage = """
            {
              "eventPath": "/calendars/owner/calendar1/3423434.ics",
              "connectedUser": "principals/users/delegate"
            }
            """;

        assertThat(EventAuditLogConsumer.extractConnectedUser(amqpMessage))
            .contains("principals/users/delegate");
        assertThat(EventAuditLogConsumer.extractOwner(amqpMessage))
            .contains("owner");
    }

    @Test
    void extractConnectedUserShouldBeEmptyWhenFieldIsAbsent() {
        assertThat(EventAuditLogConsumer.extractConnectedUser(CALENDAR_EVENT_NOTIFICATION))
            .isEmpty();
    }

    @Test
    void extractConnectedUserShouldBeEmptyWhenFieldIsNull() {
        String amqpMessage = """
            {
              "eventPath": "/calendars/base1/calendar1/3423434.ics",
              "connectedUser": null
            }
            """;

        assertThat(EventAuditLogConsumer.extractConnectedUser(amqpMessage))
            .isEmpty();
    }

    @Test
    void extractConnectedUserShouldBeEmptyWhenFieldIsBlank() {
        String amqpMessage = """
            {
              "eventPath": "/calendars/base1/calendar1/3423434.ics",
              "connectedUser": ""
            }
            """;

        assertThat(EventAuditLogConsumer.extractConnectedUser(amqpMessage))
            .isEmpty();
    }

    @Test
    void extractConnectedUserShouldBeEmptyWhenPayloadIsNotJson() {
        assertThat(EventAuditLogConsumer.extractConnectedUser("not json"))
            .isEmpty();
    }

    @Test
    void formatMessageShouldNotBeImpactedByConnectedUser() {
        String amqpMessage = """
            {
              "eventPath": "/calendars/base1/calendar1/3423434.ics",
              "connectedUser": "principals/users/delegate"
            }
            """;

        assertThat(EventAuditLogConsumer.formatMessage(amqpMessage, "calendar:event:updated"))
            .isEqualTo("Calendar event updated [/calendars/base1/calendar1/3423434.ics]");
    }

    private static Stream<EventAmqpMessageSample> eventAmqpMessageSamplesFromExistingConsumerTests() {
        // Only use AMQP payload literals already present in focused consumer/message tests.
        return Stream.of(
            EventAmqpMessageSample.amqpMessage(calendarEventFromIndexerConsumer(EVENT_OWNER))
                .exchangeName("calendar:event:created")
                .expectedMessage("Calendar event created")
                .expectedPath("/calendars/" + EVENT_OWNER + "/" + EVENT_OWNER + "/a0b5a363-e56f-490b-bfa7-89111b0fdd9b.ics")
                .expectedOwner(EVENT_OWNER)
                .build(),
            EventAmqpMessageSample.amqpMessage(CALENDAR_EVENT_NOTIFICATION)
                .exchangeName("calendar:event:updated")
                .expectedMessage("Calendar event updated")
                .expectedPath("/calendars/base1/calendar1/3423434.ics")
                .expectedOwner("base1")
                .build(),
            EventAmqpMessageSample.amqpMessage(CALENDAR_EVENT_NOTIFICATION)
                .exchangeName("calendar:event:deleted")
                .expectedMessage("Calendar event deleted")
                .expectedPath("/calendars/base1/calendar1/3423434.ics")
                .expectedOwner("base1")
                .build(),
            EventAmqpMessageSample.amqpMessage(CALENDAR_EVENT_NOTIFICATION)
                .exchangeName("calendar:event:request")
                .expectedMessage("Calendar event (iTIP request)")
                .expectedPath("/calendars/base1/calendar1/3423434.ics")
                .expectedOwner("base1")
                .build(),
            EventAmqpMessageSample.amqpMessage(CALENDAR_EVENT_NOTIFICATION)
                .exchangeName("calendar:event:cancel")
                .expectedMessage("Calendar event (iTIP cancel)")
                .expectedPath("/calendars/base1/calendar1/3423434.ics")
                .expectedOwner("base1")
                .build(),
            EventAmqpMessageSample.amqpMessage(CALENDAR_EVENT_NOTIFICATION)
                .exchangeName("calendar:event:reply")
                .expectedMessage("Calendar event (iTIP reply)")
                .expectedPath("/calendars/base1/calendar1/3423434.ics")
                .expectedOwner("base1")
                .build());
    }

    private record EventAmqpMessageSample(String exchangeName, String amqpMessage, String expectedMessage, String expectedPath, String expectedOwner) {
        private static Builder amqpMessage(String amqpMessage) {
            return new Builder(amqpMessage);
        }

        private static class Builder {
            private final String amqpMessage;
            private String exchangeName;
            private String expectedMessage;
            private String expectedPath;
            private String expectedOwner;

            private Builder(String amqpMessage) {
                this.amqpMessage = amqpMessage;
            }

            private Builder exchangeName(String exchangeName) {
                this.exchangeName = exchangeName;
                return this;
            }

            private Builder expectedMessage(String expectedMessage) {
                this.expectedMessage = expectedMessage;
                return this;
            }

            private Builder expectedPath(String expectedPath) {
                this.expectedPath = expectedPath;
                return this;
            }

            private Builder expectedOwner(String expectedOwner) {
                this.expectedOwner = expectedOwner;
                return this;
            }

            private EventAmqpMessageSample build() {
                return new EventAmqpMessageSample(exchangeName, amqpMessage, expectedMessage, expectedPath, expectedOwner);
            }
        }
    }

    private static String calendarEventFromIndexerConsumer(String userId) {
        return """
            {"eventPath":"/calendars/{userId}/{userId}/a0b5a363-e56f-490b-bfa7-89111b0fdd9b.ics","event":["vcalendar",[["version",{},"text","2.0"],["prodid",{},"text","-//Sabre//Sabre VObject 4.2.2//EN"]],[["vtimezone",[["tzid",{},"text","Asia/Jakarta"]],[["standard",[["tzoffsetfrom",{},"utc-offset","+07:00"],["tzoffsetto",{},"utc-offset","+07:00"],["tzname",{},"text","WIB"],["dtstart",{},"date-time","1970-01-01T00:00:00"]],[]]]],["vevent",[["uid",{},"text","a0b5a363-e56f-490b-bfa7-89111b0fdd9b"],["transp",{},"text","OPAQUE"],["dtstart",{"tzid":"Asia/Saigon"},"date-time","2025-04-19T11:00:00"],["dtend",{"tzid":"Asia/Saigon"},"date-time","2025-04-19T11:30:00"],["class",{},"text","PUBLIC"],["summary",{},"text","Title 1"],["description",{},"text","note tung"],["organizer",{"cn":"John1 Doe1"},"cal-address","mailto:user1@open-paas.org"],["attendee",{"partstat":"NEEDS-ACTION","rsvp":"TRUE","role":"REQ-PARTICIPANT","cutype":"INDIVIDUAL","cn":"John2 Doe2","schedule-status":"1.1"},"cal-address","mailto:user2@open-paas.org"],["attendee",{"partstat":"ACCEPTED","rsvp":"FALSE","role":"CHAIR","cutype":"INDIVIDUAL"},"cal-address","mailto:user1@open-paas.org"],["dtstamp",{},"date-time","2025-04-18T07:47:48Z"]],[]]]],"import":false,"etag":"\\"f066260d3a4fca51ae0de0618e9555cc\\""}"""
            .replace("{userId}", userId);
    }
}
