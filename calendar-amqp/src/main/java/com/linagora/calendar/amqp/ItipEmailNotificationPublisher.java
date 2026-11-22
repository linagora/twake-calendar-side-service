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

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.Temporal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.annotations.VisibleForTesting;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(ItipEmailNotificationPublisher.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final boolean MARK_AS_NEW_EVENT = true;

    private final Sender sender;
    private final NotificationStrategy singleEventNotificationStrategy;
    private final NotificationStrategy recurringEventNotificationStrategy;
    private final Function<byte[], OutboundMessage> outboundMessageFunction;
    private final Clock clock;

    public ItipEmailNotificationPublisher(Sender sender,
                                          Function<byte[], OutboundMessage> outboundMessageFunction,
                                          Clock clock) {
        this.sender = sender;
        this.singleEventNotificationStrategy = new SingleEventNotificationStrategy();
        this.recurringEventNotificationStrategy = new RecurringEventNotificationStrategy();
        this.outboundMessageFunction = outboundMessageFunction;
        this.clock = clock;
    }

    /**
     * @param newCalendar {@code localDelivery.message()} already parsed by the caller: it is only read, never modified.
     */
    Mono<Void> send(ItipLocalDeliveryDTO localDelivery,
                    URI eventPath,
                    Calendar newCalendar,
                    Optional<Calendar> oldEventCalendar) {
        return sender.send(toOutboundMessages(localDelivery, eventPath, newCalendar, oldEventCalendar)).then();
    }

    private Flux<OutboundMessage> toOutboundMessages(ItipLocalDeliveryDTO localDelivery,
                                                     URI eventPath,
                                                     Calendar newCalendar,
                                                     Optional<Calendar> oldEventCalendar) {
        return Mono.fromCallable(() -> buildNotificationMessages(localDelivery, eventPath, newCalendar, oldEventCalendar))
            .flatMapMany(Flux::fromIterable)
            .flatMap(payload -> toOutboundMessage(payload, localDelivery.uid()));
    }

    @VisibleForTesting
    List<NotificationEmailDTO> buildNotificationMessages(ItipLocalDeliveryDTO localDelivery,
                                                         URI eventPath,
                                                         Optional<Calendar> oldEventCalendar) {
        return buildNotificationMessages(localDelivery, eventPath, CalendarUtil.parseIcs(localDelivery.message()), oldEventCalendar);
    }

    @VisibleForTesting
    List<NotificationEmailDTO> buildNotificationMessages(ItipLocalDeliveryDTO localDelivery,
                                                         URI eventPath,
                                                         Calendar newCalendar,
                                                         Optional<Calendar> oldEventCalendar) {
        return notificationStrategy(newCalendar)
            .handle(localDelivery, eventPath, newCalendar, oldEventCalendar)
            .stream()
            .filter(this::notExpired)
            .map(Notification::payload)
            .toList();
    }

    /**
     * Suppresses notifications for events that have already occurred, mirroring esn-sabre's
     * {@code IMipPlugin::testIfEventIsExpired}. The check is based on the event carried by the
     * notification, which always reflects the new (rescheduled) time: rescheduling a past event to
     * a future slot therefore still notifies attendees.
     */
    private boolean notExpired(Notification notification) {
        return !CalendarEventUtils.vEventExpired(EventParseUtils.getFirstEvent(notification.eventCalendar()), clock);
    }

    private NotificationStrategy notificationStrategy(Calendar newCalendar) {
        if (EventParseUtils.isRecurringEvent(newCalendar)) {
            return recurringEventNotificationStrategy;
        }
        return singleEventNotificationStrategy;
    }

    private interface NotificationStrategy {

        List<Notification> handle(ItipLocalDeliveryDTO localDelivery,
                                  URI eventPath,
                                  Calendar newCalendar,
                                  Optional<Calendar> oldCalendar);

        default List<Notification> handleCounter(ItipLocalDeliveryDTO localDelivery, URI eventPath, Calendar newCalendar) {
            return List.of(NotificationEmailDTO.builder(localDelivery, newCalendar)
                .withEventPath(eventPath)
                .withOldEvent(localDelivery.oldMessage())
                .build());
        }
    }

    private static class SingleEventNotificationStrategy implements NotificationStrategy {
        @Override
        public List<Notification> handle(ItipLocalDeliveryDTO localDelivery,
                                         URI eventPath,
                                         Calendar newCalendar,
                                         Optional<Calendar> oldCalendar) {
            NotificationEmailDTO.Builder builderTemplate = NotificationEmailDTO.builder(localDelivery, newCalendar)
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

        private List<Notification> buildEventDiffNotifications(ItipLocalDeliveryDTO localDelivery, Calendar newCalendar, Calendar old, Builder builderTemplate) {
            return CalendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, old)
                .stream()
                .map(diff -> builderTemplate
                    .isNewEvent(diff.isNewEvent())
                    .withChanges(diff.serializeChanges()).build())
                .toList();
        }
    }

    private static class RecurringEventNotificationStrategy implements NotificationStrategy {
        @Override
        public List<Notification> handle(ItipLocalDeliveryDTO localDelivery,
                                         URI eventPath,
                                         Calendar newCalendar,
                                         Optional<Calendar> oldCalendar) {
            return switch (StringUtils.upperCase(localDelivery.method())) {
                case Method.VALUE_COUNTER -> handleCounter(localDelivery, eventPath, newCalendar);
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

        private List<Notification> buildDiffWithCancellations(ItipLocalDeliveryDTO localDelivery,
                                                              URI eventPath,
                                                              Calendar newCalendar,
                                                              Calendar oldCalendar) {
            Optional<ExDateChanges> exDateChanges = computeExDateChanges(newCalendar, oldCalendar);
            Map<Temporal, Calendar> cancelCalendarsByRecurrence = exDateChanges
                .map(changes -> computeCancelledCalendars(changes, newCalendar))
                .orElseGet(Map::of);
            Set<Temporal> recurrencesNotifiedSeparately = Sets.union(cancelCalendarsByRecurrence.keySet(),
                exDateChanges.map(ExDateChanges::removedExDates).orElseGet(Set::of));
            ZoneId floatingZone = EventParseUtils.floatingZone(newCalendar);

            List<Notification> requestNotifications = CalendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, oldCalendar)
                .stream()
                .filter(diff -> shouldSendRequest(diff, recurrencesNotifiedSeparately, floatingZone))
                .map(diff -> buildChangedOccurrenceNotification(NotificationEmailDTO.builder(localDelivery, newCalendar).withEventPath(eventPath),
                    newCalendar, diff))
                .toList();

            List<Notification> cancelNotifications = List.copyOf(cancelCalendarsByRecurrence.values()).stream()
                .map(cancelCalendar -> NotificationEmailDTO.builder(localDelivery, newCalendar)
                    .withEventPath(eventPath)
                    .withMethod(Method.VALUE_CANCEL)
                    .withEvent(renderablePayload(cancelCalendar))
                    .build())
                .toList();

            List<Notification> restoredNotifications = exDateChanges.stream()
                .flatMap(changes -> computeRestoredCalendars(changes, newCalendar))
                .map(restoredCalendar -> NotificationEmailDTO.builder(localDelivery, newCalendar)
                    .withEventPath(eventPath)
                    .withEvent(renderablePayload(restoredCalendar))
                    .isNewEvent(MARK_AS_NEW_EVENT)
                    .build())
                .toList();

            return Stream.of(cancelNotifications, requestNotifications, restoredNotifications)
                .flatMap(List::stream)
                .toList();
        }

        private List<Notification> buildDiffBasedNotifications(ItipLocalDeliveryDTO localDelivery,
                                                               URI eventPath,
                                                               Calendar newCalendar,
                                                               Calendar oldCalendar) {
            ZoneId floatingZone = EventParseUtils.floatingZone(newCalendar);
            return CalendarDiffCalculator.calculate(localDelivery.strippedRecipient(), newCalendar, oldCalendar)
                .stream()
                .filter(diff -> isNotifiable(diff, floatingZone))
                .map(diff -> buildChangedOccurrenceNotification(
                    NotificationEmailDTO.builder(localDelivery, newCalendar).withEventPath(eventPath), newCalendar, diff))
                .toList();
        }

        private List<Notification> buildNotificationsByOccurrence(ItipLocalDeliveryDTO localDelivery,
                                                                  URI eventPath,
                                                                  Calendar newCalendar,
                                                                  boolean markAsNewEvent) {
            NotificationEmailDTO.Builder payloadBuilderTemplate = NotificationEmailDTO.builder(localDelivery, newCalendar)
                .withEventPath(eventPath)
                .isNewEvent(markAsNewEvent);

            List<VEvent> vevents = newCalendar.getComponents(Component.VEVENT)
                .stream()
                .map(VEvent.class::cast)
                .toList();

            if (vevents.size() == 1) {
                return List.of(payloadBuilderTemplate.build());
            }

            return vevents.stream()
                .map(vevent -> payloadBuilderTemplate.copy()
                    .withEvent(renderablePayload(CalendarUtil.withSingleVEvent(newCalendar, vevent)))
                    .build())
                .toList();
        }

        private Notification buildChangedOccurrenceNotification(NotificationEmailDTO.Builder payloadBuilderTemplate,
                                                                Calendar newCalendar,
                                                                EventDiff diff) {
            NotificationEmailDTO.Builder payloadBuilder = payloadBuilderTemplate
                .withEvent(renderablePayload(CalendarUtil.withSingleVEvent(newCalendar, diff.vevent())))
                .isNewEvent(diff.isNewEvent());
            Optional<ObjectNode> serializedChanges = diff.serializeChanges();
            serializedChanges.ifPresent(payloadBuilder::withChanges);
            return payloadBuilder.build();
        }

        /**
         * A calendar that parsed fine cannot always be rendered: unreadable date values are left out of the payload.
         */
        private static Calendar renderablePayload(Calendar calendar) {
            return CalendarEventUtils.withoutUnreadableDates(calendar);
        }

        private record ExDateChanges(VEvent previousMaster, VEvent currentMaster,
                                     Set<Temporal> addedExDates, Set<Temporal> removedExDates) {
        }

        /**
         * EXDATE changes are only meaningful within the same series. Without a recurring master on both sides (e.g. a
         * single event turned into a recurring one) or when the RRULE changed, the attendee is invited to the whole
         * series again, EXDATEs included: there is no occurrence to cancel or restore.
         */
        private Optional<ExDateChanges> computeExDateChanges(Calendar newCalendar, Calendar oldCalendar) {
            return EventParseUtils.findMasterRecurrenceEvent(oldCalendar)
                .flatMap(previousMaster -> EventParseUtils.findMasterRecurrenceEvent(newCalendar)
                    .filter(currentMaster -> !CalendarDiffCalculator.recurrenceRuleChanged(previousMaster, currentMaster))
                    .map(currentMaster -> computeExDateChanges(previousMaster, currentMaster, overriddenRecurrences(newCalendar))));
        }

        private ExDateChanges computeExDateChanges(VEvent previousMaster, VEvent currentMaster, Set<Temporal> overriddenRecurrences) {
            Set<Temporal> previousExDates = indexExDates(previousMaster);
            Set<Temporal> currentExDates = indexExDates(currentMaster);

            return new ExDateChanges(previousMaster, currentMaster,
                Sets.difference(currentExDates, previousExDates),
                removedExDates(previousExDates, currentMaster, currentExDates, overriddenRecurrences));
        }

        /**
         * An unreadable EXDATE in the current master may still exclude a previous EXDATE: restoring it is uncertain.
         */
        private Set<Temporal> removedExDates(Set<Temporal> previousExDates, VEvent currentMaster,
                                             Set<Temporal> currentExDates, Set<Temporal> overriddenRecurrences) {
            if (CalendarEventUtils.hasUnreadableDates(currentMaster, Property.EXDATE)) {
                return Set.of();
            }
            return Sets.difference(Sets.difference(previousExDates, currentExDates), overriddenRecurrences);
        }

        private Set<Temporal> overriddenRecurrences(Calendar calendar) {
            ZoneId floatingZone = EventParseUtils.floatingZone(calendar);
            return calendar.getComponents(Component.VEVENT).stream()
                .map(VEvent.class::cast)
                .flatMap(vevent -> recurrenceKey(vevent, floatingZone).stream())
                .collect(Collectors.toSet());
        }

        private Map<Temporal, Calendar> computeCancelledCalendars(ExDateChanges changes, Calendar newCalendar) {
            return changes.addedExDates()
                .stream()
                .flatMap(temporal -> buildCancelCalendar(temporal, changes.previousMaster(), newCalendar)
                    .map(calendar -> Map.entry(temporal, calendar))
                    .stream())
                .collect(LinkedHashMap::new,
                    (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                    Map::putAll);
        }

        private Optional<Calendar> buildCancelCalendar(Temporal temporal, VEvent previousMaster, Calendar templateCalendar) {
            return occurrence(previousMaster, temporal)
                .map(cancelledEvent -> {
                    cancelledEvent.removeAll(Property.STATUS);
                    cancelledEvent.add(new Status(Status.VALUE_CANCELLED));

                    Calendar calendar = CalendarUtil.withSingleVEvent(templateCalendar, cancelledEvent);
                    calendar.removeAll(Property.METHOD);
                    calendar.add(new Method(Method.VALUE_CANCEL));
                    return calendar;
                });
        }

        /**
         * Occurrences no longer excluded (and not overridden) are back in the series: the attendee is invited to them.
         */
        private Stream<Calendar> computeRestoredCalendars(ExDateChanges changes, Calendar newCalendar) {
            return changes.removedExDates()
                .stream()
                .flatMap(temporal -> occurrence(changes.currentMaster(), temporal).stream())
                .map(restoredEvent -> CalendarUtil.withSingleVEvent(newCalendar, restoredEvent));
        }

        private Optional<VEvent> occurrence(VEvent master, Temporal recurrenceId) {
            try {
                return Optional.of(createInstanceVEvent(master, recurrenceId));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Skipping notification for occurrence {} not part of the series", recurrenceId, exception);
                return Optional.empty();
            }
        }

        private Set<Temporal> indexExDates(VEvent masterEvent) {
            return masterEvent.getProperties(Property.EXDATE).stream()
                .map(exDate -> (ExDate<?>) exDate)
                .flatMap(CalendarEventUtils::readableDates)
                .flatMap(temporal -> normalizeExDate(temporal, masterEvent).stream())
                .collect(Collectors.toSet());
        }

        private Optional<Temporal> normalizeExDate(Temporal temporal, VEvent masterEvent) {
            if (temporal instanceof LocalDate localDate) {
                return normalizeDateExDate(localDate, masterEvent);
            }
            // Independent parses of VTIMEZONE can assign different ZoneIds to the same instant.
            Optional<Temporal> instant = EventParseUtils.temporalToZonedDateTime(temporal, EventParseUtils.floatingZone(masterEvent))
                .map(ChronoZonedDateTime::toInstant);
            if (instant.isEmpty()) {
                LOGGER.warn("Ignoring EXDATE {} that cannot be converted to an instant", temporal);
            }
            return instant;
        }

        /**
         * A date-only EXDATE on a timed series excludes the occurrence of that day: it is anchored at the master's
         * start time, in the master's zone.
         */
        private Optional<Temporal> normalizeDateExDate(LocalDate exDate, VEvent masterEvent) {
            if (EventParseUtils.isAllDay(masterEvent)) {
                return Optional.of(exDate);
            }
            try {
                ZonedDateTime masterStart = EventParseUtils.getStartTime(masterEvent);
                return Optional.of(exDate.atTime(masterStart.toLocalTime()).atZone(masterStart.getZone()).toInstant());
            } catch (IllegalStateException exception) {
                LOGGER.warn("Ignoring date EXDATE {} of a series without a valid DTSTART", exDate, exception);
                return Optional.empty();
            }
        }

        private boolean shouldSendRequest(EventDiff diff, Set<Temporal> excludedRecurrenceIds, ZoneId floatingZone) {
            return isMaster(diff) || recurrenceKey(diff.vevent(), floatingZone)
                .filter(recurrenceId -> !excludedRecurrenceIds.contains(recurrenceId))
                .isPresent();
        }

        /**
         * An override whose RECURRENCE-ID cannot be read cannot be rendered: only the other occurrences are notified.
         */
        private boolean isNotifiable(EventDiff diff, ZoneId floatingZone) {
            return isMaster(diff) || recurrenceKey(diff.vevent(), floatingZone).isPresent();
        }

        private boolean isMaster(EventDiff diff) {
            return diff.vevent().getRecurrenceId() == null;
        }

        private Optional<Temporal> recurrenceKey(VEvent vevent, ZoneId floatingZone) {
            return Optional.ofNullable(vevent.getRecurrenceId())
                .flatMap(recurrenceId -> recurrenceDate(vevent, recurrenceId))
                .flatMap(recurrenceDate -> toRecurrenceKey(recurrenceDate, floatingZone));
        }

        /**
         * An unparsable or empty RECURRENCE-ID must not prevent notifying the other occurrences: such an override is not keyed.
         */
        private Optional<Temporal> recurrenceDate(VEvent vevent, DateProperty<?> recurrenceId) {
            try {
                Optional<Temporal> recurrenceDate = Optional.ofNullable(recurrenceId.getDate());
                if (recurrenceDate.isEmpty()) {
                    LOGGER.warn("Ignoring override with empty RECURRENCE-ID for event {}",
                        vevent.getUid().map(Property::getValue).orElse(null));
                }
                return recurrenceDate;
            } catch (DateTimeException exception) {
                // getValue() would trigger the same failing parse: log the UID instead
                LOGGER.warn("Ignoring override with unparsable RECURRENCE-ID for event {}",
                    vevent.getUid().map(Property::getValue).orElse(null), exception);
                return Optional.empty();
            }
        }

        private Optional<Temporal> toRecurrenceKey(Temporal temporal, ZoneId floatingZone) {
            if (temporal instanceof LocalDate localDate) {
                return Optional.of(localDate);
            }
            Optional<Temporal> recurrenceKey = EventParseUtils.temporalToZonedDateTime(temporal, floatingZone)
                .map(ChronoZonedDateTime::toInstant);
            if (recurrenceKey.isEmpty()) {
                LOGGER.warn("Ignoring override with unsupported RECURRENCE-ID {}", temporal);
            }
            return recurrenceKey;
        }
    }

    private Mono<OutboundMessage> toOutboundMessage(NotificationEmailDTO payload, String uid) {
        return Mono.fromCallable(() -> outboundMessageFunction
                .apply(OBJECT_MAPPER.writeValueAsBytes(payload.serialize())))
            .onErrorMap(error -> new RuntimeException("Failed to serialize email notification payload for uid " + uid, error));
    }

    /**
     * A payload to publish, along with the calendar its event was rendered from: the expiry check reads it
     * instead of parsing the payload again.
     */
    private record Notification(NotificationEmailDTO payload, Calendar eventCalendar) {
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record NotificationEmailDTO(@JsonProperty("senderEmail") String senderEmail,
                                @JsonProperty("recipientEmail") String recipientEmail,
                                @JsonProperty("method") String method,
                                @JsonProperty("event") String event,
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

        /**
         * @param messageCalendar {@code dto.message()} parsed: the event of the payload unless another one is supplied
         */
        static Builder builder(ItipLocalDeliveryDTO dto, Calendar messageCalendar) {
            return new Builder(dto.strippedSender(), dto.strippedRecipient(),
                dto.calendarId(), dto.method()).withEvent(dto.message(), messageCalendar);
        }

        ObjectNode serialize() {
            return SERIALIZER.valueToTree(this);
        }

        static final class Builder {
            private final String senderEmail;
            private final String recipientEmail;
            private String method;
            private final String calendarURI;
            private String event;
            private Calendar eventCalendar;
            private boolean isNewEvent;
            private Optional<URI> eventPath = Optional.empty();
            private Optional<String> oldEvent = Optional.empty();
            private Optional<ObjectNode> changes = Optional.empty();

            private Builder(String senderEmail, String recipientEmail,
                            String calendarURI, String method) {
                this.senderEmail = senderEmail;
                this.recipientEmail = recipientEmail;
                this.calendarURI = calendarURI;
                this.method = method;
            }

            Builder withEvent(Calendar renderableCalendar) {
                return withEvent(renderableCalendar.toString(), renderableCalendar);
            }

            private Builder withEvent(String eventIcs, Calendar calendar) {
                event = eventIcs;
                eventCalendar = calendar;
                return this;
            }

            Builder withMethod(String methodValue) {
                method = methodValue;
                return this;
            }

            Builder withEventPath(URI eventPath) {
                this.eventPath = Optional.of(eventPath);
                return this;
            }

            Builder withEventPath(Optional<URI> eventPath) {
                this.eventPath = eventPath;
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
                return new Builder(senderEmail, recipientEmail, calendarURI, method)
                    .withEvent(event, eventCalendar)
                    .withEventPath(eventPath)
                    .withOldEvent(oldEvent)
                    .isNewEvent(isNewEvent)
                    .withChanges(changes.map(ObjectNode::deepCopy));
            }

            Notification build() {
                return new Notification(new NotificationEmailDTO(
                    senderEmail, recipientEmail, method, event, calendarURI,
                    eventPath.map(URI::getPath), oldEvent, isNewEvent, changes), eventCalendar);
            }
        }
    }
}
