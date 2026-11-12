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
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
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
import com.google.common.base.Preconditions;
import com.google.common.net.MediaType;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
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
    private static final String X_FILENAME_HEADER = "X-Filename";
    private static final int BODY_CHUNK_PREFETCH = 1;
    private final ObjectMapper mapper;
    private final Authenticator authenticator;
    private final MetricFactory metricFactory;
    private final AttachmentStore storage;
    private final long maxFileSize;

    public record UploadResponse(String uri) { }

    @Inject
    public AttachmentUploadRoute(Authenticator authenticator, MetricFactory metricFactory,
                                 AttachmentStore storage, FileUploadConfiguration configuration) {
        this.authenticator = authenticator;
        this.metricFactory = metricFactory;
        this.storage = storage;
        this.maxFileSize = configuration.userTotalLimit();
        Preconditions.checkArgument(maxFileSize > 0, "Upload size limit must be positive");
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
        return readAttachment(request)
            .flatMap(attachment -> storage.upload(attachment, session.getUser()))
            .map(AttachmentUploadRoute::toResponse)
            .flatMap(result -> sendUploadResponse(response, result))
            .onErrorResume(PayloadTooLargeException.class, error -> response.status(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE).send())
            .onErrorResume(AttachmentStorageException.class, error -> handleStorageFailure(request, response, session, error));
    }

    private Mono<Attachment> readAttachment(HttpServerRequest request) {
        return Mono.defer(() -> {
            String filename = readFilename(request);
            String contentType = request.requestHeaders().get(HttpHeaderNames.CONTENT_TYPE);
            validateMetadata(filename, contentType);
            return readBody(request).map(body -> new Attachment(filename, ContentType.of(contentType), body));
        });
    }

    private static String readFilename(HttpServerRequest request) {
        String encodedName = request.requestHeaders().get(X_FILENAME_HEADER);
        Preconditions.checkArgument(encodedName != null, "%s is required", X_FILENAME_HEADER);
        // Preserve literal plus signs while decoding percent-encoded UTF-8 names.
        return URLDecoder.decode(encodedName.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private Mono<Void> sendUploadResponse(HttpServerResponse response, UploadResponse result) {
        return response.status(HttpResponseStatus.CREATED)
            .header(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
            .sendString(Mono.fromCallable(() -> mapper.writeValueAsString(result)))
            .then();
    }

    private Mono<Void> handleStorageFailure(HttpServerRequest request, HttpServerResponse response,
                                            MailboxSession session, AttachmentStorageException error) {
        // Client messages are sanitized; transport causes may contain sensitive URLs or credentials.
        switch (error.reason()) {
            case TIMEOUT -> LOGGER.warn("TDrive attachment upload timed out for user {} (requestId={}, upstreamStatus={}): {}",
                session.getUser().asString(), request.requestId(), error.status(), error.getMessage());
            case INVALID_RESPONSE, UPSTREAM_FAILURE -> LOGGER.error("TDrive attachment upload failed for user {} (requestId={}, reason={}, upstreamStatus={}): {}",
                session.getUser().asString(), request.requestId(), error.reason(), error.status(), error.getMessage());
            case INVALID_ATTACHMENT -> { }
        }
        return response.status(storageFailureStatus(error)).send();
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
                .publishOn(Schedulers.boundedElastic(), BODY_CHUNK_PREFETCH)
                .reduceWith(ByteArrayOutputStream::new, this::appendChunk)
                .map(ByteArrayOutputStream::toByteArray);
        });
    }

    private ByteArrayOutputStream appendChunk(ByteArrayOutputStream body, byte[] chunk) {
        validateRequestSize((long) body.size() + chunk.length);
        body.writeBytes(chunk);
        return body;
    }

    private void validateRequestSize(long size) {
        if (size > maxFileSize) {
            throw new PayloadTooLargeException();
        }
    }

    private static void validateMetadata(String name, String contentType) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name) && StringUtils.isNotBlank(contentType),
            "%s and Content-Type are required", X_FILENAME_HEADER);
        validateFilename(name);
        Preconditions.checkArgument(contentType.chars().noneMatch(Character::isISOControl),
            "contentType must not contain control characters");
    }

    private static void validateFilename(String name) {
        Preconditions.checkArgument(!Strings.CS.equalsAny(name, ".", "..") && !StringUtils.containsAny(name, '/', '\\')
            && name.chars().noneMatch(Character::isISOControl), "fileName must be a single file name");
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
