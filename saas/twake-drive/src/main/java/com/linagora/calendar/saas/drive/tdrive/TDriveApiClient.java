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
import org.apache.commons.lang3.Strings;
import org.apache.james.mailbox.model.ContentType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
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
import reactor.core.publisher.Mono;

final class TDriveApiClient {
    record PlatformFqdn(String value) {
        static PlatformFqdn withinDomain(String hostname, String domain) {
            if (!TDriveConfiguration.validHostname(hostname) || !Strings.CI.endsWith(hostname, "." + domain)) {
                throw new AttachmentStorageException(Reason.INVALID_ATTACHMENT,
                    "Resolved Drive instance must be a hostname within the configured domain");
            }
            return new PlatformFqdn(hostname);
        }
    }

    record FolderId(String value) { }

    record FileId(String value) { }

    record FileName(String value) { }

    record ShareCode(String value) {
        @Override
        public String toString() {
            return "ShareCode[REDACTED]";
        }
    }

    record TDriveAccess(URI baseUrl, String serviceToken) {
        @Override
        public String toString() {
            return "TDriveAccess[REDACTED]";
        }
    }

    record UploadContent(ContentType contentType, byte[] bytes, String checksum) {
        static UploadContent from(Attachment attachment) {
            return new UploadContent(attachment.contentType(), attachment.content(),
                Base64.getEncoder().encodeToString(DigestUtils.md5(attachment.content())));
        }

        RequestBody requestBody() {
            return new RequestBody(contentType.asString(), bytes, checksum);
        }
    }

    private static final String JSON_API = "application/vnd.api+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] TOKEN_REQUEST = """
        {"audience":"cli","scope":"io.cozy.files io.cozy.permissions"}
        """.getBytes(StandardCharsets.UTF_8);
    private final TDriveConfiguration configuration;
    private final TDriveHttpClient http;

    TDriveApiClient(TDriveConfiguration configuration) {
        this.configuration = configuration;
        this.http = new TDriveHttpClient(configuration.timeout(), configuration.trustAllSslCerts());
    }

    Mono<TDriveAccess> access(PlatformFqdn fqdn) {
        return serviceToken(fqdn)
            .map(token -> new TDriveAccess(URI.create(StringUtils.stripEnd(
                configuration.driveUrlTemplate().replace("{fqdn}", fqdn.value()), "/")), token));
    }

    private Mono<String> serviceToken(PlatformFqdn fqdn) {
        return http.request(new Request(HttpMethod.POST, URI.create(configuration.tokenUrlTemplate().replace("{fqdn}", fqdn.value())),
                configuration.partnerToken(), HttpHeaderValues.APPLICATION_JSON.toString()), RequestBody.json(TOKEN_REQUEST),
                Token.class, "TDrive service token")
            .map(result -> {
                if (!TDriveEndpointValidation.validBearerToken(result.token())) {
                    throw new AttachmentStorageException(Reason.INVALID_RESPONSE, "TDrive service token: missing token");
                }
                return result.token();
            });
    }

    Mono<FolderId> lookupFolder(TDriveAccess drive) {
        return http.request(new Request(HttpMethod.GET, URI.create(drive.baseUrl() + "/files/metadata?Path=" + encode("/" + configuration.folderName())), drive.serviceToken(),
                JSON_API), Document.class, "TDrive folder lookup")
            .map(document -> new FolderId(resourceId(document, "directory", "TDrive folder lookup")));
    }

    Mono<FolderId> createFolder(TDriveAccess drive) {
        return http.request(new Request(HttpMethod.POST, URI.create(drive.baseUrl() + "/files/io.cozy.files.root-dir?Type=directory&Name=" + encode(configuration.folderName())),
                drive.serviceToken(), JSON_API), Document.class, "TDrive folder creation")
            .map(document -> new FolderId(resourceId(document, "directory", "TDrive folder creation")));
    }

    Mono<FileId> createFile(TDriveAccess drive, FolderId folderId, FileName name, UploadContent content) {
        URI folderUrl = URI.create(drive.baseUrl() + "/files/" + encode(folderId.value()));
        URI fileUrl = URI.create(folderUrl + "?Type=file&Name=" + encode(name.value()));
        TDriveEndpointValidation.validateHttpUrl(fileUrl, "TDrive file upload URL");
        return http.request(new Request(HttpMethod.POST, fileUrl, drive.serviceToken(), JSON_API),
            content.requestBody(), Document.class, "TDrive file upload")
            .map(document -> new FileId(resourceId(document, "file", "TDrive file upload")));
    }

    Mono<ShareCode> share(TDriveAccess drive, FileId fileId) {
        Map<String, Object> body = Map.of("data", Map.of("type", "io.cozy.permissions", "attributes", Map.of("permissions",
            Map.of("files", Map.of("type", "io.cozy.files", "verbs", new String[] {"GET"}, "values", new String[] {fileId.value()})))));
        return Mono.fromCallable(() -> serialize(body))
            .flatMap(payload -> http.request(new Request(HttpMethod.POST, URI.create(drive.baseUrl() + "/permissions?codes=calendar"), drive.serviceToken(),
                JSON_API), new RequestBody(JSON_API, payload), Document.class, "TDrive public permission"))
            .map(document -> Optional.ofNullable(document.data())
                .map(Resource::attributes)
                .map(Attributes::shortcodes)
                .map(shortcodes -> shortcodes.get("calendar"))
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new AttachmentStorageException(Reason.INVALID_RESPONSE, "TDrive public permission: missing calendar shortcode")))
            .map(ShareCode::new);
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
