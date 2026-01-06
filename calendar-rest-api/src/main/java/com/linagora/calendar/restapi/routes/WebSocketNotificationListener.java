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

package com.linagora.calendar.restapi.routes;

import static com.linagora.calendar.restapi.routes.WebsocketRoute.MAPPER;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.EventBusAlarmEvent;
import com.linagora.calendar.storage.ImportEvent;
import com.linagora.calendar.storage.model.ImportId;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public record WebSocketNotificationListener(Sinks.Many<WebsocketRoute.WebsocketMessage> outbound,
                                            CalDavClient calDavClient,
                                            Username username) implements EventListener.ReactiveEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketNotificationListener.class);


    @Override
    public boolean isHandling(Event event) {
        return event instanceof CalendarChangeEvent
            || event instanceof ImportEvent
            || event instanceof EventBusAlarmEvent;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        if (event instanceof CalendarChangeEvent calendarChangeEvent) {
            return handleCalendarChange(calendarChangeEvent);
        }
        if (event instanceof ImportEvent importEvent) {
            return handleImportChange(importEvent);
        }
        if (event instanceof EventBusAlarmEvent alarmEvent) {
            return handleAlarmEvent(alarmEvent);
        }
        return Mono.empty();
    }

    private Mono<Void> handleAlarmEvent(EventBusAlarmEvent event) {
        return Mono.fromRunnable(() -> emit(AlarmMessage.from(event)))
            .then();
    }

    private Mono<Void> handleCalendarChange(CalendarChangeEvent event) {
        CalendarURL calendarUrl = event.calendarURL();
        return retrieveSyncToken(username, calendarUrl)
            .doOnNext(syncToken -> emit(new CalendarChangeMessage(calendarUrl, syncToken)))
            .then();
    }

    private Mono<Void> handleImportChange(ImportEvent event) {
        return Mono.fromRunnable(() -> emit(ImportWebSocketMessage.from(event)))
            .then();
    }

    private Mono<SyncToken> retrieveSyncToken(Username username, CalendarURL calendarURL) {
        return calDavClient.retrieveSyncToken(username, calendarURL)
            .doOnError(error -> LOGGER.error("Failed to retrieve SyncToken for {}", calendarURL.asUri(), error))
            .onErrorResume(error -> Mono.empty());
    }

    private void emit(WebsocketRoute.WebsocketMessage message) {
        synchronized (outbound) {
            outbound.emitNext(message, Sinks.EmitFailureHandler.FAIL_FAST);
        }
    }

    record ImportWebSocketMessage(URI importTargetURI,
                                  Map<ImportId, ImportWebSocketMessage.ImportResultMessage> imports) implements WebsocketRoute.WebsocketMessage {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record ImportResultMessage(String status,
                                   Integer succeedCount,
                                   Integer failedCount) {
        }

        public static final String IMPORTS_PROPERTY = "imports";
        public static final String STATUS_COMPLETED = "completed";
        public static final String STATUS_FAILED = "failed";

        public static ImportWebSocketMessage from(ImportEvent event) {
            String status = switch (event.status()) {
                case SUCCESS -> STATUS_COMPLETED;
                case FAILED -> STATUS_FAILED;
            };
            ImportWebSocketMessage.ImportResultMessage resultMessage = new ImportWebSocketMessage.ImportResultMessage(status,
                event.succeedCount().orElse(null), event.failedCount().orElse(null));

            return new ImportWebSocketMessage(event.importURI(), Map.of(event.importId(), resultMessage));
        }

        @Override
        public WebSocketFrame asWebSocketFrame() throws Exception {
            return new TextWebSocketFrame(serialize());
        }

        @JsonAnyGetter
        private Map<String, Object> asJsonProperties() {
            return Map.of(importTargetURI.toASCIIString(),
                Map.of(IMPORTS_PROPERTY,
                    imports.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().value(),
                            Map.Entry::getValue))));
        }

        public String serialize() throws JsonProcessingException {
            return MAPPER.writeValueAsString(asJsonProperties());
        }
    }

    public record CalendarChangeMessage(CalendarURL calendarURL,
                                        SyncToken syncToken) implements WebsocketRoute.WebsocketMessage {
        static final String SYNC_TOKEN_PROPERTY = "syncToken";

        @Override
        public WebSocketFrame asWebSocketFrame() throws JsonProcessingException {
            return new TextWebSocketFrame(serialize());
        }

        public String serialize() throws JsonProcessingException {
            return MAPPER.writeValueAsString(
                MAPPER.createObjectNode().set(calendarURL.asUri().toASCIIString(),
                    MAPPER.createObjectNode().put(SYNC_TOKEN_PROPERTY, syncToken.value())));
        }
    }

    public record AlarmMessage(String eventSummary,
                               String eventURL,
                               Instant eventStartTime) implements WebsocketRoute.WebsocketMessage {

        public static AlarmMessage from(EventBusAlarmEvent event) {
            return new AlarmMessage(event.eventSummary(), event.eventURL(), event.eventStartTime());
        }

        @Override
        public WebSocketFrame asWebSocketFrame() throws JsonProcessingException {
            return new TextWebSocketFrame(serialize());
        }

        public String serialize() throws JsonProcessingException {
            return MAPPER.writeValueAsString(
                MAPPER.createObjectNode()
                    .set("alarms", MAPPER.createArrayNode()
                        .add(MAPPER.createObjectNode()
                            .put("eventSummary", eventSummary)
                            .put("eventURL", eventURL)
                            .put("eventStartTime", eventStartTime.toString()))));
        }
    }

}
