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

package com.linagora.calendar.saas.drive.configuration;

import java.net.URI;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;

public final class TDriveEndpointValidation {
    private TDriveEndpointValidation() {
    }

    static void validateServiceUrlTemplate(String template, String key) {
        requirePlaceholder(template, key, "{fqdn}");
        URI uri = URI.create(template.replace("{fqdn}", "user.example.com"));
        validateHttpUrl(uri, key);
        Preconditions.checkArgument(uri.getRawFragment() == null, "%s must not contain a fragment", key);
        Preconditions.checkArgument(uri.getRawQuery() == null, "%s must not contain query or fragment", key);
    }

    static void validatePublicUrlTemplate(String template, String key) {
        requirePlaceholder(template, key, "{sharecode}");
        URI uri = URI.create(template.replace("{fqdn}", "user.example.com")
            .replace("{instanceLabel}", "user")
            .replace("{instanceSuffix}", "example.com")
            .replace("{sharecode}", "share-code"));
        validateHttpUrl(uri, key);
    }

    private static void requirePlaceholder(String template, String key, String placeholder) {
        Preconditions.checkArgument(StringUtils.isNotBlank(template), "%s is required", key);
        Preconditions.checkArgument(template.contains(placeholder), "%s must contain %s", key, placeholder);
    }

    public static void validateHttpUrl(URI uri, String key) {
        Preconditions.checkArgument(uri != null && uri.getHost() != null && uri.getUserInfo() == null
            && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())),
            key + " must be an absolute HTTP(S) URL without credentials");
    }

    public static boolean validBearerToken(String token) {
        // Authorization headers carry a single printable ASCII credential.
        return token != null && !token.isEmpty() && CharMatcher.inRange('!', '~').matchesAllOf(token);
    }
}
