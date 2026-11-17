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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.james.core.Username;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.DavConfiguration;
import com.linagora.calendar.storage.TechnicalTokenService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

    private DisposableServer sabreServer;
    private DisposableServer proxyServer;
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
                    .sendString(Mono.just(SABRE_ERROR_BODY), StandardCharsets.UTF_8)))
            .bindNow();

        DavProxy davProxy = new DavProxy(authenticator(), davConfiguration(),
            new RecordingMetricFactory(), mock(TechnicalTokenService.class));
        proxyServer = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(davProxy::forwardRequest)
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
}
