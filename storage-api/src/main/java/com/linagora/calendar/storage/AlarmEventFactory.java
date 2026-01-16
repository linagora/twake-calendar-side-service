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

import java.util.List;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.event.AlarmAction;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.DateProperty;

public interface AlarmEventFactory {

    List<AlarmEvent> buildAlarmEvent(Username username, List<MailAddress> recipients, Calendar calendarEvent, AlarmInstantFactory.AlarmInstant nextAlarmInstant, String eventPath);

    class Default implements AlarmEventFactory {

        @Override
        public List<AlarmEvent> buildAlarmEvent(Username username,
                                                List<MailAddress> recipients,
                                                Calendar calendarEvent,
                                                AlarmInstantFactory.AlarmInstant nextAlarmInstant,
                                                String eventPath) {
            boolean recurringEvent = EventParseUtils.isRecurringEvent(calendarEvent);
            EventUid eventUid = new EventUid(EventParseUtils.extractEventUid(calendarEvent));
            Optional<String> recurrenceIdValue = nextAlarmInstant.recurrenceId().map(DateProperty::getValue);
            String eventCalendarString = calendarEvent.toString();

            ImmutableList.Builder<MailAddress> recipientsBuilder = ImmutableList.builder();
            if (AlarmAction.DISPLAY == nextAlarmInstant.action()) {
                Throwing.supplier(() -> recipientsBuilder.add(username.asMailAddress())).get();
            } else {
                recipientsBuilder.addAll(recipients);
            }

            return recipientsBuilder.build().stream()
                .map(recipient -> new AlarmEvent(
                    eventUid,
                    nextAlarmInstant.alarmTime(),
                    nextAlarmInstant.eventStartTime(),
                    recurringEvent,
                    recurrenceIdValue,
                    recipient,
                    eventCalendarString,
                    eventPath,
                    nextAlarmInstant.action()))
                .toList();
        }
    }
}
