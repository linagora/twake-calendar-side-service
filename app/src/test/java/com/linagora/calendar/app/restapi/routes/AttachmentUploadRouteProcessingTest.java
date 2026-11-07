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

package com.linagora.calendar.app.restapi.routes;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.james.core.Username;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.restapi.routes.AttachmentUploadRoute;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.api.AttachmentUri;
import com.linagora.calendar.storage.FileUploadConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

/** Exercises the HTTP processing boundary without booting the application or Docker dependencies. */
class AttachmentUploadRouteProcessingTest {
    private static final Username USER = Username.of("alice@example.com");
    private static final AttachmentUri URI_RESULT = new AttachmentUri(URI.create("https://drive.example.com/public?sharecode=code"));
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private DisposableServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void shouldAcceptBinaryAndAggregateItOffEventLoop() {
        byte[] binary = new byte[16 * 1024 * 1024];
        binary[0] = (byte) 255;
        binary[binary.length - 1] = 127;
        AtomicReference<byte[]> stored = new AtomicReference<>();
        AtomicReference<Boolean> storageCalledOnEventLoop = new AtomicReference<>();
        startServer((attachment, username) -> {
            stored.set(attachment.content());
            storageCalledOnEventLoop.set(Schedulers.isInNonBlockingThread());
            assertThat(username).isEqualTo(USER);
            return Mono.just(URI_RESULT);
        });

        Response response = upload(binary);

        assertThat(response.status()).isEqualTo(201);
        assertThatJson(response.body()).isEqualTo("""
            {"uri": "<uri>"}
            """.replace("<uri>", URI_RESULT.value().toString()));
        assertThat(stored.get()).containsExactly(binary);
        assertThat(storageCalledOnEventLoop.get()).isFalse();
    }

    @Test
    void shouldPreserveArbitraryBinaryBytes() {
        AtomicReference<byte[]> stored = new AtomicReference<>();
        startServer((attachment, username) -> {
            stored.set(attachment.content());
            return Mono.just(URI_RESULT);
        });

        Response response = upload(new byte[] {0, 1, 2, (byte) 255});

        assertThat(response.status()).isEqualTo(201);
        assertThat(stored.get()).containsExactly(0, 1, 2, (byte) 255);
    }

    static Stream<Arguments> storageFailures() {
        return Stream.of(
            Arguments.of(Reason.TIMEOUT, 504, Level.WARN),
            Arguments.of(Reason.UPSTREAM_FAILURE, 502, Level.ERROR),
            Arguments.of(Reason.INVALID_RESPONSE, 502, Level.ERROR));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void shouldLogStorageFailureWithContextWithoutSensitiveCause(Reason reason, int status, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(AttachmentUploadRoute.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AttachmentStorageException failure = new AttachmentStorageException(reason, 503,
                "TDrive file upload: request failed", new IllegalStateException("secret-token-and-payload"));
            startServer((attachment, username) -> Mono.error(failure));

            Response response = upload(new byte[] {97});

            assertThat(response.status()).isEqualTo(status);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(level);
                assertThat(event.getFormattedMessage())
                    .contains(USER.asString(), "TDrive file upload", "upstreamStatus=503", "requestId=")
                    .doesNotContain("secret-token-and-payload", "YQ==");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldNotLogInvalidAttachmentAsAnUpstreamFailure() {
        Logger logger = (Logger) LoggerFactory.getLogger(AttachmentUploadRoute.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AttachmentStorageException failure = new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Invalid attachment");
            startServer((attachment, username) -> Mono.error(failure));

            Response response = upload(new byte[] {97});

            assertThat(response.status()).isEqualTo(400);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void startServer(AttachmentStorageClient storage) {
        MailboxSession session = new MailboxSession(MailboxSession.SessionId.of(1L), USER, Optional.of(USER),
            List.of(), '.', MailboxSession.SessionType.User);
        Authenticator authenticator = mock(Authenticator.class);
        when(authenticator.authenticate(any())).thenReturn(Mono.just(session));

        AttachmentUploadRoute route = new AttachmentUploadRoute(authenticator, new RecordingMetricFactory(),
            Optional.of(storage), FileUploadConfiguration.DEFAULT);
        var upload = route.routes()
            .filter(candidate -> candidate.getEndpoint().getMethod().equals(HttpMethod.POST))
            .findFirst()
            .orElseThrow();

        server = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(upload.getAction()::handleRequest)
            .bindNow();
    }

    private Response upload(byte[] body) {
        return HttpClient.create()
            .host("127.0.0.1")
            .port(server.port())
            .headers(headers -> headers
                .set("Content-Type", "application/octet-stream")
                .set("X-Filename", "data.bin"))
            .post()
            .uri("/api/attachments")
            .send(ByteBufFlux.fromInbound(Mono.just(body)))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .map(value -> new Response(response.status().code(), value)))
            .block(TIMEOUT);
    }

    private record Response(int status, String body) { }
}
