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

import static org.apache.james.backends.rabbitmq.Constants.DURABLE;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.AuditTrail;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.BuiltinExchangeType;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

public class EventAuditLogConsumer implements Closeable, Startable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventAuditLogConsumer.class);
    private static final boolean REQUEUE_ON_NACK = true;
    static final String AUDIT_QUEUE = "tcalendar:audit";
    static final String AUDIT_DEAD_LETTER = "tcalendar:audit:dead-letter";

    private static final List<String> EXCHANGES = List.of(
        "sabre:contact:created",
        "sabre:contact:deleted",
        "sabre:contact:updated",
        "sabre:contact:update",
        "calendar:subscription:created",
        "calendar:subscription:deleted",
        "calendar:subscription:updated",
        "calendar:calendar:created",
        "calendar:calendar:deleted",
        "calendar:calendar:updated",
        "calendar:event:created",
        "calendar:event:updated",
        "calendar:event:deleted",
        "calendar:event:request",
        "calendar:event:cancel",
        "calendar:event:reply",
        "sabre:addressbook:created",
        "sabre:addressbook:deleted",
        "sabre:addressbook:updated",
        "sabre:addressbook:subscription:created",
        "sabre:addressbook:subscription:deleted",
        "sabre:addressbook:subscription:updated");

    private static final Map<String, String> MESSAGE_TEMPLATES = Map.ofEntries(
        Map.entry("sabre:contact:created", "Contact created"),
        Map.entry("sabre:contact:deleted", "Contact deleted"),
        Map.entry("sabre:contact:updated", "Contact updated"),
        Map.entry("sabre:contact:update", "Contact updated"),
        Map.entry("calendar:subscription:created", "Calendar subscription created"),
        Map.entry("calendar:subscription:deleted", "Calendar subscription deleted"),
        Map.entry("calendar:subscription:updated", "Calendar subscription updated"),
        Map.entry("calendar:calendar:created", "Calendar created"),
        Map.entry("calendar:calendar:deleted", "Calendar deleted"),
        Map.entry("calendar:calendar:updated", "Calendar updated"),
        Map.entry("calendar:event:created", "Calendar event created"),
        Map.entry("calendar:event:updated", "Calendar event updated"),
        Map.entry("calendar:event:deleted", "Calendar event deleted"),
        Map.entry("calendar:event:request", "Calendar event (iTIP request)"),
        Map.entry("calendar:event:cancel", "Calendar event (iTIP cancel)"),
        Map.entry("calendar:event:reply", "Calendar event (iTIP reply)"),
        Map.entry("sabre:addressbook:created", "Address Book created"),
        Map.entry("sabre:addressbook:deleted", "Address Book deleted"),
        Map.entry("sabre:addressbook:updated", "Address Book updated"),
        Map.entry("sabre:addressbook:subscription:created", "Address Book subscription created"),
        Map.entry("sabre:addressbook:subscription:deleted", "Address Book subscription deleted"),
        Map.entry("sabre:addressbook:subscription:updated", "Address Book subscription updated"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReceiverProvider receiverProvider;
    private Disposable consumeDisposable;

    @Inject
    @Singleton
    public EventAuditLogConsumer(ReactorRabbitMQChannelPool channelPool,
                                 @Named(CalendarAmqpModule.INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.receiverProvider = channelPool::createReceiver;
        declareInfrastructure(channelPool.getSender(), queueArgumentSupplier);
    }

    private void declareInfrastructure(Sender sender, Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        Flux.concat(
                sender.declareExchange(ExchangeSpecification.exchange(AUDIT_DEAD_LETTER)
                    .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                sender.declareQueue(QueueSpecification
                    .queue(AUDIT_DEAD_LETTER)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(AUDIT_DEAD_LETTER)
                    .queue(AUDIT_DEAD_LETTER)
                    .routingKey(EMPTY_ROUTING_KEY)),
                sender.declareQueue(QueueSpecification
                    .queue(AUDIT_QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(AUDIT_DEAD_LETTER)
                        .build())))
            .thenMany(Flux.fromIterable(EXCHANGES)
                .flatMap(exchange -> Flux.concat(
                    sender.declareExchange(ExchangeSpecification.exchange(exchange)
                        .durable(DURABLE).type(BuiltinExchangeType.FANOUT.getType())),
                    sender.bind(BindingSpecification.binding()
                        .exchange(exchange)
                        .queue(AUDIT_QUEUE)
                        .routingKey(EMPTY_ROUTING_KEY)))))
            .then()
            .block();
    }

    public void init() {
        start();
    }

    public void start() {
        consumeDisposable = doConsume();
    }

    public void restart() {
        close();
        start();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event audit log consumer");
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    private Disposable doConsume() {
        return delivery()
            .flatMap(this::messageConsume, DEFAULT_CONCURRENCY)
            .subscribe();
    }

    public Flux<AcknowledgableDelivery> delivery() {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(AUDIT_QUEUE, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<?> messageConsume(AcknowledgableDelivery ackDelivery) {
        return Mono.fromRunnable(() -> {
                String body = new String(ackDelivery.getBody(), StandardCharsets.UTF_8);
                String exchangeName = ackDelivery.getEnvelope().getExchange();
                AuditTrail.entry()
                    .action(exchangeName)
                    .username(() -> extractOwner(body).orElse(null))
                    .parameters(() -> {
                        ImmutableMap.Builder<String, String> params = ImmutableMap.builder();
                        extractPath(body).ifPresent(p -> params.put("path", p));
                        return params.build();
                    })
                    .log(formatMessage(body, exchangeName));
            })
            .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Consumed audit log event")))
            .doOnSuccess(result -> ackDelivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when consume audit log event", error);
                ackDelivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private static String formatMessage(String body, String exchangeName) {
        String message = MESSAGE_TEMPLATES.getOrDefault(exchangeName, "Unknown event");
        Optional<String> uid = extractUid(body);
        Optional<String> path = extractPath(body);
        StringBuilder sb = new StringBuilder(message);
        uid.ifPresent(u -> sb.append(" (uid=").append(u).append(")"));
        path.ifPresent(p -> sb.append(" [").append(p).append("]"));
        return sb.toString();
    }

    public static Optional<String> extractOwner(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            Optional<String> path = Optional.empty();
            if (root.has("eventPath")) {
                path = Optional.ofNullable(root.get("eventPath").asText(null));
            } else if (root.has("calendarPath")) {
                path = Optional.ofNullable(root.get("calendarPath").asText(null));
            }
            return path.flatMap(EventAuditLogConsumer::parseOwnerFromPath);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> parseOwnerFromPath(String path) {
        try {
            List<String> parts = Splitter.on('/')
                .omitEmptyStrings()
                .splitToList(path);
            if (parts.size() >= 3 && !parts.getFirst().isEmpty()) {
                return Optional.of(parts.get(1));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> extractUid(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.has("uid")) {
                return Optional.ofNullable(root.get("uid").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<String> extractPath(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.has("eventPath")) {
                return Optional.ofNullable(root.get("eventPath").asText(null));
            }
            if (root.has("calendarPath")) {
                return Optional.ofNullable(root.get("calendarPath").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}