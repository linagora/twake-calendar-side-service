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

import java.util.List;

/**
 * Names an AMQP topology consumed by {@link RabbitMQConsumerSupport}: the exchange(s) publishing
 * messages, the durable queue bound to them, and the dead-letter queue (also used as the
 * dead-letter exchange name) that the queue forwards rejected messages to.
 */
public record QueueDeclaration(List<String> exchangeNames, String queueName, String deadLetter) {
    public static QueueDeclaration of(String exchangeName, String queueName, String deadLetter) {
        return new QueueDeclaration(List.of(exchangeName), queueName, deadLetter);
    }

    public static QueueDeclaration of(List<String> exchangeNames, String queueName, String deadLetter) {
        return new QueueDeclaration(exchangeNames, queueName, deadLetter);
    }
}
