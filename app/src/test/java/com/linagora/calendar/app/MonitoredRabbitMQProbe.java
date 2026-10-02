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

package com.linagora.calendar.app;

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.MonitoredDeadLetterQueue;
import org.apache.james.backends.rabbitmq.MonitoredRabbitMQConsumers;
import org.apache.james.utils.GuiceProbe;

public class MonitoredRabbitMQProbe implements GuiceProbe {
    private final Set<MonitoredRabbitMQConsumers> consumers;
    private final Set<MonitoredDeadLetterQueue> deadLetterQueues;

    @Inject
    public MonitoredRabbitMQProbe(Set<MonitoredRabbitMQConsumers> consumers, Set<MonitoredDeadLetterQueue> deadLetterQueues) {
        this.consumers = consumers;
        this.deadLetterQueues = deadLetterQueues;
    }

    public List<String> consumedQueues() {
        return consumers.stream()
            .flatMap(monitoredConsumers -> monitoredConsumers.queues().stream())
            .toList();
    }

    public List<String> deadLetterQueues() {
        return deadLetterQueues.stream()
            .map(MonitoredDeadLetterQueue::queue)
            .toList();
    }
}