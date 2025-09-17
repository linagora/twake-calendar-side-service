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

import java.util.Arrays;
import java.util.List;

import com.google.common.collect.ImmutableList;

public class CalendarQueueUtil {
    public static List<String> getAllQueueNames() {
        return ImmutableList.<String>builder()
            .addAll(Arrays.stream(EventIndexerConsumer.Queue.values())
                .map(EventIndexerConsumer.Queue::queueName)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventAlarmConsumer.Queue.values())
                .map(EventAlarmConsumer.Queue::queueName)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventResourceConsumer.Queue.values())
                .map(EventResourceConsumer.Queue::queueName)
                .collect(ImmutableList.toImmutableList()))
            .add(EventEmailConsumer.QUEUE_NAME)
            .build();
    }

    public static List<String> getAllDeadLetterQueueNames() {
        return ImmutableList.<String>builder()
            .addAll(Arrays.stream(EventIndexerConsumer.Queue.values())
                .map(EventIndexerConsumer.Queue::deadLetter)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventAlarmConsumer.Queue.values())
                .map(EventAlarmConsumer.Queue::deadLetter)
                .collect(ImmutableList.toImmutableList()))
            .addAll(Arrays.stream(EventResourceConsumer.Queue.values())
                .map(EventResourceConsumer.Queue::deadLetter)
                .collect(ImmutableList.toImmutableList()))
            .add(EventEmailConsumer.DEAD_LETTER_QUEUE)
            .build();
    }
}
