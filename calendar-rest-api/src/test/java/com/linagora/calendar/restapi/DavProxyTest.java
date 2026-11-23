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

package com.linagora.calendar.restapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.TechnicalTokenService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

class DavProxyTest {
    private static final Username USER = Username.of("bob@domain.tld");
    private static final HttpMethod REPORT = HttpMethod.valueOf("REPORT");
    private static final String MULTISTATUS_CONTENT_TYPE = "application/xml; charset=utf-8";
    private static final List<String> REPORT_BODY_CHUNKS = IntStream.range(0, 200)
        .mapToObj(index -> "<d:response><d:href>/calendars/bob/events/" + index + ".ics</d:href>"
            + "<d:propstat><d:prop><cal:calendar-data>BEGIN:VCALENDAR é " + "x".repeat(1000)
            + "</cal:calendar-data></d:prop></d:propstat></d:response>\n")
        .toList();
    private static final String REPORT_BODY = String.join("", REPORT_BODY_CHUNKS);
    private static final String SABRE_ERROR_BODY = "<s:exception>Sabre\\DAV\\Exception</s:exception>";

    private static final String RECEIVED_CONTENT_LENGTH_HEADER = "X-Received-Content-Length";
    private static final String ICS_BODY = "BEGIN:VCALENDAR\r\n" + "X-PADDING:é\r\n".repeat(20_000) + "END:VCALENDAR\r\n";
    private static final Duration UNAUTHENTICATED_ANSWER_TIMEOUT = Duration.ofSeconds(10);

    private DisposableServer sabreServer;
    private DisposableServer proxyServer;
    private DisposableServer unauthenticatedProxyServer;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        sabreServer = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes
                .route(request -> request.uri().startsWith("/calendars"), (request, response) -> response
                    .status(207)
                    .header(HttpHeaderNames.CONTENT_TYPE, MULTISTATUS_CONTENT_TYPE)
                    .sendString(Flux.fromIterable(REPORT_BODY_CHUNKS), StandardCharsets.UTF_8))
                .route(request -> request.uri().startsWith("/error"), (request, response) -> response
                    .status(500)
                    .sendString(Mono.just(SABRE_ERROR_BODY), StandardCharsets.UTF_8))
                .route(request -> request.uri().startsWith("/echo"), (request, response) -> response
                    .status(201)
                    .header(RECEIVED_CONTENT_LENGTH_HEADER, Optional.ofNullable(request.requestHeaders().get(HttpHeaderNames.CONTENT_LENGTH)).orElse("none"))
                    .send(request.receive().retain())))
            .bindNow();

        DavProxy davProxy = new DavProxy(authenticator(), davConfiguration(),
            new RecordingMetricFactory(), mock(TechnicalTokenService.class));
        proxyServer = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(davProxy::forwardRequest)
            .bindNow();

        DavProxy unauthenticatedDavProxy = new DavProxy(rejectingAuthenticator(), davConfiguration(),
            new RecordingMetricFactory(), mock(TechnicalTokenService.class));
        unauthenticatedProxyServer = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> unauthenticatedDavProxy.forwardRequest(request, response)
                .onErrorResume(UnauthorizedException.class, error -> response.status(401).send()))
            .bindNow();

        logger = (Logger) LoggerFactory.getLogger(DavProxy.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        proxyServer.disposeNow();
        unauthenticatedProxyServer.disposeNow();
        sabreServer.disposeNow();
    }

    @Test
    void reportBodyShouldBeByteIdenticalThroughTheProxy() {
        ProxiedResponse response = callProxy(REPORT, "/dav/calendars/bob/events");

        assertThat(response.status()).isEqualTo(207);
        assertThat(response.contentType()).isEqualTo(MULTISTATUS_CONTENT_TYPE);
        assertThat(response.body()).isEqualTo(REPORT_BODY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void successfulResponseShouldNotBeLoggedAsSabreError() {
        callProxy(REPORT, "/dav/calendars/bob/events");

        assertThat(errorLogs()).isEmpty();
    }

    @Test
    void sabreServerErrorShouldBeForwarded() {
        ProxiedResponse response = callProxy(HttpMethod.GET, "/dav/error");

        assertThat(response.status()).isEqualTo(500);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(SABRE_ERROR_BODY);
    }

    @Test
    void sabreServerErrorShouldBeLoggedWithItsBody() {
        callProxy(HttpMethod.GET, "/dav/error");

        assertThat(errorLogs())
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Sabre server error upon DAV request");
                assertThat(event.getMDCPropertyMap())
                    .containsEntry("statusCode", "500")
                    .containsEntry("url", "/dav/error")
                    .containsEntry("response", SABRE_ERROR_BODY);
            });
    }

    @Test
    void putBodyWithContentLengthShouldBeForwardedByteIdentical() {
        byte[] body = ICS_BODY.getBytes(StandardCharsets.UTF_8);

        ProxiedPut put = putThroughProxy(Mono.just(Unpooled.wrappedBuffer(body)));

        assertThat(put.status()).isEqualTo(201);
        assertThat(put.receivedContentLength()).isEqualTo(Integer.toString(body.length));
        assertThat(put.body()).isEqualTo(body);
    }

    @Test
    void chunkedPutBodyShouldBeForwardedByteIdentical() {
        byte[] body = ICS_BODY.getBytes(StandardCharsets.UTF_8);
        int chunkSize = body.length / 10 + 1;
        Flux<ByteBuf> chunks = Flux.range(0, 10)
            .map(index -> Unpooled.wrappedBuffer(body, index * chunkSize, Math.min(chunkSize, body.length - index * chunkSize)));

        ProxiedPut put = putThroughProxy(chunks);

        assertThat(put.status()).isEqualTo(201);
        assertThat(put.body()).isEqualTo(body);
    }

    @Test
    void unauthenticatedPutShouldBeRejectedBeforeItsDeclaredBodyIsReceived() throws Exception {
        String statusLine = sendUnauthenticatedPutStart("Content-Length: 10000000000", "x".repeat(1024));

        assertThat(statusLine).startsWith("HTTP/1.1 401");
    }

    @Test
    void unauthenticatedChunkedPutShouldBeRejectedBeforeItsBodyEnds() throws Exception {
        String statusLine = sendUnauthenticatedPutStart("Transfer-Encoding: chunked", "400\r\n" + "x".repeat(1024) + "\r\n");

        assertThat(statusLine).startsWith("HTTP/1.1 401");
    }

    /**
     * Sends the headers and the first bytes of a PUT whose body never ends, then reads the status line.
     */
    private String sendUnauthenticatedPutStart(String bodyFramingHeader, String bodyStart) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", unauthenticatedProxyServer.port())) {
            socket.setSoTimeout((int) UNAUTHENTICATED_ANSWER_TIMEOUT.toMillis());
            OutputStream output = socket.getOutputStream();
            output.write(("PUT /dav/calendars/bob/events/1.ics HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + bodyFramingHeader + "\r\n"
                + "\r\n"
                + bodyStart).getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    private ProxiedPut putThroughProxy(Publisher<ByteBuf> body) {
        return HttpClient.create()
            .baseUrl("http://127.0.0.1:" + proxyServer.port())
            .put()
            .uri("/dav/echo/bob/1.ics")
            .send(body)
            .responseSingle((response, content) -> content.asByteArray()
                .defaultIfEmpty(new byte[0])
                .map(bytes -> new ProxiedPut(response.status().code(),
                    response.responseHeaders().get(RECEIVED_CONTENT_LENGTH_HEADER), bytes)))
            .block();
    }

    private List<ILoggingEvent> errorLogs() {
        return appender.list.stream()
            .filter(event -> event.getLevel().equals(Level.ERROR))
            .collect(Collectors.toList());
    }

    private ProxiedResponse callProxy(HttpMethod method, String uri) {
        return HttpClient.create()
            .baseUrl("http://127.0.0.1:" + proxyServer.port())
            .request(method)
            .uri(uri)
            .responseSingle((response, body) -> body.asByteArray()
                .defaultIfEmpty(new byte[0])
                .map(bytes -> new ProxiedResponse(response.status().code(),
                    response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE), bytes)))
            .block();
    }

    private Authenticator authenticator() {
        MailboxSession session = new MailboxSession(MailboxSession.SessionId.of(1L), USER, Optional.of(USER),
            List.of(), '.', MailboxSession.SessionType.User);
        Authenticator authenticator = mock(Authenticator.class);
        when(authenticator.authenticate(any())).thenReturn(Mono.just(session));
        return authenticator;
    }

    private Authenticator rejectingAuthenticator() {
        Authenticator authenticator = mock(Authenticator.class);
        when(authenticator.authenticate(any())).thenReturn(Mono.error(new UnauthorizedException("No credentials")));
        return authenticator;
    }

    private DavConfiguration davConfiguration() {
        return new DavConfiguration(
            new UsernamePasswordCredentials("admin", "secret"),
            URI.create("http://127.0.0.1:" + sabreServer.port()),
            Optional.of(false),
            Optional.empty(),
            Optional.empty());
    }

    private record ProxiedResponse(int status, String contentType, byte[] body) {
    }

    private record ProxiedPut(int status, String receivedContentLength, byte[] body) {
    }
}
