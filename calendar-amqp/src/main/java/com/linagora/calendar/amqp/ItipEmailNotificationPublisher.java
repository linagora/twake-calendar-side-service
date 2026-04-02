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

import static com.linagora.calendar.storage.event.EventParseUtils.createInstanceVEvent;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.Temporal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.Sets;
import com.linagora.calendar.amqp.CalendarDiffCalculator.EventDiff;
import com.linagora.calendar.amqp.ItipEmailNotificationPublisher.NotificationEmailDTO.Builder;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.Status;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

class ItipEmailNotificationPublisher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final boolean MARK_AS_NEW_EVENT = true;

    private final Sender sender;
    private final CalendarDiffCalculator calendarDiffCalculator;
    private final NotificationStrategy singleEventNotificationStrategy;
    private final NotificationStrategy recurringEventNotificationStrategy;

    public ItipEmailNotificationPublisher(Sender sender) {
        this.sender = sender;
        this.calendarDiffCalculator = new CalendarDiffCalculator();
        this.singleEventNotificationStrategy = new SingleEventNotificationStrategy();
        this.recurringEventNotificationStrategy = new RecurringEventNotificationStrategy();
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
        Calendar newCalendar = CalendarUtil.parseIcs(localDelivery.message());
        return notificationStrategy(newCalendar)
            .handle(localDelivery, eventPath, newCalendar, oldEventCalendar);
    }

    private NotificationStrategy notificationStrategy(Calendar newCalendar) {
        if (EventParseUtils.isRecurringEvent(newCalendar)) {
            return recurringEventNotificationStrategy;
        }
        return singleEventNotificationStrategy;
    }

    private interface NotificationStrategy {

        List<NotificationEmailDTO> handle(ItipLocalDeliveryDTO localDelivery,
                                          URI eventPath,
                                          Calendar newCalendar,
                                          Optional<Calendar> oldCalendar);

        default List<NotificationEmailDTO> handleCounter(ItipLocalDeliveryDTO localDelivery, URI eventPath) {
            return List.of(NotificationEmailDTO.builder(localDelivery)
                .withEvent(localDelivery.message())
                .withEventPath(eventPath)
                .withOldEvent(localDelivery.oldMessage())
                .build());
        }
    }

    private class SingleEventNotificationStrategy implements NotificationStrategy {
        @Override
        public List<NotificationEmailDTO> handle(ItipLocalDeliveryDTO localDelivery,
                                                 URI eventPath,
                                                 Calendar newCalendar,
                                                 Optional<Calendar> oldCalendar) {
            NotificationEmailDTO.Builder builderTemplate = NotificationEmailDTO.builder(localDelivery)
                .withEventPath(eventPath);

            return switch (StringUtils.upperCase(localDelivery.method())) {
                case Method.VALUE_COUNTER -> List.of(builderTemplate.withOldEvent(localDelivery.oldMessage()).build());
                case Method.VALUE_REPLY -> List.of(builderTemplate.build());
                default -> oldCalendar
                    .map(old -> buildEventDiffNotifications(localDelivery, newCalendar, old, builderTemplate))
                    .orElseGet(() -> {
                        boolean markAsNewEvent = Method.VALUE_REQUEST.equalsIgnoreCase(localDelivery.method());
                        return List.of(builderTemplate.isNewEvent(markAsNewEvent).build());
                    });
            };
        }

        private List<NotificationEmailDTO> buildEventDiffNotifications(ItipLocalDeliveryDTO localDelivery, Calendar newCalendar, Calendar old, Builder builderTemplate) {
            return calendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, old)
                .stream()
                .map(diff -> builderTemplate
                    .isNewEvent(diff.isNewEvent())
                    .withChanges(diff.serializeChanges()).build())
                .toList();
        }
    }

    private class RecurringEventNotificationStrategy implements NotificationStrategy {
        @Override
        public List<NotificationEmailDTO> handle(ItipLocalDeliveryDTO localDelivery,
                                                 URI eventPath,
                                                 Calendar newCalendar,
                                                 Optional<Calendar> oldCalendar) {
            return switch (StringUtils.upperCase(localDelivery.method())) {
                case Method.VALUE_COUNTER -> handleCounter(localDelivery, eventPath);
                case Method.VALUE_REPLY ->
                    buildNotificationsByOccurrence(localDelivery, eventPath, newCalendar, !MARK_AS_NEW_EVENT);
                case Method.VALUE_REQUEST -> oldCalendar
                    .map(old -> buildDiffWithCancellations(localDelivery, eventPath, newCalendar, old))
                    .orElseGet(() -> buildNotificationsByOccurrence(localDelivery, eventPath, newCalendar, MARK_AS_NEW_EVENT));
                default -> oldCalendar
                    .map(old -> buildDiffBasedNotifications(localDelivery, eventPath, newCalendar, old))
                    .orElseGet(() -> buildNotificationsByOccurrence(localDelivery, eventPath, newCalendar, !MARK_AS_NEW_EVENT));
            };
        }

        private List<NotificationEmailDTO> buildDiffWithCancellations(ItipLocalDeliveryDTO localDelivery,
                                                                      URI eventPath,
                                                                      Calendar newCalendar,
                                                                      Calendar oldCalendar) {
            Map<Temporal, Calendar> cancelCalendarsByRecurrence = computeCancelledCalendars(newCalendar, oldCalendar);

            List<NotificationEmailDTO> requestNotifications = calendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, oldCalendar)
                .stream()
                .filter(diff -> shouldSendRequest(diff, cancelCalendarsByRecurrence.keySet()))
                .map(diff -> buildChangedOccurrenceNotification(NotificationEmailDTO.builder(localDelivery).withEventPath(eventPath),
                    newCalendar, diff))
                .toList();

            List<NotificationEmailDTO> cancelNotifications = List.copyOf(cancelCalendarsByRecurrence.values()).stream()
                .map(cancelCalendar -> NotificationEmailDTO.builder(localDelivery)
                    .withEventPath(eventPath)
                    .withMethod(Method.VALUE_CANCEL)
                    .withEvent(cancelCalendar.toString())
                    .build())
                .toList();

            return Stream.concat(cancelNotifications.stream(), requestNotifications.stream())
                .toList();
        }

        private List<NotificationEmailDTO> buildDiffBasedNotifications(ItipLocalDeliveryDTO localDelivery,
                                                                       URI eventPath,
                                                                       Calendar newCalendar,
                                                                       Calendar oldCalendar) {
            return calendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, oldCalendar)
                .stream()
                .map(diff -> buildChangedOccurrenceNotification(
                    NotificationEmailDTO.builder(localDelivery).withEventPath(eventPath), newCalendar, diff))
                .toList();
        }

        private List<NotificationEmailDTO> buildNotificationsByOccurrence(ItipLocalDeliveryDTO localDelivery,
                                                                          URI eventPath,
                                                                          Calendar newCalendar,
                                                                          boolean markAsNewEvent) {
            NotificationEmailDTO.Builder payloadBuilderTemplate = NotificationEmailDTO.builder(localDelivery)
                .withEventPath(eventPath)
                .isNewEvent(markAsNewEvent);

            List<VEvent> vevents = newCalendar.getComponents(Component.VEVENT)
                .stream()
                .map(VEvent.class::cast)
                .toList();

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
            NotificationEmailDTO.Builder payloadBuilder = payloadBuilderTemplate
                .withEvent(CalendarUtil.withSingleVEvent(newCalendar, diff.vevent()).toString())
                .isNewEvent(diff.isNewEvent());
            Optional<ObjectNode> serializedChanges = diff.serializeChanges();
            serializedChanges.ifPresent(payloadBuilder::withChanges);
            return payloadBuilder.build();
        }

        private Map<Temporal, Calendar> computeCancelledCalendars(Calendar newCalendar, Calendar oldCalendar) {
            VEvent previousMaster = EventParseUtils.getMasterRecurrenceEvent(oldCalendar);
            Set<Temporal> previousExDates = indexExDates(previousMaster);
            Set<Temporal> currentExDates = indexExDates(EventParseUtils.getMasterRecurrenceEvent(newCalendar));

            return Sets.difference(currentExDates, previousExDates)
                .stream()
                .collect(LinkedHashMap::new,
                    (map, temporal) ->
                        map.put(temporal, buildCancelCalendar(temporal, previousMaster, newCalendar)),
                    Map::putAll);
        }

        private Calendar buildCancelCalendar(Temporal temporal, VEvent previousMaster, Calendar templateCalendar) {
            VEvent cancelledEvent = createInstanceVEvent(previousMaster, temporal);
            cancelledEvent.removeAll(Property.STATUS);
            cancelledEvent.add(new Status(Status.VALUE_CANCELLED));

            Calendar calendar = CalendarUtil.withSingleVEvent(templateCalendar, cancelledEvent);
            calendar.removeAll(Property.METHOD);
            calendar.add(new Method(Method.VALUE_CANCEL));
            return calendar;
        }

        private Set<Temporal> indexExDates(VEvent masterEvent) {
            return masterEvent.getProperties(Property.EXDATE).stream()
                .map(exDate -> (ExDate<?>) exDate)
                .flatMap(exDate -> exDate.getDates().stream().map(Temporal.class::cast))
                .map(temporal -> normalizeExDate(temporal, masterEvent))
                .collect(Collectors.toSet());
        }

        private Temporal normalizeExDate(Temporal temporal, VEvent masterEvent) {
            if (EventParseUtils.isAllDay(masterEvent) && (temporal instanceof LocalDate localDate)) {
                return localDate;
            }
            return EventParseUtils.temporalToZonedDateTime(temporal)
                .orElseThrow(() -> new IllegalStateException("Cannot convert EXDATE: " + temporal));
        }

        private boolean shouldSendRequest(EventDiff diff, Set<Temporal> excludedRecurrenceIds) {
            return recurrenceKey(diff.vevent())
                .map(recurrenceId -> !excludedRecurrenceIds.contains(recurrenceId))
                .orElse(true);
        }

        private Optional<Temporal> recurrenceKey(VEvent vevent) {
            return Optional.ofNullable(vevent.getRecurrenceId())
                .map(DateProperty::getDate)
                .map(temporal -> {
                    if (temporal instanceof LocalDate localDate) {
                        return localDate;
                    }
                    return EventParseUtils.temporalToZonedDateTime(temporal)
                        .map(ChronoZonedDateTime::toInstant)
                        .orElseThrow(() -> new IllegalStateException("Cannot convert recurrence temporal: " + temporal));
                });
        }
    }

    private Mono<OutboundMessage> toOutboundMessage(NotificationEmailDTO payload, String uid) {
        return Mono.fromCallable(() -> new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY,
                OBJECT_MAPPER.writeValueAsBytes(payload.serialize())))
            .onErrorMap(error -> new RuntimeException(
                "Failed to serialize email notification payload for uid " + uid, error));
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
            private String method;
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

            Builder withMethod(String methodValue) {
                method = methodValue;
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

            Builder withChanges(Optional<ObjectNode> changesNode) {
                changes = changesNode
                    .map(ObjectNode::deepCopy);
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
