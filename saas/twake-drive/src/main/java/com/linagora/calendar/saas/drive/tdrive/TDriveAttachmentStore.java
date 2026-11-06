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

package com.linagora.calendar.saas.drive.tdrive;

import static com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.encode;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;

import com.google.common.base.Preconditions;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
import com.linagora.calendar.saas.drive.api.AttachmentUri;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.FileId;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.FileName;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.FolderId;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.PlatformFqdn;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.ShareCode;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.TDriveAccess;
import com.linagora.calendar.saas.drive.tdrive.TDriveApiClient.UploadContent;

import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class TDriveAttachmentStore implements AttachmentStore {
    private static final int MAX_FILE_NAME_BYTES = 255;

    private final TDriveConfiguration configuration;
    private final TDriveApiClient driveClient;
    private final TDriveInstanceResolver instanceResolver;

    @Inject
    public TDriveAttachmentStore(TDriveConfiguration configuration, TDriveInstanceResolver instanceResolver) {
        Preconditions.checkArgument(configuration.enabled(), "TDrive integration is disabled");
        this.configuration = configuration;
        this.driveClient = new TDriveApiClient(configuration);
        this.instanceResolver = instanceResolver;
    }

    @Override
    public Mono<AttachmentUri> upload(Attachment attachment, Username username) {
        return Mono.defer(() -> {
            validate(attachment, username);
            return instanceResolver.resolve(username)
                .map(hostname -> PlatformFqdn.withinDomain(hostname, configuration.instanceDomain()))
                .flatMap(fqdn -> uploadToInstance(fqdn, attachment))
                .onErrorMap(AttachmentStorageException.class, error -> new AttachmentStorageException(error.reason(), error.status(),
                    error.getMessage() + " for user " + username.asString(), error));
        });
    }

    private Mono<AttachmentUri> uploadToInstance(PlatformFqdn fqdn, Attachment attachment) {
        return driveClient.access(fqdn).flatMap(drive -> folder(drive)
            .flatMap(folderId -> uploadWithConflictRetry(drive, folderId, attachment))
            .flatMap(fileId -> driveClient.share(drive, fileId))
            .map(code -> publicUri(fqdn, code)));
    }

    private static void validate(Attachment attachment, Username user) {
        if (user == null || attachment == null) {
            throw invalidAttachment();
        }
        validateHeaderValue(user.asString());
        validateAttachment(attachment);
    }

    private static void validateAttachment(Attachment attachment) {
        if (!TDriveConfiguration.validName(attachment.fileName())) {
            throw invalidAttachment();
        }
        if (attachment.content() == null || attachment.contentType() == null) {
            throw invalidAttachment();
        }
        validateHeaderValue(attachment.contentType().asString());
    }

    private static void validateHeaderValue(String value) {
        if (StringUtils.isBlank(value) || value.chars().anyMatch(Character::isISOControl)) {
            throw invalidAttachment();
        }
    }

    private static AttachmentStorageException invalidAttachment() {
        return new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Attachment requires a user, file name, content type and binary content");
    }

    private Mono<FolderId> folder(TDriveAccess drive) {
        return driveClient.lookupFolder(drive)
            .onErrorResume(error -> hasStatus(error, HttpResponseStatus.NOT_FOUND), error -> driveClient.createFolder(drive)
                // Two simultaneous first uploads may race; only a folder conflict is safe to recover by lookup.
                .onErrorResume(conflict -> hasStatus(conflict, HttpResponseStatus.CONFLICT),
                    conflict -> driveClient.lookupFolder(drive)));
    }

    private Mono<FileId> uploadWithConflictRetry(TDriveAccess drive, FolderId folderId, Attachment attachment) {
        FileName name = new FileName(attachment.fileName());
        // Folder lookup completes on an I/O thread; hashing large files must run on a worker.
        return Mono.fromCallable(() -> UploadContent.from(attachment))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(content -> driveClient.createFile(drive, folderId, name, content)
                .onErrorResume(error -> hasStatus(error, HttpResponseStatus.CONFLICT),
                    error -> driveClient.createFile(drive, folderId, conflictFileName(name), content)));
    }

    private static boolean hasStatus(Throwable error, HttpResponseStatus status) {
        return error instanceof AttachmentStorageException failure && failure.status() == status.code();
    }

    private static FileName conflictFileName(FileName original) {
        String name = original.value();
        String suffix = "-" + UUID.randomUUID();
        int lastDot = name.lastIndexOf('.');
        String extension = lastDot > 0 ? name.substring(lastDot) : "";
        int stemBudget = MAX_FILE_NAME_BYTES - suffix.length() - extension.getBytes(StandardCharsets.UTF_8).length;
        if (stemBudget <= 0) {
            extension = "";
            stemBudget = MAX_FILE_NAME_BYTES - suffix.length();
        }

        ByteBuffer stem = ByteBuffer.allocate(stemBudget);
        // The encoder stops at a character boundary when the buffer is full.
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(StringUtils.removeEnd(name, extension)), stem, true);
        return new FileName(StandardCharsets.UTF_8.decode(stem.flip()) + suffix + extension);
    }

    private AttachmentUri publicUri(PlatformFqdn fqdn, ShareCode code) {
        String localPart = fqdn.value().substring(0, fqdn.value().length() - configuration.instanceDomain().length() - 1);
        String driveFqdn = localPart + "-drive." + configuration.instanceDomain();
        return new AttachmentUri(URI.create(configuration.publicUrlTemplate().replace("{fqdn}", fqdn.value())
            .replace("{driveFqdn}", driveFqdn).replace("{sharecode}", encode(code.value()))));
    }
}
