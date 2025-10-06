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

import static com.rabbitmq.client.BuiltinExchangeType.FANOUT;
import static org.apache.james.util.ReactorUtils.LOW_CONCURRENCY;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.Sender;

public class SabreResourceProvisioner implements Startable {
    private final ReactorRabbitMQChannelPool channelPool;

    @Inject
    public SabreResourceProvisioner(ReactorRabbitMQChannelPool channelPool) {
        this.channelPool = channelPool;
    }

    public void provisionSabreExchanges() {
        declareExchanges(channelPool.getSender(),
            "sabre:contact:created",
            "sabre:contact:deleted",
            "sabre:contact:updated",
            "sabre:contact:update",
            "calendar:subscription:created",
            "calendar:subscription:deleted",
            "calendar:subscription:updated",
            "calendar:calendar:created",
            "calendar:calendar:deleted",
            "calendar:calendar:updated",
            "calendar:event:reply",
            "contacts:addressbook:created",
            "contacts:addressbook:deleted",
            "contacts:addressbook:subscription:created",
            "contacts:addressbook:subscription:deleted",
            "contacts:addressbook:subscription:updated",
            "contacts:addressbook:updated")
            .block();
    }

    private Mono<Void> declareExchanges(Sender sender, String... exchanges) {
        return Flux.just(exchanges)
            .flatMap(exchange -> sender.declareExchange(ExchangeSpecification.exchange(exchange)
                .durable(true)
                .type(FANOUT.getType())), LOW_CONCURRENCY)
            .then();
    }
}
