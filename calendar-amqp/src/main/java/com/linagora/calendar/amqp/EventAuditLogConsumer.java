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
import java.util.List;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.ReceiverProvider;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.AuditTrail;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.inject.name.Named;

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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final boolean REQUEUE_ON_NACK = true;

    public static final String QUEUE_NAME = "tcalendar:event:audit:log";
    public static final String DEAD_LETTER_QUEUE = QUEUE_NAME + ":dead-letter";

    private static final List<String> EXCHANGES = List.of(
        "calendar:calendar:created",
        "calendar:calendar:updated",
        "calendar:calendar:deleted",
        "calendar:subscription:created",
        "calendar:subscription:updated",
        "calendar:subscription:deleted",
        "calendar:event:created",
        "calendar:event:updated",
        "calendar:event:deleted",
        "sabre:contact:created",
        "sabre:contact:deleted",
        "sabre:contact:updated",
        "sabre:contact:update",
        "sabre:addressbook:created",
        "sabre:addressbook:deleted",
        "sabre:addressbook:updated",
        "sabre:addressbook:subscription:created",
        "sabre:addressbook:subscription:deleted",
        "sabre:addressbook:subscription:updated");

    // ponytail: path field names tried in order; add more if Sabre uses a different key
    private static final List<String> PATH_FIELD_NAMES = List.of(
        "eventPath", "calendarPath", "contactPath", "addressBookPath");

    private final ReceiverProvider receiverProvider;
    private final ReactorRabbitMQChannelPool channelPool;
    private final Supplier<QueueArguments.Builder> queueArgumentSupplier;
    private Disposable consumeDisposable;

    @Inject
    public EventAuditLogConsumer(ReactorRabbitMQChannelPool channelPool,
                                 @Named(INJECT_KEY_DAV) Supplier<QueueArguments.Builder> queueArgumentSupplier) {
        this.channelPool = channelPool;
        this.queueArgumentSupplier = queueArgumentSupplier;
        this.receiverProvider = channelPool::createReceiver;
    }

    public void init() {
        declareExchangeAndQueue();
        start();
    }

    public void start() {
        if (consumeDisposable == null || consumeDisposable.isDisposed()) {
            consumeDisposable = doConsume();
        }
    }

    public void restart() {
        close();
        consumeDisposable = doConsume();
    }

    @Override
    @PreDestroy
    public void close() {
        LOGGER.info("Trying to stop event audit log consumer");
        if (consumeDisposable != null && !consumeDisposable.isDisposed()) {
            consumeDisposable.dispose();
        }
    }

    private void declareExchangeAndQueue() {
        Sender sender = channelPool.getSender();
        Flux.concat(
                Flux.fromIterable(EXCHANGES)
                    .flatMap(exchange -> sender.declareExchange(ExchangeSpecification.exchange(exchange)
                        .durable(DURABLE)
                        .type("fanout"))),
                sender.declareExchange(ExchangeSpecification.exchange(DEAD_LETTER_QUEUE)
                    .durable(DURABLE)
                    .type("fanout")),
                sender.declareQueue(QueueSpecification.queue(DEAD_LETTER_QUEUE)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get().build())),
                sender.bind(BindingSpecification.binding()
                    .exchange(DEAD_LETTER_QUEUE)
                    .queue(DEAD_LETTER_QUEUE)
                    .routingKey(EMPTY_ROUTING_KEY)),
                sender.declareQueue(QueueSpecification.queue(QUEUE_NAME)
                    .durable(DURABLE)
                    .arguments(queueArgumentSupplier.get()
                        .deadLetter(DEAD_LETTER_QUEUE)
                        .build())),
                Flux.fromIterable(EXCHANGES)
                    .flatMap(exchange -> sender.bind(BindingSpecification.binding()
                        .exchange(exchange)
                        .queue(QUEUE_NAME)
                        .routingKey(EMPTY_ROUTING_KEY))))
            .then()
            .block();
    }

    private Disposable doConsume() {
        return delivery(QUEUE_NAME)
            .flatMap(this::handleMessage, DEFAULT_CONCURRENCY)
            .subscribe();
    }

    private Flux<AcknowledgableDelivery> delivery(String queue) {
        return Flux.using(receiverProvider::createReceiver,
            receiver -> receiver.consumeManualAck(queue, new ConsumeOptions().qos(DEFAULT_CONCURRENCY)),
            Receiver::close);
    }

    private Mono<Void> handleMessage(AcknowledgableDelivery delivery) {
        String exchangeName = delivery.getEnvelope().getExchange();
        return Mono.fromCallable(() -> extractPath(delivery.getBody()))
            .flatMap(path -> logAuditTrail(exchangeName, path)
                .then(ReactorUtils.logAsMono(() -> LOGGER.debug("Audit logged {} {}",
                    exchangeName, path))))
            .doOnSuccess(result -> delivery.ack())
            .onErrorResume(error -> {
                LOGGER.error("Error when processing audit log event from {}", exchangeName, error);
                delivery.nack(!REQUEUE_ON_NACK);
                return Mono.empty();
            });
    }

    private String extractPath(byte[] body) {
        JsonNode root = OBJECT_MAPPER.readTree(body);
        return PATH_FIELD_NAMES.stream()
            .map(field -> root.path(field))
            .filter(node -> !node.isMissingNode() && !node.isNull())
            .findFirst()
            .map(JsonNode::asText)
            .orElse("<no-path>");
    }

    private String extractUser(String path) {
        if (path == null || path.isBlank() || "<no-path>".equals(path)) {
            return "<unknown>";
        }
        List<String> parts = Splitter.on('/')
            .omitEmptyStrings()
            .splitToList(path);
        // path format: /{resourceType}/{userId}/... so userId is at index 1
        return parts.size() >= 2 ? parts.get(1) : "<unknown>";
    }

    private Mono<Void> logAuditTrail(String exchangeName, String path) {
        return Mono.fromRunnable(() -> AuditTrail.entry()
            .action("AUDIT_LOG")
            .action(exchangeName)
            .parameters(() -> ImmutableMap.of(
                "user", extractUser(path),
                "path", path))
            .log("Sabre CRUD audit: " + exchangeName));
    }
}