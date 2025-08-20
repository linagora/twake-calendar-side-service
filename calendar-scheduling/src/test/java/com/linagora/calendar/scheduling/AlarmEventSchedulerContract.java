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

package com.linagora.calendar.scheduling;

import static com.linagora.calendar.scheduling.AlarmTriggerServiceTest.awaitAtMost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.eventsearch.EventUid;

import io.restassured.path.json.JsonPath;

public interface AlarmEventSchedulerContract {
    boolean NO_RECURRING = false;

    AlarmEventScheduler scheduler();

    UpdatableTickingClock clock();

    JsonPath getSmtpMailbox();

    AlarmEventDAO alarmEventDAO();

    @Test
    default void shouldSendAlarmEmailWhenAvailableAlarmIsTriggered() {
        scheduler().start();
        Instant now = clock().instant();
        AlarmEvent event = new AlarmEvent(
            new EventUid("event-uid-1"),
            now.minus(10, ChronoUnit.MINUTES),
            now.plus(10, ChronoUnit.MINUTES),
            NO_RECURRING,
            Optional.empty(),
            Throwing.supplier(() -> new MailAddress("attendee@abc.com")).get(),
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-uid-1
                DTSTART:20250801T100000Z
                DTEND:20250801T110000Z
                SUMMARY:Alarm Test Event
                LOCATION:Test Room
                DESCRIPTION:This is a test alarm event.
                ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
                ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
                END:VEVENT
                END:VCALENDAR
                """);

        alarmEventDAO().create(event).block();

        // Wait for the mail to be received via mock SMTP
        awaitAtMost.atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(getSmtpMailbox().getList("")).hasSize(1));
        JsonPath smtpMailsResponse = getSmtpMailbox();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo("attendee@abc.com");
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Notification: Alarm Test Event")
                .contains("Content-Type: multipart/mixed;")
                .containsIgnoringNewLines("""
                    Content-Transfer-Encoding: base64
                    Content-Type: text/html; charset=UTF-8
                    """);
        }));
    }

    @Test
    default void shouldNotSendAlarmEmailWhenAlarmTimeGreaterThanNow() throws InterruptedException {
        scheduler().start();
        Instant now = clock().instant();
        AlarmEvent event = new AlarmEvent(
            new EventUid("event-uid-1"),
            now.plus(10, ChronoUnit.MINUTES),
            now.plus(20, ChronoUnit.MINUTES),
            NO_RECURRING,
            Optional.empty(),
            Throwing.supplier(() -> new MailAddress("attendee@abc.com")).get(),
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-uid-1
                DTSTART:20250801T100000Z
                DTEND:20250801T110000Z
                SUMMARY:Alarm Test Event
                LOCATION:Test Room
                DESCRIPTION:This is a test alarm event.
                ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
                ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
                END:VEVENT
                END:VCALENDAR
                """);

        alarmEventDAO().create(event).block();

        Thread.sleep(1000);
        assertThat(getSmtpMailbox().getList("")).hasSize(0);
    }

    @Test
    default void shouldNotSendAlarmEmailWhenEventStartTimeLessThanNow() throws InterruptedException {
        scheduler().start();
        Instant now = clock().instant();
        AlarmEvent event = new AlarmEvent(
            new EventUid("event-uid-1"),
            now.minus(20, ChronoUnit.MINUTES),
            now.minus(10, ChronoUnit.MINUTES),
            NO_RECURRING,
            Optional.empty(),
            Throwing.supplier(() -> new MailAddress("attendee@abc.com")).get(),
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:event-uid-1
                DTSTART:20250801T100000Z
                DTEND:20250801T110000Z
                SUMMARY:Alarm Test Event
                LOCATION:Test Room
                DESCRIPTION:This is a test alarm event.
                ORGANIZER;CN=Test Organizer:mailto:organizer@abc.com
                ATTENDEE;CN=Test Attendee:mailto:attendee@abc.com
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:organizer@abc.com
                END:VEVENT
                END:VCALENDAR
                """);
        alarmEventDAO().create(event).block();

        Thread.sleep(1000);
        assertThat(getSmtpMailbox().getList("")).hasSize(0);
    }

}
