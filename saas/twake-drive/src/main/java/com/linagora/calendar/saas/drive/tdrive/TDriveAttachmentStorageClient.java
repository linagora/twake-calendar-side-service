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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.api.AttachmentUri;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.tdrive.TDriveHttpClient.Request;
import com.linagora.calendar.saas.drive.tdrive.TDriveHttpClient.RequestBody;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Attributes;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Document;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Resource;
import com.linagora.calendar.saas.drive.tdrive.TDriveResponses.Token;

import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class TDriveAttachmentStorageClient implements AttachmentStorageClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] TOKEN_REQUEST = """
        {"audience":"cli","scope":"io.cozy.files io.cozy.permissions"}
        """.getBytes(StandardCharsets.UTF_8);
    private final TDriveConfiguration configuration;
    private final TDriveHttpClient http;

    public TDriveAttachmentStorageClient(TDriveConfiguration configuration) {
        if (!configuration.enabled()) {
            throw new IllegalArgumentException("TDrive integration is disabled");
        }
        this.configuration = configuration;
        this.http = new TDriveHttpClient(configuration);
    }

    @Override
    public Mono<AttachmentUri> upload(Attachment attachment, Username username) {
        return Mono.defer(() -> {
            validate(attachment, username);
            String fqdn = fqdn(username.getLocalPart());
            if (!TDriveConfiguration.validHostname(fqdn)) {
                throw new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Username local part must form a valid instance hostname");
            }
            return token(fqdn)
                .flatMap(token -> {
                    String drive = StringUtils.stripEnd(configuration.driveUrlTemplate().replace("{fqdn}", fqdn), "/");
                    return folder(drive, token)
                        .flatMap(folderId -> upload(drive, token, folderId, attachment))
                        .flatMap(fileId -> share(drive, token, fileId))
                        .map(code -> publicUri(fqdn, code));
                })
                .onErrorMap(AttachmentStorageException.class, error -> new AttachmentStorageException(error.reason(), error.status(),
                    error.getMessage() + " for user " + username.asString(), error));
        });
    }

    private Mono<String> token(String fqdn) {
        return http.request(new Request(HttpMethod.POST, configuration.tokenUrlTemplate().replace("{fqdn}", fqdn),
                configuration.partnerToken(), "application/json"), new RequestBody("application/json", TOKEN_REQUEST, null),
                Token.class, "TDrive service token")
            .map(result -> {
                if (StringUtils.isBlank(result.token()) || result.token().chars().anyMatch(character -> character <= 32 || character >= 127)) {
                    throw new AttachmentStorageException(Reason.INVALID_RESPONSE, "TDrive service token: missing token");
                }
                return result.token();
            });
    }

    private void validate(Attachment attachment, Username user) {
        validateUser(user);
        validateAttachment(attachment);
    }

    private void validateUser(Username user) {
        if (user == null) {
            throw invalidAttachment();
        }
        validateHeaderValue(user.asString());
    }

    private void validateAttachment(Attachment attachment) {
        if (attachment == null) {
            throw invalidAttachment();
        }
        if (!TDriveConfiguration.validName(attachment.fileName())) {
            throw invalidAttachment();
        }
        if (attachment.content() == null || attachment.contentType() == null) {
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

    private Mono<String> lookupFolder(String drive, String token) {
        return http.request(new Request(HttpMethod.GET, drive + "/files/metadata?Path=" + encode("/" + configuration.folderName()), token,
                TDriveHttpClient.JSON_API), RequestBody.EMPTY, Document.class, "TDrive folder lookup")
            .map(document -> resourceId(document, "directory", "TDrive folder lookup"));
    }

    private Mono<String> folder(String drive, String token) {
        return lookupFolder(drive, token)
            .onErrorResume(AttachmentStorageException.class, error -> {
                if (error.status() != 404) {
                    return Mono.error(error);
                }
                return http.request(new Request(HttpMethod.POST, drive + "/files/io.cozy.files.root-dir?Type=directory&Name=" + encode(configuration.folderName()),
                        token, TDriveHttpClient.JSON_API), RequestBody.EMPTY, Document.class, "TDrive folder creation")
                    .map(document -> resourceId(document, "directory", "TDrive folder creation"))
                    // Two simultaneous first uploads may race; only a folder conflict is safe to recover by lookup.
                    .onErrorResume(AttachmentStorageException.class, conflict -> conflict.status() == 409
                        ? lookupFolder(drive, token) : Mono.error(conflict));
            });
    }

    private Mono<String> upload(String drive, String token, String folderId, Attachment attachment) {
        // Folder lookup completes on an I/O thread; hashing large files must run on a worker.
        return Mono.fromCallable(() -> Base64.getEncoder().encodeToString(DigestUtils.md5(attachment.content())))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(checksum -> http.request(new Request(HttpMethod.POST, drive + "/files/" + encode(folderId) + "?Type=file&Name=" + encode(attachment.fileName()),
                token, TDriveHttpClient.JSON_API), new RequestBody(attachment.contentType().asString(), attachment.content(), checksum),
                Document.class, "TDrive file upload"))
            .map(document -> resourceId(document, "file", "TDrive file upload"));
    }

    private Mono<String> share(String drive, String token, String fileId) {
        Map<String, Object> body = Map.of("data", Map.of("type", "io.cozy.permissions", "attributes", Map.of("permissions",
            Map.of("files", Map.of("type", "io.cozy.files", "verbs", new String[] {"GET"}, "values", new String[] {fileId})))));
        return Mono.fromCallable(() -> serialize(body))
            .flatMap(payload -> http.request(new Request(HttpMethod.POST, drive + "/permissions?codes=calendar", token,
                TDriveHttpClient.JSON_API), new RequestBody(TDriveHttpClient.JSON_API, payload, null), Document.class, "TDrive public permission"))
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

    private String fqdn(String localPart) {
        return localPart.replace(".", "") + "." + configuration.instanceDomain();
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
