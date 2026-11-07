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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.api.AttachmentUri;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.configuration.TDriveEndpointValidation;
import com.linagora.calendar.saas.drive.tdrive.TDriveHttpClient.Request;
import com.linagora.calendar.saas.drive.tdrive.TDriveHttpClient.RequestBody;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Attributes;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Document;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Resource;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Token;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class TDriveAttachmentStorageClient implements AttachmentStorageClient {
    private record TDriveAccess(URI baseUrl, String serviceToken) { }

    private static final String JSON_API = "application/vnd.api+json";
    private static final int MAX_FILE_NAME_BYTES = 255;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] TOKEN_REQUEST = """
        {"audience":"cli","scope":"io.cozy.files io.cozy.permissions"}
        """.getBytes(StandardCharsets.UTF_8);
    private final TDriveConfiguration configuration;
    private final TDriveHttpClient http;
    private final TDriveInstanceResolver instanceResolver;

    @Inject
    public TDriveAttachmentStorageClient(TDriveConfiguration configuration, TDriveInstanceResolver instanceResolver) {
        Preconditions.checkArgument(configuration.enabled(), "TDrive integration is disabled");
        this.configuration = configuration;
        this.http = new TDriveHttpClient(configuration.timeout(), configuration.trustAllSslCerts());
        this.instanceResolver = instanceResolver;
    }

    @Override
    public Mono<AttachmentUri> upload(Attachment attachment, Username username) {
        return Mono.defer(() -> {
            validate(attachment, username);
            return instanceResolver.resolve(username)
                .flatMap(fqdn -> uploadToInstance(fqdn, attachment))
                .onErrorMap(AttachmentStorageException.class, error -> new AttachmentStorageException(error.reason(), error.status(),
                    error.getMessage() + " for user " + username.asString(), error));
        });
    }

    private Mono<AttachmentUri> uploadToInstance(String fqdn, Attachment attachment) {
        if (!TDriveConfiguration.validHostname(fqdn)
            || !fqdn.toLowerCase(Locale.ROOT).endsWith("." + configuration.instanceDomain().toLowerCase(Locale.ROOT))) {
            return Mono.error(new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Resolved Drive instance must be a hostname within the configured domain"));
        }
        return serviceToken(fqdn).flatMap(serviceToken -> {
            URI baseUrl = URI.create(StringUtils.stripEnd(configuration.driveUrlTemplate().replace("{fqdn}", fqdn), "/"));
            TDriveAccess drive = new TDriveAccess(baseUrl, serviceToken);
            return folder(drive)
                .flatMap(folderId -> upload(drive, folderId, attachment))
                .flatMap(fileId -> share(drive, fileId))
                .map(code -> publicUri(fqdn, code));
        });
    }

    private Mono<String> serviceToken(String fqdn) {
        return http.request(new Request(HttpMethod.POST, configuration.tokenUrlTemplate().replace("{fqdn}", fqdn),
                configuration.partnerToken(), HttpHeaderValues.APPLICATION_JSON.toString()), RequestBody.json(TOKEN_REQUEST),
                Token.class, "TDrive service token")
            .map(result -> {
                if (!TDriveEndpointValidation.validBearerToken(result.token())) {
                    throw new AttachmentStorageException(Reason.INVALID_RESPONSE, "TDrive service token: missing token");
                }
                return result.token();
            });
    }

    private void validate(Attachment attachment, Username user) {
        if (user == null || attachment == null) {
            throw invalidAttachment();
        }
        validateHeaderValue(user.asString());
        if (!TDriveConfiguration.validName(attachment.fileName()) || attachment.content() == null || attachment.contentType() == null) {
            throw invalidAttachment();
        }
        validateHeaderValue(attachment.contentType().asString());
    }

    private void validateHeaderValue(String value) {
        if (StringUtils.isBlank(value) || value.chars().anyMatch(Character::isISOControl)) {
            throw invalidAttachment();
        }
    }

    private static AttachmentStorageException invalidAttachment() {
        return new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Attachment requires a user, file name, content type and binary content");
    }

    private Mono<String> lookupFolder(TDriveAccess drive) {
        return http.request(new Request(HttpMethod.GET, drive.baseUrl() + "/files/metadata?Path=" + encode("/" + configuration.folderName()), drive.serviceToken(),
                JSON_API), Document.class, "TDrive folder lookup")
            .map(document -> resourceId(document, "directory", "TDrive folder lookup"));
    }

    private Mono<String> folder(TDriveAccess drive) {
        return lookupFolder(drive)
            .onErrorResume(AttachmentStorageException.class, error -> {
                if (error.status() != HttpResponseStatus.NOT_FOUND.code()) {
                    return Mono.error(error);
                }
                return createFolder(drive)
                    // Two simultaneous first uploads may race; only a folder conflict is safe to recover by lookup.
                    .onErrorResume(AttachmentStorageException.class, conflict -> conflict.status() == HttpResponseStatus.CONFLICT.code()
                        ? lookupFolder(drive) : Mono.error(conflict));
            });
    }

    private Mono<String> createFolder(TDriveAccess drive) {
        return http.request(new Request(HttpMethod.POST, drive.baseUrl() + "/files/io.cozy.files.root-dir?Type=directory&Name=" + encode(configuration.folderName()),
                drive.serviceToken(), JSON_API), Document.class, "TDrive folder creation")
            .map(document -> resourceId(document, "directory", "TDrive folder creation"));
    }

    private Mono<String> upload(TDriveAccess drive, String folderId, Attachment attachment) {
        // Folder lookup completes on an I/O thread; hashing large files must run on a worker.
        return Mono.fromCallable(() -> Base64.getEncoder().encodeToString(DigestUtils.md5(attachment.content())))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(checksum -> {
                String fileUrl = drive.baseUrl() + "/files/" + encode(folderId) + "?Type=file&Name=";
                RequestBody body = new RequestBody(attachment.contentType().asString(), attachment.content(), checksum);
                return createFile(drive, fileUrl, attachment.fileName(), body)
                    .onErrorResume(AttachmentStorageException.class, error -> error.status() == HttpResponseStatus.CONFLICT.code()
                        ? createFile(drive, fileUrl, conflictFileName(attachment.fileName()), body)
                        : Mono.error(error));
            })
            .map(document -> resourceId(document, "file", "TDrive file upload"));
    }

    private Mono<Document> createFile(TDriveAccess drive, String fileUrl, String name, RequestBody body) {
        return http.request(new Request(HttpMethod.POST, fileUrl + encode(name), drive.serviceToken(), JSON_API),
            body, Document.class, "TDrive file upload");
    }

    private static String conflictFileName(String name) {
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
        return StandardCharsets.UTF_8.decode(stem.flip()) + suffix + extension;
    }

    private Mono<String> share(TDriveAccess drive, String fileId) {
        Map<String, Object> body = Map.of("data", Map.of("type", "io.cozy.permissions", "attributes", Map.of("permissions",
            Map.of("files", Map.of("type", "io.cozy.files", "verbs", new String[] {"GET"}, "values", new String[] {fileId})))));
        return Mono.fromCallable(() -> serialize(body))
            .flatMap(payload -> http.request(new Request(HttpMethod.POST, drive.baseUrl() + "/permissions?codes=calendar", drive.serviceToken(),
                JSON_API), new RequestBody(JSON_API, payload), Document.class, "TDrive public permission"))
            .map(document -> Optional.ofNullable(document.data())
                .map(Resource::attributes)
                .map(Attributes::shortcodes)
                .map(shortcodes -> shortcodes.get("calendar"))
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new AttachmentStorageException(Reason.INVALID_RESPONSE, "TDrive public permission: missing calendar shortcode")));
    }

    private AttachmentUri publicUri(String fqdn, String code) {
        String localPart = fqdn.substring(0, fqdn.length() - configuration.instanceDomain().length() - 1);
        String driveFqdn = localPart + "-drive." + configuration.instanceDomain();
        return new AttachmentUri(URI.create(configuration.publicUrlTemplate().replace("{fqdn}", fqdn)
            .replace("{driveFqdn}", driveFqdn).replace("{sharecode}", encode(code))));
    }

    private static String resourceId(Document document, String expectedType, String operation) {
        return Optional.ofNullable(document.data())
            .filter(resource -> Optional.ofNullable(resource.attributes()).map(Attributes::type)
                .filter(expectedType::equals).isPresent())
            .map(Resource::id)
            .filter(StringUtils::isNotBlank)
            .orElseThrow(() -> new AttachmentStorageException(Reason.INVALID_RESPONSE, operation + ": missing id or unexpected resource type"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] serialize(Object body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize TDrive permission request", e);
        }
    }
}
