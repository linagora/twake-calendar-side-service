/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  This file is subject to the Affero Gnu Public License           *
 *  version 3.                                                      *
 ********************************************************************/

package com.linagora.calendar.amqp;

import jakarta.inject.Inject;

import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

public class EventContactNotificationReconnectionHandler implements SimpleConnectionPool.ReconnectionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventContactNotificationReconnectionHandler.class);

    private final EventContactNotificationConsumer consumer;

    @Inject
    public EventContactNotificationReconnectionHandler(EventContactNotificationConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public Publisher<Void> handleReconnection(com.rabbitmq.client.Connection connection) {
        return Mono.fromRunnable(consumer::restart)
            .doOnError(error -> LOGGER.error("Error while handling reconnection for EventContactNotificationConsumer", error))
            .then();
    }
}
