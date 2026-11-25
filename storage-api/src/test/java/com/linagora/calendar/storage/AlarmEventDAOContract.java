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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.event.AlarmAction;
import com.linagora.calendar.storage.eventsearch.EventUid;

public interface AlarmEventDAOContract {

    boolean NO_RECURRING = false;

    AlarmEventDAO getDAO();

    @Test
    default void shouldCreateNewAlarmEvent() throws AddressException {
        AlarmEvent event = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("recipient@abc.com"),
            "ics",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        getDAO().create(event).block();

        AlarmEvent found = getDAO().find(new EventUid("1"), new MailAddress("recipient@abc.com")).block();

        assertThat(found).isEqualTo(event);
    }

    @Test
    default void shouldUpdateAlarmEvent() throws AddressException {
        AlarmEvent event = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("recipient@abc.com"),
            "ics",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        AlarmEvent updated = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("recipient@abc.com"),
            "newIcs",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.DISPLAY);
        getDAO().create(event).block();
        getDAO().update(updated).block();

        AlarmEvent found = getDAO().find(new EventUid("1"), new MailAddress("recipient@abc.com")).block();

        assertThat(found).isEqualTo(updated);
    }

    @Test
    default void upsertIfReplaceableFromShouldCreateAMissingAlarm() throws AddressException {
        AlarmEvent event = alarmScheduledFrom("/calendars/home/calendar/object.ics", "ics");

        assertThat(getDAO().upsertIfReplaceableFrom(event, new OpenPaaSId("home")).block()).isTrue();
        assertThat(getDAO().find(event.eventUid(), event.recipient()).block()).isEqualTo(event);
    }

    @Test
    default void upsertIfReplaceableFromShouldReplaceAnAlarmOfTheSameCalendarHome() throws AddressException {
        getDAO().create(alarmScheduledFrom("/calendars/home/calendar/object.ics", "ics")).block();
        AlarmEvent updated = alarmScheduledFrom("/calendars/home/other-calendar/object.ics", "newIcs");

        assertThat(getDAO().upsertIfReplaceableFrom(updated, new OpenPaaSId("home")).block()).isTrue();
        assertThat(getDAO().find(updated.eventUid(), updated.recipient()).block()).isEqualTo(updated);
    }

    @Test
    default void upsertIfReplaceableFromShouldNotReplaceAnAlarmOfAnotherCalendarHome() throws AddressException {
        AlarmEvent victimAlarm = alarmScheduledFrom("/calendars/victim/calendar/object.ics", "ics");
        getDAO().create(victimAlarm).block();

        assertThat(getDAO().upsertIfReplaceableFrom(alarmScheduledFrom("/calendars/attacker/calendar/object.ics", "attackerIcs"),
            new OpenPaaSId("attacker")).block()).isFalse();
        assertThat(getDAO().find(victimAlarm.eventUid(), victimAlarm.recipient()).block()).isEqualTo(victimAlarm);
    }

    @Test
    default void upsertIfReplaceableFromShouldNotReplaceAnAlarmOfAHomeItPrefixes() throws AddressException {
        AlarmEvent victimAlarm = alarmScheduledFrom("/calendars/home2/calendar/object.ics", "ics");
        getDAO().create(victimAlarm).block();

        assertThat(getDAO().upsertIfReplaceableFrom(alarmScheduledFrom("/calendars/home/calendar/object.ics", "attackerIcs"),
            new OpenPaaSId("home")).block()).isFalse();
        assertThat(getDAO().find(victimAlarm.eventUid(), victimAlarm.recipient()).block()).isEqualTo(victimAlarm);
    }

    @Test
    default void upsertIfReplaceableFromShouldReplaceAnAlarmWithoutEventPath() throws AddressException {
        getDAO().create(alarmScheduledFrom("", "ics")).block();
        AlarmEvent updated = alarmScheduledFrom("/calendars/home/calendar/object.ics", "newIcs");

        assertThat(getDAO().upsertIfReplaceableFrom(updated, new OpenPaaSId("home")).block()).isTrue();
        assertThat(getDAO().find(updated.eventUid(), updated.recipient()).block()).isEqualTo(updated);
    }

    private static AlarmEvent alarmScheduledFrom(String eventPath, String ics) throws AddressException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new AlarmEvent(new EventUid("1"), now, now, NO_RECURRING, Optional.empty(),
            new MailAddress("recipient@abc.com"), ics, eventPath, AlarmAction.EMAIL);
    }

    @Test
    default void shouldDeleteAlarmEvent() throws AddressException {
        AlarmEvent event = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("recipient@abc.com"),
            "ics",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        getDAO().create(event).block();
        getDAO().delete(new EventUid("1"), new MailAddress("recipient@abc.com")).block();

        AlarmEvent found = getDAO().find(new EventUid("1"), new MailAddress("recipient@abc.com")).block();

        assertThat(found).isNull();
    }

    @Test
    default void deleteByEventPathShouldRemoveEveryRecipientOfThatCalendarObject() throws AddressException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String eventPath = "/calendars/xxx/yyy/zzz.ics";
        AlarmEvent attendeeAlarm = new AlarmEvent(new EventUid("1"), now, now, NO_RECURRING, Optional.empty(),
            new MailAddress("attendee@abc.com"), "ics", eventPath, AlarmAction.EMAIL);
        AlarmEvent delegatedAlarm = new AlarmEvent(new EventUid("1"), now, now, NO_RECURRING, Optional.empty(),
            new MailAddress("delegated@abc.com"), "ics", eventPath, AlarmAction.EMAIL);

        getDAO().create(attendeeAlarm).block();
        getDAO().create(delegatedAlarm).block();
        getDAO().deleteByEventPath(new EventUid("1"), eventPath).block();

        assertThat(List.of(
            Optional.ofNullable(getDAO().find(new EventUid("1"), new MailAddress("attendee@abc.com")).block()),
            Optional.ofNullable(getDAO().find(new EventUid("1"), new MailAddress("delegated@abc.com")).block())))
            .containsExactly(Optional.empty(), Optional.empty());
    }

    @Test
    default void deleteByEventPathShouldNotRemoveAlarmsOfOtherCalendarObjects() throws AddressException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AlarmEvent organizerCopy = new AlarmEvent(new EventUid("1"), now, now, NO_RECURRING, Optional.empty(),
            new MailAddress("recipient@abc.com"), "ics", "/calendars/organizer/organizer/zzz.ics", AlarmAction.EMAIL);
        AlarmEvent attendeeCopy = new AlarmEvent(new EventUid("1"), now, now, NO_RECURRING, Optional.empty(),
            new MailAddress("attendee@abc.com"), "ics", "/calendars/attendee/attendee/zzz.ics", AlarmAction.EMAIL);

        getDAO().create(organizerCopy).block();
        getDAO().create(attendeeCopy).block();
        getDAO().deleteByEventPath(new EventUid("1"), "/calendars/organizer/organizer/zzz.ics").block();

        assertThat(getDAO().find(new EventUid("1"), new MailAddress("attendee@abc.com")).block())
            .isEqualTo(attendeeCopy);
    }

    @Test
    default void shouldGetAlarmEventsByTime() throws AddressException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AlarmEvent e1 = new AlarmEvent(
            new EventUid("1"),
            now.minusSeconds(60),
            now.plusSeconds(1000),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("r1@abc.com"),
            "ics1",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        AlarmEvent e2 = new AlarmEvent(
            new EventUid("2"),
            now,
            now.plusSeconds(1000),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("r2@abc.com"),
            "ics2",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        AlarmEvent e3 = new AlarmEvent(
            new EventUid("3"),
            now.plusSeconds(60),
            now.plusSeconds(1000),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("r3@abc.com"),
            "ics2",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        AlarmEvent e4 = new AlarmEvent(
            new EventUid("4"),
            now.minusSeconds(60),
            now.minusSeconds(10),
            NO_RECURRING,
            Optional.empty(),
            new MailAddress("r4@abc.com"),
            "ics2",
            "/calendars/xxx/yyy/zzz.ics",
            AlarmAction.EMAIL);
        getDAO().create(e1).block();
        getDAO().create(e2).block();
        getDAO().create(e3).block();
        getDAO().create(e4).block();

        List<AlarmEvent> events = getDAO().findAlarmsToTrigger(now).collectList().block();

        assertThat(events).containsExactlyInAnyOrder(e1, e2, e4);
    }
}
