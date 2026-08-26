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

import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.core.MailAddress;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

@Singleton
public class EventEmailNotificationPublisher {

    private record NotificationEmailDTO(@JsonProperty("senderEmail") String senderEmail,
                                        @JsonProperty("recipientEmail") String recipientEmail,
                                        @JsonProperty("method") String method,
                                        @JsonProperty("event") String event,
                                        @JsonProperty("eventPath") String eventPath) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Sender sender;

    @Inject
    public EventEmailNotificationPublisher(ReactorRabbitMQChannelPool channelPool) {
        this.sender = channelPool.getSender();
    }

    public Mono<Void> publishReply(Calendar updatedCalendar, MailAddress attendee, MailAddress organizer, String eventPath) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.writeValueAsBytes(new NotificationEmailDTO(
                attendee.asString(),
                organizer.asString(),
                ImmutableMethod.REPLY.getValue(),
                replyCalendar(updatedCalendar, attendee).toString(),
                eventPath)))
            .flatMap(payload -> sender.send(Mono.just(new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY, payload))))
            .then();
    }

    private Calendar replyCalendar(Calendar updatedCalendar, MailAddress attendee) {
        VEvent replyEvent = EventParseUtils.getFirstEvent(updatedCalendar).copy();
        replyEvent.getAttendees().stream()
            .filter(candidate -> !matches(candidate, attendee))
            .toList()
            .forEach(replyEvent::remove);

        if (replyEvent.getAttendees().isEmpty()) {
            throw new IllegalArgumentException("Attendee " + attendee.asString() + " is not part of the event");
        }

        return CalendarUtil.withMethod(CalendarUtil.withSingleVEvent(updatedCalendar, replyEvent), ImmutableMethod.REPLY);
    }

    private boolean matches(Attendee attendee, MailAddress mailAddress) {
        return Strings.CI.equals(attendee.getCalAddress().toASCIIString(), "mailto:" + mailAddress.asString());
    }
}
