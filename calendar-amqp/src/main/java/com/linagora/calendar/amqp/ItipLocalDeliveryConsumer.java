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
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.QueueArguments.Builder;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.core.Username;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.name.Named;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.ItipRequest;
import com.linagora.calendar.dav.dto.CalendarReportJsonResponse;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.rabbitmq.client.BuiltinExchangeType;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.Method;
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
 * message.
 */
public class ItipLocalDeliveryConsumer implements Closeable, Startable {

    public static final String EXCHANGE_NAME = "calendar:itip:localDelivery";
    public static final String QUEUE_NAME = "tcalendar:itip:localDelivery";
    public static final String DEAD_LETTER_QUEUE = "tcalendar:itip:localDelivery:dead-letter";

    private static final Logger LOGGER = LoggerFactory.getLogger(ItipLocalDeliveryConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private final ReceiverProvider receiverProvider;
    private final Sender sender;
    private final CalDavClient calDavClient;
    private final LocalRecipientResolver localRecipientResolver;
    private final ItipEmailNotificationPublisher itipEmailNotificationPublisher;
    private final int prefetchCount;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
    private Disposable consumeDisposable;

    @Inject
    public ItipLocalDeliveryConsumer(ReactorRabbitMQChannelPool channelPool,
                                     @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier,
                                     CalDavClient calDavClient,
                                     LocalRecipientResolver localRecipientResolver,
                                     @Named("itipEventMessagesPrefetchCount") int prefetchCount) {
        this.receiverProvider = channelPool::createReceiver;
        this.sender = channelPool.getSender();
        this.calDavClient = calDavClient;
        this.localRecipientResolver = localRecipientResolver;
        this.prefetchCount = prefetchCount;
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.itipEmailNotificationPublisher = new ItipEmailNotificationPublisher(sender,
            bytes -> new OutboundMessage(EventEmailConsumer.EXCHANGE_NAME, EMPTY_ROUTING_KEY, bytes));
    }

    private static void declareExchangeAndQueue(Supplier<Builder> queueArgumentSupplier, Sender sender) {
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
        declareExchangeAndQueue(queueArgumentSupplier, sender);
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

    private Mono<Void> processSingleRecipient(ItipLocalDeliveryDTO localDelivery) {
        Username recipientUsername = Username.of(localDelivery.strippedRecipient());
        Optional<Calendar> oldEventCalendar = localDelivery.oldMessage().map(CalendarUtil::parseIcs);

        return localRecipientResolver.resolve(recipientUsername)
            .flatMap(localRecipientId -> sendItipIfNecessary(localDelivery, recipientUsername, localRecipientId)
                .then(publishEmailNotification(localDelivery, recipientUsername, localRecipientId, oldEventCalendar)));
    }

    private Mono<Void> sendItipIfNecessary(ItipLocalDeliveryDTO localDelivery,
                                           Username recipientUsername,
                                           Optional<OpenPaaSId> localRecipientId) {
        if (localRecipientId.isEmpty() || Method.VALUE_COUNTER.equalsIgnoreCase(localDelivery.method())) {
            return Mono.empty();
        }

        Optional<Integer> sequence = EventParseUtils.maxSequenceNo(CalendarUtil.parseIcs(localDelivery.message()));

        ItipRequest itipRequest = new ItipRequest(localDelivery.uid(), localDelivery.strippedSender(),
            localDelivery.strippedRecipient(), localDelivery.message(), localDelivery.method(), sequence);
        return calDavClient.sendItip(recipientUsername, itipRequest)
            .then();
    }

    private Mono<Void> publishEmailNotification(ItipLocalDeliveryDTO localDelivery,
                                                Username recipientUsername,
                                                Optional<OpenPaaSId> localRecipientId,
                                                Optional<Calendar> oldEventCalendar) {
        return resolveEventPath(localDelivery, recipientUsername, localRecipientId)
            .flatMap(eventPath -> itipEmailNotificationPublisher.send(localDelivery, eventPath, oldEventCalendar))
            .then(ReactorUtils.logAsMono(() ->
                LOGGER.debug("Published email notifications for uid {} to {}", localDelivery.uid(), localDelivery.strippedRecipient())));
    }

    private Mono<URI> resolveEventPath(ItipLocalDeliveryDTO localDelivery,
                                       Username recipientUsername,
                                       Optional<OpenPaaSId> localRecipientId) {
        return Mono.justOrEmpty(localRecipientId)
            .flatMap(recipientId -> retrieveRecipientEventHref(recipientUsername, recipientId, localDelivery.uid()))
            .switchIfEmpty(Mono.just(defaultEventPath(localDelivery.calendarId(), localDelivery.uid())));
    }

    private URI defaultEventPath(String calendarId, String uid) {
        if (StringUtils.isEmpty(calendarId)) {
            return URI.create(StringUtils.EMPTY);
        }
        return URI.create(String.format("%s/%s/%s/%s.ics", CalendarURL.CALENDAR_URL_PATH_PREFIX, calendarId, calendarId, uid));
    }

    private Mono<URI> retrieveRecipientEventHref(Username recipientUsername, OpenPaaSId recipientId, String uid) {
        return calDavClient.calendarReportByUid(recipientUsername, recipientId, uid)
            .map(CalendarReportJsonResponse::calendarHref)
            .onErrorResume(e -> {
                LOGGER.debug("Could not retrieve recipient event href for uid {} and recipient {}", uid, recipientUsername.asString(), e);
                return Mono.empty();
            });
    }

}
