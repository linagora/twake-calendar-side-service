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

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentUri;
import com.linagora.calendar.storage.FileUploadConfiguration;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public final class AttachmentUploadRoute implements JMAPRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentUploadRoute.class);
    private final ObjectMapper mapper;
    private final Authenticator authenticator;
    private final MetricFactory metricFactory;
    private final Optional<AttachmentStorageClient> client;
    private final long maxFileSize;

    public record UploadResponse(String uri) { }

    @Inject
    public AttachmentUploadRoute(Authenticator authenticator, MetricFactory metricFactory,
                                 Optional<AttachmentStorageClient> client, FileUploadConfiguration configuration) {
        this.authenticator = authenticator;
        this.metricFactory = metricFactory;
        this.client = client;
        this.maxFileSize = configuration.userTotalLimit();
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("Upload size limit must be positive");
        }
        this.mapper = new ObjectMapper();
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(JMAPRoute.builder()
            .endpoint(new Endpoint(HttpMethod.POST, "/api/attachments"))
            .action((request, response) -> authenticator.authenticate(request)
                .flatMap(session -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(
                    getClass().getSimpleName(), handleRequest(request, response, session)))))
            .corsHeaders());
    }

    private Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        if (client.isEmpty()) {
            return response.status(HttpResponseStatus.SERVICE_UNAVAILABLE).send();
        }
        AttachmentStorageClient storage = client.orElseThrow();
        return Mono.fromCallable(() -> {
                // Percent encoding keeps UTF-8 filenames safe in ASCII HTTP headers.
                String encodedName = request.requestHeaders().get("X-Filename");
                if (encodedName == null) {
                    throw new IllegalArgumentException("X-Filename is required");
                }
                String name = URLDecoder.decode(encodedName.replace("+", "%2B"), StandardCharsets.UTF_8);
                String type = request.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE);
                validateMetadata(name, type);
                return name;
            })
            .flatMap(name -> readBody(request).map(body -> new Attachment(name,
                ContentType.of(request.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE)), body)))
            .flatMap(attachment -> storage.upload(attachment, session.getUser()))
            .map(AttachmentUploadRoute::toResponse)
            .flatMap(result -> response.status(HttpResponseStatus.CREATED)
                .header(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .sendString(Mono.fromCallable(() -> mapper.writeValueAsString(result)))
                .then())
            .onErrorResume(PayloadTooLargeException.class, error -> response.status(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE).send())
            .onErrorResume(AttachmentStorageException.class, error -> {
                // Client messages are sanitized; transport causes may contain sensitive URLs or credentials.
                switch (error.reason()) {
                    case TIMEOUT -> LOGGER.warn("TDrive attachment upload timed out for user {} (requestId={}, upstreamStatus={}): {}",
                        session.getUser().asString(), request.requestId(), error.status(), error.getMessage());
                    case INVALID_RESPONSE, UPSTREAM_FAILURE -> LOGGER.error("TDrive attachment upload failed for user {} (requestId={}, reason={}, upstreamStatus={}): {}",
                        session.getUser().asString(), request.requestId(), error.reason(), error.status(), error.getMessage());
                    case INVALID_ATTACHMENT -> { }
                }
                return response.status(storageFailureStatus(error)).send();
            });
    }

    private Mono<byte[]> readBody(HttpServerRequest request) {
        return Mono.defer(() -> {
            String contentLength = request.requestHeaders().get(HttpHeaderNames.CONTENT_LENGTH);
            if (contentLength != null) {
                validateRequestSize(Long.parseLong(contentLength));
            }
            // Count received bytes as well: chunked requests have no Content-Length.
            return request.receive().asByteArray()
                // Chunks are detached byte arrays: aggregate large bodies off the Netty event loop.
                .publishOn(Schedulers.boundedElastic(), 1)
                .reduceWith(ByteArrayOutputStream::new, (body, chunk) -> {
                    validateRequestSize((long) body.size() + chunk.length);
                    body.writeBytes(chunk);
                    return body;
                })
                .map(ByteArrayOutputStream::toByteArray);
        });
    }

    private void validateRequestSize(long size) {
        if (size > maxFileSize) {
            throw new PayloadTooLargeException();
        }
    }

    private static void validateMetadata(String name, String contentType) {
        if (StringUtils.isBlank(name) || StringUtils.isBlank(contentType)) {
            throw new IllegalArgumentException("X-Filename and Content-Type are required");
        }
        validateFilename(name);
        if (contentType.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("contentType must not contain control characters");
        }
    }

    private static void validateFilename(String name) {
        if (StringUtils.equalsAny(name, ".", "..") || StringUtils.containsAny(name, '/', '\\') || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("fileName must be a single file name");
        }
    }

    private static HttpResponseStatus storageFailureStatus(AttachmentStorageException error) {
        return switch (error.reason()) {
            case INVALID_ATTACHMENT -> HttpResponseStatus.BAD_REQUEST;
            case TIMEOUT -> HttpResponseStatus.GATEWAY_TIMEOUT;
            case INVALID_RESPONSE, UPSTREAM_FAILURE -> HttpResponseStatus.BAD_GATEWAY;
        };
    }

    private static final class PayloadTooLargeException extends RuntimeException {
    }

    private static UploadResponse toResponse(AttachmentUri uri) {
        return new UploadResponse(uri.value().toString());
    }
}
