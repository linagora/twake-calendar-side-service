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

import java.io.IOException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.fileupload.util.LimitedInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.james.blob.api.ObjectStoreIOException;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.ReactorUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.FileUploadConfiguration;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.UploadedFileDAO;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadableMimeType;
import com.linagora.calendar.storage.model.UploadedFile;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class FileUploadRoute extends CalendarRoute {

    public static class UploadResponse {
        private final OpenPaaSId id;

        public UploadResponse(OpenPaaSId id) {
            this.id = id;
        }

        @JsonProperty("_id")
        public String getId() {
            return id.value();
        }
    }

    static class AccumulatedSize {
        private long value;

        public AccumulatedSize() {
            this.value = 0;
        }

        public long getValue() {
            return value;
        }

        public void add(long size) {
            value += size;
        }
    }

    public static final String NAME_PARAM = "name";
    public static final String SIZE_PARAM = "size";
    public static final String MIME_TYPE_PARAM = "mimetype";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UploadedFileDAO fileDAO;
    private final Clock clock;
    private final Long userTotalLimitInBytes;

    @Inject
    public FileUploadRoute(Authenticator authenticator, MetricFactory metricFactory, UploadedFileDAO fileDAO, Clock clock, FileUploadConfiguration configuration) {
        super(authenticator, metricFactory);
        this.fileDAO = fileDAO;
        this.clock = clock;
        this.userTotalLimitInBytes = configuration.userTotalLimit();
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/files");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
        String fileName = extractFileName(queryStringDecoder);
        long fileSize = extractFileSize(queryStringDecoder);
        UploadableMimeType mimeType = extractMimeType(queryStringDecoder);

        if (fileSize > userTotalLimitInBytes) {
            throw new IllegalArgumentException("File size exceeds user total upload limit");
        }

        return ensureSpaceForUpload(session.getUser(), fileSize)
            .then(getUploadedData(request, fileSize))
            .flatMap(data -> fileDAO.saveFile(session.getUser(), new Upload(fileName, mimeType, clock.instant().truncatedTo(ChronoUnit.MILLIS), (long) data.length, data)))
            .map(UploadResponse::new)
            .map(this::toJsonBytes)
            .flatMap(bytes -> response.status(201)
                .header("Content-Type", "application/json;charset=utf-8")
                .sendByteArray(Mono.just(bytes))
                .then());
    }

    private Mono<byte[]> getUploadedData(HttpServerRequest request, long fileSize) {
        return Mono.fromCallable(() -> ReactorUtils.toInputStream(request.receive().asByteBuffer()))
            .map(inputStream -> new LimitedInputStream(inputStream, fileSize) {
                @Override
                protected void raiseError(long pSizeMax, long pCount) {
                    throw new IllegalArgumentException("Real size is greater than declared size");
                }
            }).flatMap(inputStream -> Mono.fromCallable(() -> getBytes(inputStream))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private byte[] getBytes(LimitedInputStream inputStream) {
        try {
            return IOUtils.toByteArray(inputStream);
        } catch (IOException e) {
            throw new ObjectStoreIOException("IOException occurred", e);
        }
    }

    private byte[] toJsonBytes(UploadResponse uploadResponse) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(uploadResponse);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response", e);
        }
    }

    private String extractFileName(QueryStringDecoder queryStringDecoder) {
        return queryStringDecoder.parameters().getOrDefault(NAME_PARAM, List.of())
            .stream()
            .findAny()
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("Missing name param"));
    }

    private long extractFileSize(QueryStringDecoder queryStringDecoder) {
        String size = queryStringDecoder.parameters().getOrDefault(SIZE_PARAM, List.of())
            .stream()
            .findAny()
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("Missing size param"));

        try {
            return Long.parseLong(size);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid size param: " + size);
        }
    }

    private UploadableMimeType extractMimeType(QueryStringDecoder queryStringDecoder) {
        return queryStringDecoder.parameters().getOrDefault(MIME_TYPE_PARAM, List.of())
            .stream()
            .findAny()
            .filter(s -> !s.isBlank())
            .map(UploadableMimeType::fromType)
            .orElseThrow(() -> new IllegalArgumentException("Missing mimetype param"));
    }

    private Mono<Void> ensureSpaceForUpload(Username username, long incomingFileSizeInBytes) {
        return fileDAO.listFiles(username)
            .collectList()
            .flatMap(files -> {
                long totalUsed = files.stream().mapToLong(UploadedFile::size).sum();
                long required = incomingFileSizeInBytes - (userTotalLimitInBytes - totalUsed);

                if (required <= 0) {
                    return Mono.empty();
                }

                List<UploadedFile> sorted = files.stream()
                    .sorted(Comparator.comparing(UploadedFile::created))
                    .toList();

                List<UploadedFile> toDelete = getDeletedList(sorted, required);

                return Flux.fromIterable(toDelete)
                    .flatMap(file -> fileDAO.deleteFile(username, file.id()))
                    .then();
            });
    }

    private List<UploadedFile> getDeletedList(List<UploadedFile> sorted, long required) {
        AccumulatedSize accumulatedSize = new AccumulatedSize();

        return sorted.stream()
            .takeWhile(file -> {
                if (accumulatedSize.getValue() >= required) {
                    return false;
                }
                accumulatedSize.add(file.size());
                return true;
            }).toList();
    }
}
