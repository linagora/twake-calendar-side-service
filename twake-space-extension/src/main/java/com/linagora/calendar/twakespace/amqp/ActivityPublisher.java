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

package com.linagora.calendar.twakespace.amqp;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.lifecycle.api.Startable;

import com.linagora.calendar.twakespace.TwakeSpaceConfiguration;
import com.linagora.calendar.twakespace.model.ActivityEvent;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

public class ActivityPublisher implements Startable {
    private static final String CONTENT_TYPE = "application/cloudevents+json";
    private static final int PERSISTENT = 2;

    private final Sender sender;
    private final String exchange;

    @Inject
    public ActivityPublisher(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool, TwakeSpaceConfiguration configuration) {
        this.sender = channelPool.getSender();
        this.exchange = configuration.activityExchange();
    }

    public void init() {
        sender.declareExchange(ExchangeSpecification.exchange(exchange)
                .durable(DURABLE)
                .type(BuiltinExchangeType.TOPIC.getType()))
            .block();
    }

    // Confirmed before returning: an event is only acknowledged once its activity is queued, and the
    // provisioned event of a calendar is queued before any activity on it.
    public Mono<Void> publish(ActivityEvent event) {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
            .contentType(CONTENT_TYPE)
            .deliveryMode(PERSISTENT)
            .messageId(event.id())
            .build();
        return sender.sendWithPublishConfirms(Mono.just(new OutboundMessage(exchange, event.type(), properties, event.serialize())))
            .next()
            .flatMap(result -> {
                if (!result.isAck()) {
                    return Mono.error(new IllegalStateException("RabbitMQ refused " + event.type() + " " + event.id()));
                }
                return Mono.<Void>empty();
            });
    }
}
