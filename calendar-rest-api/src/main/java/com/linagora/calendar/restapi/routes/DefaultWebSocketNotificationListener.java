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

import java.util.List;
import java.util.Map;

import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.storage.CalendarListChangedEvent;
import com.linagora.calendar.storage.CalendarListChangedEvent.ChangeType;
import com.linagora.calendar.storage.CalendarURL;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public record DefaultWebSocketNotificationListener(Sinks.Many<WebsocketRoute.WebsocketMessage> outbound) implements EventListener.ReactiveEventListener {

    @Override
    public boolean isHandling(Event event) {
        return event instanceof CalendarListChangedEvent;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        if (event instanceof CalendarListChangedEvent calendarListChangedEvent) {
            return handleCalendarListChange(calendarListChangedEvent);
        }
        return Mono.empty();
    }

    private Mono<Void> handleCalendarListChange(CalendarListChangedEvent event) {
        return Mono.fromRunnable(() -> emit(CalendarListChangeMessage.of(event.calendarURL(), event.changeType())))
            .then();
    }

    private void emit(WebsocketRoute.WebsocketMessage message) {
        synchronized (outbound) {
            outbound.emitNext(message, Sinks.EmitFailureHandler.FAIL_FAST);
        }
    }

    public record CalendarListChangeMessage(Map<ChangeType, List<CalendarURL>> changes) implements WebsocketRoute.WebsocketMessage {

        public static CalendarListChangeMessage of(CalendarURL calendarURL, ChangeType changeType) {
            return new CalendarListChangeMessage(Map.of(changeType, List.of(calendarURL)));
        }

        static final String CALENDAR_LIST_PROPERTY = "calendarList";

        @Override
        public WebSocketFrame asWebSocketFrame() throws JsonProcessingException {
            return new TextWebSocketFrame(serialize());
        }

        public String serialize() throws JsonProcessingException {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode calendarListNode = root.putObject(CALENDAR_LIST_PROPERTY);

            changes.forEach((changeType, calendarURLs) -> {
                if (!calendarURLs.isEmpty()) {
                    calendarListNode.set(changeType.name().toLowerCase(),
                        MAPPER.valueToTree(serializeCalendarURLs(calendarURLs)));
                }
            });

            return MAPPER.writeValueAsString(root);
        }

        private List<String> serializeCalendarURLs(List<CalendarURL> calendarURLs) {
            return calendarURLs.stream()
                .map(url -> url.asUri().toASCIIString())
                .toList();
        }
    }
}
