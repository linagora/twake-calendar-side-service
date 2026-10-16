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

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.CalendarAccess;
import com.linagora.calendar.restapi.ForbiddenException;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportCommand;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportType;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
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

    record ImportTarget(ImportType importType, OpenPaaSId baseId, String collectionId) {
        private static final Pattern TARGET_PATTERN = Pattern.compile(
            "^/(" + CalendarURL.CALENDAR_SEGMENT + "|" + AddressBookURL.ADDRESS_BOOKS_SEGMENT + ")/([\\w-]+)/([\\w-]+)(?:\\.json)?$");

        static ImportTarget parse(String target) {
            Matcher matcher = TARGET_PATTERN.matcher(target);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid target path");
            }
            return new ImportTarget(importTypeOf(matcher.group(1)), new OpenPaaSId(matcher.group(2)), matcher.group(3));
        }

        private static ImportType importTypeOf(String collectionSegment) {
            return CalendarURL.CALENDAR_SEGMENT.equals(collectionSegment) ? ImportType.ICS : ImportType.VCARD;
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportRoute.class);
    private static final String IMPORT_ID_PROPERTY = "importId";

    private final UploadedFileDAO fileDAO;
    private final ImportProcessor importProcessor;
    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    public ImportRoute(Authenticator authenticator,
                       MetricFactory metricFactory,
                       UploadedFileDAO fileDAO,
                       ImportProcessor importProcessor,
                       CalDavClient calDavClient,
                       OpenPaaSUserDAO userDAO) {
        super(authenticator, metricFactory);
        this.fileDAO = fileDAO;
        this.importProcessor = importProcessor;
        this.calDavClient = calDavClient;
        this.userDAO = userDAO;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/import");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asInputStream()
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Request body must not be empty")))
            .map(this::parseRequest)
            .flatMap(importRequest -> handleImport(importRequest, session))
            .flatMap(importId -> response.status(HttpResponseStatus.ACCEPTED)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> serializeImportResponse(importId)))
                .then());
    }

    private ImportRequest parseRequest(InputStream body) {
        try {
            return OBJECT_MAPPER.readValue(body, ImportRequest.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
    }

    private Mono<ImportId> handleImport(ImportRequest request, MailboxSession session) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.fileId), "fileId must be present");
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.target), "target must be present");

        ImportTarget target = ImportTarget.parse(request.target);

        return fileDAO.getFile(session.getUser(), new OpenPaaSId(request.fileId))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Uploaded file not found")))
            .map(uploadedFile -> ImportCommand.create(getImportType(uploadedFile.uploadedMimeType()), uploadedFile, target.baseId().value(), target.collectionId()))
            .filter(importCommand -> importCommand.importType() == target.importType())
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Uploaded file type does not match target")))
            .flatMap(importCommand -> checkWriteAccess(importCommand, session).thenReturn(importCommand))
            .doOnSuccess(importCommand -> importProcessor.process(importCommand, session)
                .doOnSuccess(unused -> LOGGER.info("Import of {} with fileId {} completed successfully", importCommand.importType().name(), request.fileId))
                .doOnError(ex -> LOGGER.error("Error during import of {} with fileId {}", importCommand.importType().name(), request.fileId, ex))
                .subscribe())
            .map(ImportCommand::importId);
    }

    private Mono<Void> checkWriteAccess(ImportCommand importCommand, MailboxSession session) {
        return switch (importCommand.importType()) {
            case ICS -> checkCalendarWriteAccess(new CalendarURL(importCommand.baseId(), new OpenPaaSId(importCommand.resourceId())), session);
            case VCARD -> checkAddressBookHomeOwnership(importCommand.baseId(), session);
        };
    }

    private Mono<Void> checkCalendarWriteAccess(CalendarURL calendarURL, MailboxSession session) {
        return calDavClient.resolveCalendarAccess(session.getUser(), calendarURL)
            .filter(CalendarAccess.WRITABLE::equals)
            .switchIfEmpty(Mono.error(() -> new ForbiddenException("User does not have write access to calendar: " + calendarURL.asUri())))
            .then();
    }

    private Mono<Void> checkAddressBookHomeOwnership(OpenPaaSId baseId, MailboxSession session) {
        return userDAO.retrieve(session.getUser())
            .map(OpenPaaSUser::id)
            .filter(baseId::equals)
            .switchIfEmpty(Mono.error(() -> new ForbiddenException("User does not own address book home: " + baseId.value())))
            .then();
    }

    private ImportType getImportType(UploadedMimeType mimeType) {
        return switch (mimeType) {
            case TEXT_CALENDAR -> ImportProcessor.ImportType.ICS;
            case TEXT_VCARD -> ImportProcessor.ImportType.VCARD;
        };
    }

    private byte[] serializeImportResponse(ImportId importId) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsBytes(Map.of(IMPORT_ID_PROPERTY, importId.value()));
    }

}
