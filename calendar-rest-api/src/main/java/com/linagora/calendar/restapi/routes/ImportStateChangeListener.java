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
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.linagora.calendar.storage.ImportEvent;
import com.linagora.calendar.storage.model.ImportId;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public record ImportStateChangeListener(Sinks.Many<WebsocketRoute.WebsocketMessage> outbound,
                                        Username username) implements EventListener.ReactiveEventListener {

    record ImportWebSocketMessage(URI importTargetURI,
                                  Map<ImportId, ImportResultMessage> imports) implements WebsocketRoute.WebsocketMessage {

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
            ImportResultMessage resultMessage = new ImportResultMessage(status,
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

    @Override
    public boolean isHandling(Event event) {
        return event instanceof ImportEvent;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        ImportEvent importEvent = (ImportEvent) event;
        return Mono.fromRunnable(() -> {
            synchronized (outbound) {
                outbound.emitNext(ImportWebSocketMessage.from(importEvent), Sinks.EmitFailureHandler.FAIL_FAST);
            }
        }).then();

    }
}