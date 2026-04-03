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

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.linagora.calendar.amqp.CalendarDiffCalculator.EventDiff;
import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Method;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class ItipEmailNotificationPublisher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final boolean MARK_AS_NEW_EVENT = true;

    private final Sender sender;
    private final CalendarDiffCalculator calendarDiffCalculator;

    public ItipEmailNotificationPublisher(Sender sender) {
        this.sender = sender;
        this.calendarDiffCalculator = new CalendarDiffCalculator();
    }

    Mono<Void> send(ItipLocalDeliveryDTO localDelivery,
                    URI eventPath,
                    Optional<Calendar> oldEventCalendar) {
        return sender.send(toOutboundMessages(localDelivery, eventPath, oldEventCalendar)).then();
    }

    private Flux<OutboundMessage> toOutboundMessages(ItipLocalDeliveryDTO localDelivery,
                                                     URI eventPath,
                                                     Optional<Calendar> oldEventCalendar) {
        return Mono.fromCallable(() -> buildNotificationMessages(localDelivery, eventPath, oldEventCalendar))
            .flatMapMany(Flux::fromIterable)
            .flatMap(payload -> toOutboundMessage(payload, localDelivery.uid()));
    }

    private List<NotificationEmailDTO> buildNotificationMessages(ItipLocalDeliveryDTO localDelivery,
                                                                 URI eventPath,
                                                                 Optional<Calendar> oldEventCalendar) {
        String normalizedMethod = StringUtils.upperCase(localDelivery.method());

        return switch (normalizedMethod) {
            case Method.VALUE_COUNTER -> List.of(NotificationEmailDTO.builder(localDelivery)
                .withEvent(localDelivery.message())
                .withEventPath(eventPath)
                .withOldEvent(localDelivery.oldMessage())
                .build());
            case Method.VALUE_REPLY -> {
                Calendar newCalendar = CalendarUtil.parseIcs(localDelivery.message());
                yield buildReplyNotifications(localDelivery, eventPath, newCalendar);
            }
            default -> {
                Calendar newCalendar = CalendarUtil.parseIcs(localDelivery.message());
                yield oldEventCalendar.map(oldCalendar -> buildDiffBasedNotifications(localDelivery, eventPath, newCalendar, oldCalendar))
                    .orElseGet(() -> buildOccurrenceBasedNotifications(localDelivery, eventPath, newCalendar));
            }
        };
    }

    private List<NotificationEmailDTO> buildReplyNotifications(ItipLocalDeliveryDTO localDelivery,
                                                               URI eventPath,
                                                               Calendar newCalendar) {
        return buildNotificationsByOccurrence(localDelivery, eventPath, newCalendar, !MARK_AS_NEW_EVENT);
    }

    private List<NotificationEmailDTO> buildDiffBasedNotifications(ItipLocalDeliveryDTO localDelivery,
                                                                   URI eventPath,
                                                                   Calendar newCalendar,
                                                                   Calendar oldCalendar) {
        NotificationEmailDTO.Builder payloadBuilderTemplate = NotificationEmailDTO.builder(localDelivery)
            .withEventPath(eventPath);
        return calendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, oldCalendar)
            .stream()
            .map(diff -> buildChangedOccurrenceNotification(payloadBuilderTemplate, newCalendar, diff))
            .toList();
    }

    private List<NotificationEmailDTO> buildOccurrenceBasedNotifications(ItipLocalDeliveryDTO localDelivery,
                                                                         URI eventPath,
                                                                         Calendar newCalendar) {
        boolean markAsNewEvent = Method.VALUE_REQUEST.equalsIgnoreCase(localDelivery.method());
        return buildNotificationsByOccurrence(localDelivery, eventPath, newCalendar, markAsNewEvent);
    }

    private List<NotificationEmailDTO> buildNotificationsByOccurrence(ItipLocalDeliveryDTO localDelivery,
                                                                      URI eventPath,
                                                                      Calendar newCalendar,
                                                                      boolean markAsNewEvent) {
        NotificationEmailDTO.Builder payloadBuilderTemplate = NotificationEmailDTO.builder(localDelivery)
            .withEventPath(eventPath)
            .isNewEvent(markAsNewEvent);

        List<VEvent> vevents = extractVEvents(newCalendar);
        if (vevents.size() == 1) {
            return List.of(payloadBuilderTemplate.copy()
                .withEvent(localDelivery.message())
                .build());
        }

        return vevents.stream()
            .map(vevent -> payloadBuilderTemplate.copy()
                .withEvent(CalendarUtil.withSingleVEvent(newCalendar, vevent).toString())
                .build())
            .toList();
    }

    private NotificationEmailDTO buildChangedOccurrenceNotification(NotificationEmailDTO.Builder payloadBuilderTemplate,
                                                                    Calendar newCalendar,
                                                                    EventDiff diff) {
        NotificationEmailDTO.Builder payloadBuilder = payloadBuilderTemplate.copy()
            .withEvent(CalendarUtil.withSingleVEvent(newCalendar, diff.vevent()).toString())
            .isNewEvent(diff.isNewEvent());
        Optional<ObjectNode> serializedChanges = diff.serializeChanges();
        serializedChanges.ifPresent(payloadBuilder::withChanges);
        return payloadBuilder.build();
    }

    private Mono<OutboundMessage> toOutboundMessage(NotificationEmailDTO payload, String uid) {
        return Mono.fromCallable(() -> new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY,
                OBJECT_MAPPER.writeValueAsBytes(payload.serialize())))
            .onErrorMap(error -> new RuntimeException(
                "Failed to serialize email notification payload for uid " + uid, error));
    }

    private static List<VEvent> extractVEvents(Calendar calendar) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .toList();
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record NotificationEmailDTO(@JsonProperty("senderEmail") String senderEmail,
                                @JsonProperty("recipientEmail") String recipientEmail,
                                @JsonProperty("method") String method,
                                @JsonProperty("event") String event,
                                @JsonProperty("notify") boolean shouldNotify,
                                @JsonProperty("calendarURI") String calendarURI,
                                @JsonProperty("eventPath") Optional<String> eventPath,
                                @JsonProperty("oldEvent") Optional<String> oldEvent,
                                @JsonProperty("isNewEvent")
                                @JsonInclude(JsonInclude.Include.NON_DEFAULT)
                                boolean isNewEvent,
                                @JsonProperty("changes") Optional<ObjectNode> changes) {

        private static final ObjectMapper SERIALIZER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .setSerializationInclusion(JsonInclude.Include.NON_ABSENT);

        static Builder builder(ItipLocalDeliveryDTO dto) {
            return new Builder(
                dto.strippedSender(),
                dto.strippedRecipient(),
                dto.method(),
                true,
                dto.calendarId(),
                dto.message(),
                Optional.empty(),
                Optional.empty(),
                !MARK_AS_NEW_EVENT,
                Optional.empty());
        }

        ObjectNode serialize() {
            return SERIALIZER.valueToTree(this);
        }

        static final class Builder {
            private final String senderEmail;
            private final String recipientEmail;
            private final String method;
            private final boolean shouldNotify;
            private final String calendarURI;
            private String event;
            private boolean isNewEvent;
            private Optional<String> eventPath;
            private Optional<String> oldEvent;
            private Optional<ObjectNode> changes;

            private Builder(String senderEmail, String recipientEmail, String method,
                            boolean shouldNotify, String calendarURI, String event, Optional<String> eventPath,
                            Optional<String> oldEvent, boolean isNewEvent, Optional<ObjectNode> changes) {
                this.senderEmail = senderEmail;
                this.recipientEmail = recipientEmail;
                this.method = method;
                this.shouldNotify = shouldNotify;
                this.calendarURI = calendarURI;
                this.event = event;
                this.eventPath = eventPath;
                this.oldEvent = oldEvent;
                this.isNewEvent = isNewEvent;
                this.changes = changes;
            }

            Builder withEvent(String eventIcs) {
                event = eventIcs;
                return this;
            }

            Builder withEventPath(URI eventPathValue) {
                eventPath = Optional.ofNullable(eventPathValue)
                    .map(URI::getPath);
                return this;
            }

            Builder withOldEvent(Optional<String> oldEventIcs) {
                oldEvent = oldEventIcs;
                return this;
            }

            Builder isNewEvent(boolean isNewEventValue) {
                isNewEvent = isNewEventValue;
                return this;
            }

            Builder withChanges(ObjectNode changesNode) {
                changes = Optional.of(changesNode.deepCopy());
                return this;
            }

            Builder copy() {
                return new Builder(senderEmail, recipientEmail, method, shouldNotify, calendarURI, event, eventPath,
                    oldEvent, isNewEvent, changes.map(ObjectNode::deepCopy));
            }

            NotificationEmailDTO build() {
                return new NotificationEmailDTO(
                    senderEmail, recipientEmail, method, event, shouldNotify, calendarURI, eventPath, oldEvent,
                    isNewEvent, changes);
            }
        }
    }
}
