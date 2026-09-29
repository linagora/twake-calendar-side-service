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

package com.linagora.calendar.commoncontacts.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.Strings;

import com.google.common.collect.ImmutableList;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.netty.http.server.HttpServerRequest;

public class BearerTokenAuthenticator {
    private static final String BEARER_PREFIX = "Bearer ";

    private final List<byte[]> secrets;

    @Inject
    public BearerTokenAuthenticator(CommonContactsApiConfiguration configuration) {
        this.secrets = configuration.secrets().stream()
            .map(secret -> secret.value().getBytes(UTF_8))
            .collect(ImmutableList.toImmutableList());
    }

    public boolean isAuthorized(HttpServerRequest request) {
        return extractBearerToken(request)
            .map(token -> token.getBytes(UTF_8))
            .map(this::matchesAnySecret)
            .orElse(false);
    }

    private Optional<String> extractBearerToken(HttpServerRequest request) {
        return Optional.ofNullable(request.requestHeaders().get(HttpHeaderNames.AUTHORIZATION))
            .filter(header -> Strings.CI.startsWith(header, BEARER_PREFIX))
            .map(header -> header.substring(BEARER_PREFIX.length()).trim());
    }

    // Constant time comparison, evaluated against every secret, in order not to leak timing information
    private boolean matchesAnySecret(byte[] token) {
        return secrets.stream()
            .map(secret -> MessageDigest.isEqual(secret, token))
            .reduce(false, Boolean::logicalOr);
    }
}
