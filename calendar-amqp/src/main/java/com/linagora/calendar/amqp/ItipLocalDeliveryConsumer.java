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

import static com.linagora.calendar.amqp.CalendarAmqpModule.INJECT_KEY_DAV;
import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.core.Username;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.name.Named;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.rabbitmq.client.BuiltinExchangeType;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

/**
 * Consumes messages from {@code calendar:itip:localDelivery} and implements the fan-out then
 * process pattern described in ADR-0001.
 *
 * <p><b>Phase 1 — Fan-out</b> ({@code recipients.length > 1}): re-publishes one message per
 * recipient to the same exchange, then acks the original.
 *
 * <p><b>Phase 2 — Process</b> ({@code recipients.length === 1}): submits {@code POST /itip} to
 * Sabre impersonating the recipient, then publishes a {@code calendar:event:notificationEmail:send}
 * message when {@code hasChange} is true.
 */
public class ItipLocalDeliveryConsumer implements Closeable, Startable {

    public static final String EXCHANGE_NAME = "calendar:itip:localDelivery";
    public static final String QUEUE_NAME = "tcalendar:itip:localDelivery";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:itip:localDelivery:dead-letter";

    private static final String DEFAULT_CALENDAR_URI = "events";
    /** Date format used in the {@code changes} payload — PHP-compatible microsecond precision. */
    private static final DateTimeFormatter CHANGE_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.000000");

    private static final Logger LOGGER = LoggerFactory.getLogger(ItipLocalDeliveryConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private final ReceiverProvider receiverProvider;
    private final Sender sender;
    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final int prefetchCount;

    private Disposable consumeDisposable;

    @Inject
    public ItipLocalDeliveryConsumer(ReactorRabbitMQChannelPool channelPool,
                                     @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                     CalDavClient calDavClient,
                                     OpenPaaSUserDAO openPaaSUserDAO,
                                     @Named("itipEventMessagesPrefetchCount") int prefetchCount) {
        this.receiverProvider = channelPool::createReceiver;
        this.sender = channelPool.getSender();
        this.calDavClient = calDavClient;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.prefetchCount = prefetchCount;

        Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(EXCHANGE_NAME)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(DEAD_LETTER_QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get().build())),
                sender.declareQueue(QueueSpecification
                    .queue(QUEUE_NAME)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get().deadLetter(DEAD_LETTER_QUEUE).build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(EXCHANGE_NAME)
                    .queue(QUEUE_NAME)
                    .routingKey(EMPTY_ROUTING_KEY)))
            .then()
            .block();
    }

    public void init() {
        start();
    }

    public void start() {
        consumeDisposable = doConsumeMessages();
    }

    public void restart() {
        close();
        start();
    }

    @Override
    public void close() {
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    public Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(prefetchCount)),
            Receiver::close);
    }

    private Disposable doConsumeMessages() {
        return delivery(QUEUE_NAME)
            .flatMap(this::consumeMessage, DEFAULT_CONCURRENCY)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    private Mono<Void> consumeMessage(AcknowledgableDelivery ackDelivery) {
        return Mono.fromCallable(() -> OBJECT_MAPPER.readValue(ackDelivery.getBody(), ItipLocalDeliveryDTO.class))
            .flatMap(dto -> {
                if (dto.recipients().size() > 1) {
                    return fanOut(dto);
                }
                return processSingleRecipient(dto);
            })
            .doOnSuccess(ignored -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error consuming calendar:itip:localDelivery message", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    // ---- Fan-out phase -----------------------------------------------------------------------

    /**
     * Splits an N-recipient message into N single-recipient messages re-published on the same
     * exchange. Each single-recipient message is then independently retried / dead-lettered.
     */
    private Mono<Void> fanOut(ItipLocalDeliveryDTO dto) {
        List<OutboundMessage> outboundMessages = dto.recipients().stream()
            .map(recipient -> {
                try {
                    byte[] payload = OBJECT_MAPPER.writeValueAsBytes(dto.withSingleRecipient(recipient));
                    return new OutboundMessage(EXCHANGE_NAME, EMPTY_ROUTING_KEY, payload);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize fan-out message for recipient " + recipient, e);
                }
            })
            .toList();

        return sender.send(Flux.fromIterable(outboundMessages))
            .then(ReactorUtils.logAsMono(() ->
                LOGGER.debug("Fanned out {} recipients for uid {}", dto.recipients().size(), dto.uid())));
    }

    // ---- Processing phase --------------------------------------------------------------------

    /**
     * Processes a single-recipient message: calls Sabre's {@code POST /itip} endpoint, then
     * publishes an email notification when {@code hasChange} is true.
     */
    private Mono<Void> processSingleRecipient(ItipLocalDeliveryDTO dto) {
        Username recipientUsername = Username.of(dto.strippedRecipient());

        return calDavClient.sendItip(recipientUsername, dto.uid(), dto.strippedSender(),
                dto.strippedRecipient(), dto.message(), dto.method())
            .flatMap(isLocal -> {
                if (!dto.hasChange()) {
                    return Mono.empty();
                }
                return publishEmailNotification(dto, recipientUsername, isLocal);
            });
    }

    // ---- Email notification ------------------------------------------------------------------

    private Mono<Void> publishEmailNotification(ItipLocalDeliveryDTO dto,
                                                 Username recipientUsername,
                                                 boolean isLocal) {
        return resolveEventPath(dto, recipientUsername, isLocal)
            .map(eventPath -> buildEmailNotificationPayload(dto, eventPath))
            .flatMap(payloadBytes -> sender.send(
                Mono.just(new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY, payloadBytes))))
            .then(ReactorUtils.logAsMono(() ->
                LOGGER.debug("Published email notification for uid {} to {}", dto.uid(), dto.strippedRecipient())));
    }

    /**
     * Resolves {@code /calendars/<userId>/events/<uid>.ics} for local recipients.
     * Returns an empty string for external recipients (eventPath omitted in the payload).
     */
    private Mono<String> resolveEventPath(ItipLocalDeliveryDTO dto,
                                           Username recipientUsername,
                                           boolean isLocal) {
        if (!isLocal) {
            return Mono.just("");
        }
        return openPaaSUserDAO.retrieve(recipientUsername)
            .map(user -> CalendarURL.CALENDAR_URL_PATH_PREFIX
                + "/" + user.id().value()
                + "/" + DEFAULT_CALENDAR_URI
                + "/" + dto.uid() + ".ics")
            .switchIfEmpty(Mono.just(""));
    }

    private byte[] buildEmailNotificationPayload(ItipLocalDeliveryDTO dto, String eventPath) {
        try {
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("senderEmail", dto.strippedSender());
            node.put("recipientEmail", dto.strippedRecipient());
            node.put("method", dto.method());
            node.put("event", dto.message());
            node.put("notify", true);
            node.put("calendarURI", DEFAULT_CALENDAR_URI);

            if (!eventPath.isEmpty()) {
                node.put("eventPath", eventPath);
            }

            boolean isNewEvent = isNewEventForRecipient(dto);
            node.put("isNewEvent", isNewEvent);

            computeChanges(dto).ifPresent(changesNode -> node.set("changes", changesNode));

            if ("COUNTER".equalsIgnoreCase(dto.method())) {
                dto.oldMessage().ifPresent(old -> node.put("oldEvent", old));
            }

            return OBJECT_MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize email notification for uid " + dto.uid(), e);
        }
    }

    /**
     * Returns {@code true} when the recipient was not listed as an attendee in the old iCal
     * (i.e. the event is new for them, or there is no previous state).
     */
    private boolean isNewEventForRecipient(ItipLocalDeliveryDTO dto) {
        if (dto.oldMessage().isEmpty()) {
            return true;
        }
        try {
            Calendar oldCal = CalendarUtil.parseIcs(dto.oldMessage().get());
            String recipient = dto.recipients().get(0).toLowerCase();
            return oldCal.getComponents(Component.VEVENT).stream()
                .map(VEvent.class::cast)
                .flatMap(vEvent -> vEvent.getProperties(Property.ATTENDEE).stream())
                .noneMatch(p -> p.getValue().toLowerCase().contains(recipient.replace("mailto:", "")));
        } catch (Exception e) {
            LOGGER.warn("Could not determine isNewEvent for {}: {}", dto.strippedRecipient(), e.getMessage());
            return false;
        }
    }

    // ---- Changes diff ------------------------------------------------------------------------

    /**
     * Computes the diff between the old and new iCal for the tracked properties
     * (SUMMARY, LOCATION, DESCRIPTION, DTSTART, DTEND).
     *
     * <p>Returns {@code Optional.empty()} when there is no previous state (new event).
     * Returns an (possibly empty) {@code ObjectNode} when a previous state exists.
     */
    private Optional<ObjectNode> computeChanges(ItipLocalDeliveryDTO dto) {
        if (dto.oldMessage().isEmpty()) {
            return Optional.empty();
        }
        try {
            Calendar oldCal = CalendarUtil.parseIcs(dto.oldMessage().get());
            Calendar newCal = CalendarUtil.parseIcs(dto.message());

            VEvent oldEvent = firstVEvent(oldCal).orElse(null);
            VEvent newEvent = firstVEvent(newCal).orElse(null);
            if (oldEvent == null || newEvent == null) {
                return Optional.of(OBJECT_MAPPER.createObjectNode());
            }

            ObjectNode changes = OBJECT_MAPPER.createObjectNode();

            compareStringProp(oldEvent, newEvent, Property.SUMMARY)
                .ifPresent(node -> changes.set("summary", node));
            compareStringProp(oldEvent, newEvent, Property.LOCATION)
                .ifPresent(node -> changes.set("location", node));
            compareStringProp(oldEvent, newEvent, Property.DESCRIPTION)
                .ifPresent(node -> changes.set("description", node));
            compareDateProp(oldEvent, newEvent, Property.DTSTART)
                .ifPresent(node -> changes.set("dtstart", node));
            compareDateProp(oldEvent, newEvent, Property.DTEND)
                .ifPresent(node -> changes.set("dtend", node));

            return Optional.of(changes);
        } catch (Exception e) {
            LOGGER.warn("Could not compute changes for uid {}: {}", dto.uid(), e.getMessage());
            return Optional.of(OBJECT_MAPPER.createObjectNode());
        }
    }

    private Optional<VEvent> firstVEvent(Calendar cal) {
        return cal.getComponent(Component.VEVENT).map(VEvent.class::cast);
    }

    private Optional<ObjectNode> compareStringProp(VEvent oldEvent, VEvent newEvent, String propName) {
        String oldVal = oldEvent.getProperty(propName).map(Property::getValue).orElse("");
        String newVal = newEvent.getProperty(propName).map(Property::getValue).orElse("");
        if (Objects.equals(oldVal, newVal)) {
            return Optional.empty();
        }
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("previous", oldVal);
        node.put("current", newVal);
        return Optional.of(node);
    }

    private Optional<ObjectNode> compareDateProp(VEvent oldEvent, VEvent newEvent, String propName) {
        Optional<Property> oldProp = oldEvent.getProperty(propName);
        Optional<Property> newProp = newEvent.getProperty(propName);

        String oldSerialized = oldProp.map(Property::getValue).orElse("");
        String newSerialized = newProp.map(Property::getValue).orElse("");

        if (Objects.equals(oldSerialized, newSerialized)) {
            return Optional.empty();
        }

        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        oldProp.map(p -> toDateTimeValueNode(p, oldEvent))
            .ifPresent(v -> node.set("previous", v));
        newProp.map(p -> toDateTimeValueNode(p, newEvent))
            .ifPresent(v -> node.set("current", v));
        return Optional.of(node);
    }

    private ObjectNode toDateTimeValueNode(Property prop, VEvent vEvent) {
        boolean isAllDay = EventParseUtils.isDateType(prop);
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("isAllDay", isAllDay);

        ZonedDateTime zdt = EventParseUtils.parseTime(prop)
            .orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));

        node.put("date", CHANGE_DATE_FORMATTER.format(zdt));
        node.put("timezone_type", 3);

        String timezone = prop.getParameter(Parameter.TZID)
            .map(Parameter::getValue)
            .orElseGet(() -> zdt.getZone().getId());
        node.put("timezone", timezone);

        return node;
    }
}
