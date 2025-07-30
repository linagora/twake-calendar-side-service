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

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.eventsearch.EventUid;

public interface AlarmEventDAOContract {
    AlarmEventDAO getDAO();

    @Test
    default void shouldCreateNewAlarmEvent() throws AddressException {
        AlarmEvent event = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            false,
            new MailAddress("recipient@abc.com"),
            "ics");
        getDAO().create(event).block();

        AlarmEvent found = getDAO().find(new EventUid("1")).block();

        assertThat(found).isEqualTo(event);
    }

    @Test
    default void shouldUpdateAlarmEvent() throws AddressException {
        AlarmEvent event = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            false,
            new MailAddress("recipient@abc.com"),
            "ics");
        AlarmEvent updated = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            true,
            new MailAddress("newRecipient@abc.com"),
            "newIcs");
        getDAO().create(event).block();
        getDAO().update(updated).block();

        AlarmEvent found = getDAO().find(new EventUid("1")).block();

        assertThat(found).isEqualTo(updated);
    }

    @Test
    default void shouldDeleteAlarmEvent() throws AddressException {
        AlarmEvent event = new AlarmEvent(
            new EventUid("1"),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            Instant.now().truncatedTo(ChronoUnit.MILLIS),
            false,
            new MailAddress("recipient@abc.com"),
            "ics");
        getDAO().create(event).block();
        getDAO().delete(new EventUid("1")).block();

        AlarmEvent found = getDAO().find(new EventUid("1")).block();

        assertThat(found).isNull();
    }

    @Test
    default void shouldGetAlarmEventsByTime() throws AddressException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AlarmEvent e1 = new AlarmEvent(
            new EventUid("1"),
            now.minusSeconds(60),
            now.plusSeconds(1000),
            false,
            new MailAddress("r1@abc.com"),
            "ics1");
        AlarmEvent e2 = new AlarmEvent(
            new EventUid("2"),
            now,
            now.plusSeconds(1000),
            true,
            new MailAddress("r2@abc.com"),
            "ics2");
        AlarmEvent e3 = new AlarmEvent(
            new EventUid("3"),
            now.plusSeconds(60),
            now.plusSeconds(1000),
            false,
            new MailAddress("r3@abc.com"),
            "ics2");
        AlarmEvent e4 = new AlarmEvent(
            new EventUid("3"),
            now.minusSeconds(60),
            now.minusSeconds(10),
            false,
            new MailAddress("r3@abc.com"),
            "ics2");
        getDAO().create(e1).block();
        getDAO().create(e2).block();
        getDAO().create(e3).block();
        getDAO().create(e4).block();

        List<AlarmEvent> events = getDAO().findBetweenAlarmTimeAndStartTime(now).collectList().block();

        assertThat(events).containsExactlyInAnyOrder(e1, e2);
    }
}
