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

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
import net.fortuna.ical4j.model.Component;
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

    private static final Comparator<VEvent> MASTER_FIRST = Comparator.comparing(vEvent -> EventParseUtils.getRecurrenceId(vEvent).isPresent());

    private final Sender sender;
    private final Clock clock;

    @Inject
    public EventEmailNotificationPublisher(ReactorRabbitMQChannelPool channelPool, Clock clock) {
        this.sender = channelPool.getSender();
        this.clock = clock;
    }

    public Mono<Void> publishReply(Calendar updatedCalendar, MailAddress attendee, MailAddress organizer, String eventPath) {
        return Mono.fromCallable(() -> replyCalendar(updatedCalendar, attendee))
            .filter(replyCalendar -> !CalendarEventUtils.vEventExpired(EventParseUtils.getFirstEvent(replyCalendar), clock))
            .flatMap(replyCalendar -> Mono.fromCallable(() -> OBJECT_MAPPER.writeValueAsBytes(new NotificationEmailDTO(
                attendee.asString(),
                organizer.asString(),
                ImmutableMethod.REPLY.getValue(),
                replyCalendar.toString(),
                eventPath))))
            .flatMap(payload -> sender.send(Mono.just(new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY, payload))))
            .then();
    }

    /**
     * The answer of the attendee is for the whole series: the REPLY holds every VEVENT the attendee is invited to,
     * the master one first, so that it conveys the answer for the series and not for a single occurrence.
     */
    private Calendar replyCalendar(Calendar updatedCalendar, MailAddress attendee) {
        List<VEvent> replyEvents = updatedCalendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .sorted(MASTER_FIRST)
            .map(vEvent -> replyEvent(vEvent, attendee))
            .flatMap(Optional::stream)
            .toList();

        if (replyEvents.isEmpty()) {
            throw new IllegalArgumentException("Attendee " + attendee.asString() + " is not part of the event");
        }

        return CalendarUtil.withMethod(CalendarUtil.withVEvents(updatedCalendar, replyEvents), ImmutableMethod.REPLY);
    }

    private Optional<VEvent> replyEvent(VEvent vEvent, MailAddress attendee) {
        VEvent replyEvent = vEvent.copy();
        replyEvent.getAttendees().stream()
            .filter(candidate -> !matches(candidate, attendee))
            .toList()
            .forEach(replyEvent::remove);

        return Optional.of(replyEvent)
            .filter(event -> !event.getAttendees().isEmpty());
    }

    private boolean matches(Attendee attendee, MailAddress mailAddress) {
        return Strings.CI.equals(attendee.getCalAddress().toASCIIString(), "mailto:" + mailAddress.asString());
    }
}
