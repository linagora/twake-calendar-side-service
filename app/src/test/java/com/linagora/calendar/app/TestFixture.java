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

import static com.linagora.calendar.storage.eventsearch.CalendarSearchServiceContract.CALMLY_AWAIT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public interface TestFixture {

    static String awaitMessage(BlockingQueue<String> messages, Predicate<String> accept) {
        AtomicReference<String> matched = new AtomicReference<>();
        CALMLY_AWAIT
            .untilAsserted(() -> {
                String msg = messages.poll(2, SECONDS);
                assertThat(msg)
                    .as("Expected a websocket message but queue was empty")
                    .isNotNull();

                assertThat(msg)
                    .as("Received websocket message does not match condition")
                    .matches(accept);
                matched.set(msg);
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
