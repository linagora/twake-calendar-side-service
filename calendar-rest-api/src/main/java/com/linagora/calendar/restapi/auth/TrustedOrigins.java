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

package com.linagora.calendar.restapi.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.RestApiConfiguration;

/**
 * Web origins (scheme://host[:port]) allowed to send state-changing requests
 * authenticated by an ambient credential such as the LemonLDAP cookie.
 */
public record TrustedOrigins(ImmutableSet<String> origins) {

    public static final String EXCAL_SPA_URL_PROPERTY = "spa.excal.url";
    public static final String EXTRA_TRUSTED_ORIGINS_PROPERTY = "oidc.fallback.lemon.cookie.trusted.origins";

    public static TrustedOrigins from(RestApiConfiguration restApiConfiguration) {
        return of(Stream.of(restApiConfiguration.getCalendarSpaUrl(),
                restApiConfiguration.getContactSpaUrl(),
                restApiConfiguration.getSelfUrl())
            .map(URL::toString)
            .toList());
    }

    public static TrustedOrigins from(RestApiConfiguration restApiConfiguration, Configuration configuration) {
        Stream<String> excalSpaUrl = Optional.ofNullable(configuration.getString(EXCAL_SPA_URL_PROPERTY, null)).stream();
        Stream<String> extraOrigins = Arrays.stream(configuration.getStringArray(EXTRA_TRUSTED_ORIGINS_PROPERTY))
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(StringUtils::isNotEmpty);

        return from(restApiConfiguration)
            .with(Stream.concat(excalSpaUrl, extraOrigins).toList());
    }

    public static TrustedOrigins of(Collection<String> urls) {
        return new TrustedOrigins(urls.stream()
            .map(url -> asOrigin(url)
                .orElseThrow(() -> new IllegalArgumentException("Invalid trusted origin: " + url)))
            .collect(ImmutableSet.toImmutableSet()));
    }

    /**
     * @return the normalized origin (lower-case scheme://host, default port omitted) of an absolute URL,
     * or empty when the value is not an absolute http(s) URL (e.g. the opaque {@code Origin: null}).
     */
    public static Optional<String> asOrigin(String url) {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty();
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1
                || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
            return Optional.of(scheme + "://" + uri.getHost().toLowerCase(Locale.US) + (defaultPort ? "" : ":" + port));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    public TrustedOrigins {
        Preconditions.checkNotNull(origins, "origins must not be null");
    }

    public TrustedOrigins with(Collection<String> urls) {
        return new TrustedOrigins(ImmutableSet.<String>builder()
            .addAll(origins)
            .addAll(of(urls).origins())
            .build());
    }

    public boolean contains(String origin) {
        return origins.contains(origin);
    }
}
