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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import com.linagora.calendar.restapi.routes.BoundedRequestBody.PayloadTooLargeException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

class BoundedRequestBodyTest {
    private static final int MAX_BYTES = 1024;
    private static final BoundedRequestBody BOUNDED_REQUEST_BODY = new BoundedRequestBody(MAX_BYTES);

    private DisposableServer server;

    @BeforeEach
    void setUp() {
        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> BOUNDED_REQUEST_BODY.read(request)
                .flatMap(body -> response.status(200).sendByteArray(Mono.just(body)).then())
                .onErrorResume(PayloadTooLargeException.class, error -> response.status(413).send()))
            .bindNow();
    }

    @AfterEach
    void tearDown() {
        server.disposeNow();
    }

    @Test
    void bodyWithinTheLimitShouldBeReadWhole() {
        byte[] body = "x".repeat(MAX_BYTES).getBytes(StandardCharsets.US_ASCII);

        Answer answer = post(Mono.just(Unpooled.wrappedBuffer(body)));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo(body);
    }

    @Test
    void chunkedBodyWithinTheLimitShouldBeReadWhole() {
        Answer answer = post(Flux.range(0, 4).map(index -> chunk(MAX_BYTES / 4)));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).hasSize(MAX_BYTES);
    }

    @Test
    void bodyWithAContentLengthOverTheLimitShouldBeRefused() {
        Answer answer = post(Mono.just(chunk(MAX_BYTES + 1)));

        assertThat(answer.status()).isEqualTo(413);
    }

    @Test
    void chunkedBodyOverTheLimitShouldBeRefused() {
        Answer answer = post(Flux.range(0, 5).map(index -> chunk(MAX_BYTES / 4)));

        assertThat(answer.status()).isEqualTo(413);
    }

    private static ByteBuf chunk(int size) {
        return Unpooled.wrappedBuffer("x".repeat(size).getBytes(StandardCharsets.US_ASCII));
    }

    private Answer post(Publisher<ByteBuf> body) {
        return HttpClient.create()
            .baseUrl("http://127.0.0.1:" + server.port())
            .post()
            .uri("/")
            .send(body)
            .responseSingle((response, content) -> content.asByteArray()
                .defaultIfEmpty(new byte[0])
                .map(bytes -> new Answer(response.status().code(), bytes)))
            .block();
    }

    private record Answer(int status, byte[] body) {
    }
}
