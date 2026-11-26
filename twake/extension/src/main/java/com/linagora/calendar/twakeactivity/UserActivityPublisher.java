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

package com.linagora.calendar.twakeactivity;

import static com.linagora.tmail.saas.rabbitmq.TWPConstants.TWP_INJECTION_KEY;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;

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

// Publishes on the Twake Workplace broker. The exchange is the one TwakeSpace publishes its activity on by default:
// both declare it the same way.
public class UserActivityPublisher implements Startable {
    private static final int PERSISTENT = 2;

    private final Sender sender;
    private final String exchange;

    @Inject
    public UserActivityPublisher(@Named(TWP_INJECTION_KEY) ReactorRabbitMQChannelPool channelPool, TwakeActivityConfiguration configuration) {
        this.sender = channelPool.getSender();
        this.exchange = configuration.exchange();
    }

    public void init() {
        sender.declareExchange(ExchangeSpecification.exchange(exchange)
                .durable(DURABLE)
                .type(BuiltinExchangeType.TOPIC.getType()))
            .block();
    }

    // Confirmed before returning: the sabre message is only acknowledged once its activity is queued.
    public Mono<Void> publish(UserActivity activity) {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
            .contentType(UserActivity.CONTENT_TYPE)
            .deliveryMode(PERSISTENT)
            .messageId(activity.id())
            .build();
        String routingKey = activity.type().routingKey();
        return sender.sendWithPublishConfirms(Mono.just(new OutboundMessage(exchange, routingKey, properties, activity.serialize())))
            .next()
            .flatMap(result -> {
                if (!result.isAck()) {
                    return Mono.error(new IllegalStateException("RabbitMQ refused " + routingKey + " " + activity.id()));
                }
                return Mono.<Void>empty();
            });
    }
}
