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

import java.util.Map;

import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public record ImportStateChangeListener(Sinks.Many<WebsocketRoute.WebsocketMessage> outbound,
                                        Username username) implements EventListener.ReactiveEventListener {

    record ImportWebSocketMessage(String importType,
                                  String status,
                                  String baseId,
                                  String resourceId,
                                  int succeedCount,
                                  int failedCount) implements WebsocketRoute.WebsocketMessage {

        public static ImportWebSocketMessage from(ImportEvent importEvent) {
            return new ImportWebSocketMessage(importEvent.importType().getTemplateType().value(),
                "completed",
                importEvent.baseId(),
                importEvent.resourceId(),
                importEvent.succeedCount(),
                importEvent.failedCount());
        }

        @Override
        public WebSocketFrame asWebSocketFrame() throws Exception {
            return new TextWebSocketFrame(serialize());
        }

        private String serialize() throws JsonProcessingException {
            return MAPPER.writeValueAsString(
                Map.of(
                    "type", importType,
                    "status", status,
                    "payload", Map.of(
                        "baseId", baseId,
                        "resourceId", resourceId,
                        "succeedCount", succeedCount,
                        "failedCount", failedCount
                    )));
        }
    }

    @Override
    public boolean isHandling(Event event) {
        return event instanceof ImportEvent;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        ImportEvent importEvent = (ImportEvent) event;
        if (!importEvent.username().equals(username)) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> {
            synchronized (outbound) {
                outbound.emitNext(ImportWebSocketMessage.from(importEvent), Sinks.EmitFailureHandler.FAIL_FAST);
            }
        }).then();

    }
}