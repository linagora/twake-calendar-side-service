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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.james.core.Username;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.configuration.ClouderyLookup;
import com.linagora.calendar.saas.drive.tdrive.TDriveHttpClient.Request;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;

public final class ClouderyTDriveInstanceResolver implements TDriveInstanceResolver {
    // A second match is enough to reject an ambiguous lookup.
    private static final int LOOKUP_RESULT_LIMIT = 2;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Instance(String fqdn, @JsonProperty("internal_email") String internalEmail) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Instances(List<Instance> items, Long count) { }

    private final ClouderyLookup lookup;
    private final TDriveHttpClient http;

    public ClouderyTDriveInstanceResolver(ClouderyLookup lookup, Duration timeout, boolean trustAllSslCerts) {
        this.lookup = lookup;
        this.http = new TDriveHttpClient(timeout, trustAllSslCerts);
    }

    @Override
    public Mono<String> resolve(Username username) {
        if (username.getDomainPart().isEmpty()) {
            return Mono.error(new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Drive lookup requires an email address"));
        }
        String email = username.asString();
        String url = lookup.url() + "?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&limit=" + LOOKUP_RESULT_LIMIT;
        return http.request(new Request(HttpMethod.GET, url, lookup.partnerToken(), HttpHeaderValues.APPLICATION_JSON.toString()),
                Instances.class, "Cloudery instance lookup")
            .map(result -> resolvedHostname(result, email));
    }

    private static String resolvedHostname(Instances result, String email) {
        Instance instance = uniqueInstance(result);
        if (instance == null || !email.equalsIgnoreCase(instance.internalEmail())) {
            throw new AttachmentStorageException(Reason.INVALID_RESPONSE, "Cloudery did not confirm ownership of the Drive instance");
        }
        if (instance.fqdn() == null) {
            throw new AttachmentStorageException(Reason.INVALID_RESPONSE, "Cloudery returned an instance without a hostname");
        }
        return instance.fqdn();
    }

    private static Instance uniqueInstance(Instances result) {
        return Optional.ofNullable(result.items())
            .filter(items -> items.size() == 1)
            .filter(items -> Objects.requireNonNullElse(result.count(), 1L) == 1L)
            .orElseThrow(() -> new AttachmentStorageException(Reason.INVALID_ATTACHMENT,
                "Expected exactly one Drive instance for the authenticated user"))
            .getFirst();
    }
}
