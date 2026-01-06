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
import java.util.Optional;

import org.apache.james.core.MailAddress;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.storage.event.AlarmAction;
import com.linagora.calendar.storage.event.AlarmInstantFactory.AlarmInstant;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.property.DateProperty;

public record AlarmEvent(EventUid eventUid,
                         Instant alarmTime,
                         Instant eventStartTime,
                         boolean recurring,
                         Optional<String> recurrenceId,
                         MailAddress recipient,
                         String ics,
                         String eventPath,
                         AlarmAction action) {

    public String toShortString() {
        return MoreObjects.toStringHelper(this)
            .add("eventUid", eventUid.value())
            .add("recipient", recipient.asString())
            .add("alarmTime", alarmTime.getEpochSecond())
            .toString();
    }

    public AlarmEvent withNextOccurrence(AlarmInstant instant) {
        return new AlarmEvent(
            this.eventUid,
            instant.alarmTime(),
            instant.eventStartTime(),
            this.recurring,
            instant.recurrenceId().map(DateProperty::getValue),
            this.recipient,
            this.ics,
            this.eventPath,
            instant.action());
    }
}
