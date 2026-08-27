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

package com.linagora.calendar.saas.contact;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

public class CommonContactPublisher implements Startable {
    private static final AMQP.BasicProperties PERSISTENT_JSON = new AMQP.BasicProperties.Builder()
        .deliveryMode(2)
        .contentType("application/json")
        .build();

    private final Sender sender;
    private final String exchange;

    @Inject
    public CommonContactPublisher(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool,
                                  CommonContactsConfiguration configuration) {
        this.sender = channelPool.getSender();
        this.exchange = configuration.outboundExchange();
    }

    public void init() {
        sender.declareExchange(ExchangeSpecification.exchange(exchange)
                .durable(DURABLE)
                .type(BuiltinExchangeType.FANOUT.getType()))
            .block();
    }

    public Mono<Void> publish(CommonContactOutboundEvent event) {
        return Mono.fromCallable(event::serialize)
            .map(body -> new OutboundMessage(exchange, EMPTY_ROUTING_KEY, PERSISTENT_JSON, body))
            .flatMap(message -> sender.send(Mono.just(message)));
    }

}
