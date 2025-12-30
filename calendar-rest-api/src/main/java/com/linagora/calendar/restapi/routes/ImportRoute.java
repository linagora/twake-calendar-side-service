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

import static com.linagora.calendar.restapi.RestApiConstants.JSON_HEADER;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportCommand;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportType;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.UploadedFileDAO;
import com.linagora.calendar.storage.model.ImportId;
import com.linagora.calendar.storage.model.UploadedMimeType;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ImportRoute extends CalendarRoute {

    public static class ImportRequest {
        public String fileId;
        public String target;

        @JsonProperty(value = "fileId", required = true)
        public String getFileId() {
            return fileId;
        }

        @JsonProperty(value = "target", required = true)
        public String getTarget() {
            return target;
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportRoute.class);
    private static final String IMPORT_ID_PROPERTY = "importId";

    private final UploadedFileDAO fileDAO;
    private final ImportProcessor importProcessor;

    @Inject
    public ImportRoute(Authenticator authenticator,
                       MetricFactory metricFactory,
                       UploadedFileDAO fileDAO,
                       ImportProcessor importProcessor) {
        super(authenticator, metricFactory);
        this.fileDAO = fileDAO;
        this.importProcessor = importProcessor;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/import");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asInputStream()
            .map(Throwing.function(inputStream -> OBJECT_MAPPER.readValue(inputStream, ImportRequest.class)))
            .flatMap(importRequest -> handleImport(importRequest, session))
            .flatMap(importId -> response.status(HttpResponseStatus.ACCEPTED)
                .headers(JSON_HEADER)
                .sendString(Mono.just(serializeImportResponse(importId)))
                .then());
    }

    private Mono<ImportId> handleImport(ImportRequest request, MailboxSession session) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.fileId), "fileId must be present");
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.target), "target must be present");

        String[] parts = request.target.split("/");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid target path");
        }
        String baseId = parts[2];
        String davCollectionId = parts[3].replace(".json", "");

        return fileDAO.getFile(session.getUser(), new OpenPaaSId(request.fileId))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Uploaded file not found")))
            .map(uploadedFile -> ImportCommand.create(getImportType(uploadedFile.uploadedMimeType()), uploadedFile, baseId, davCollectionId))
            .doOnSuccess(importCommand -> importProcessor.process(importCommand, session)
                .doOnSuccess(unused -> LOGGER.info("Import of {} with fileId {} completed successfully", importCommand.importType().name(), request.fileId))
                .doOnError(ex -> LOGGER.error("Error during import of {} with fileId {}", importCommand.importType().name(), request.fileId, ex))
                .subscribe())
            .map(ImportCommand::importId);
    }

    private ImportType getImportType(UploadedMimeType mimeType) {
        return switch (mimeType) {
            case TEXT_CALENDAR -> ImportProcessor.ImportType.ICS;
            case TEXT_VCARD -> ImportProcessor.ImportType.VCARD;
        };
    }

    private String serializeImportResponse(ImportId importId) {
        return "{\"" + IMPORT_ID_PROPERTY + "\":\"" + importId.value() + "\"}";
    }

}
