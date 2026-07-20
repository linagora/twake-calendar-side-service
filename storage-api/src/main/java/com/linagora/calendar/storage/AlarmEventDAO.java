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

import java.time.Instant;

import org.apache.james.core.MailAddress;

import com.linagora.calendar.storage.eventsearch.EventUid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlarmEventDAO {
    Mono<AlarmEvent> find(EventUid eventUid, MailAddress recipient);

    Mono<Void> create(AlarmEvent alarmEvent);

    Mono<Void> update(AlarmEvent alarmEvent);

    Mono<Void> delete(EventUid eventUid, MailAddress recipient);

    /**
     * Deletes every alarm originating from a given calendar object, whatever its recipient.
     * A single calendar object may schedule alarms for recipients that are not attendees of the
     * event (VALARM ATTENDEE delegation), those alarms must be dropped along with the calendar object.
     */
    Mono<Void> deleteByEventPath(EventUid eventUid, String eventPath);

    Flux<AlarmEvent> findAlarmsToTrigger(Instant time); // get all alarmEvent with time >= alarmTime
}
