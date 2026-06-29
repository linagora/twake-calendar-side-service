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

package com.linagora.calendar.app;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public interface TestFixture {

    // WebSocket notifications driven by the DAV -> RabbitMQ -> AMQP consumer -> event bus -> WebSocket
    // pipeline can take a while to be delivered under CI load: be generous on the overall budget.
    ConditionFactory MESSAGE_AWAIT = Awaitility.with()
        .pollInterval(Duration.ofMillis(100))
        .and().pollDelay(Duration.ZERO)
        .await()
        .atMost(Duration.ofSeconds(30));

    static String awaitMessage(BlockingQueue<String> messages, Predicate<String> accept) {
        AtomicReference<String> matched = new AtomicReference<>();
        MESSAGE_AWAIT
            .untilAsserted(() -> {
                String head = messages.poll(2, SECONDS);
                assertThat(head)
                    .as("Expected a websocket message but queue was empty")
                    .isNotNull();

                List<String> received = new ArrayList<>();
                received.add(head);
                messages.drainTo(received);

                Optional<String> match = received.stream()
                    .filter(accept)
                    .findFirst();

                // Put back the messages we are not interested in so a subsequent awaitMessage call can
                // still observe them. Without this, a notification arriving out of order would be silently
                // dropped here and the later assertion expecting it would time out (flaky test).
                match.ifPresent(received::remove);
                messages.addAll(received);

                assertThat(match)
                    .as("No websocket message matched the expected condition. Received: %s", received)
                    .isPresent();
                matched.set(match.get());
            });

        return matched.get();
    }

    static WebSocket connectWebSocket(int socketPort, String ticket, BlockingQueue<String> messages) {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        Request wsRequest = new Request.Builder()
            .url("ws://localhost:" + socketPort + "/ws?ticket=" + ticket)
            .build();
        WebSocket webSocket = client.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                messages.offer(text);
            }
        });

        // warm up
        awaitMessage(messages, msg -> msg.contains("calendarListRegistered"));
        return webSocket;
    }
}
