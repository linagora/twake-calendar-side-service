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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.RestApiConfiguration;

/**
 * Web origins (scheme://host[:port]) allowed to send state-changing requests
 * authenticated by an ambient credential such as the LemonLDAP cookie.
 */
public record TrustedOrigins(ImmutableSet<String> origins) {

    public static final String EXCAL_SPA_URL_PROPERTY = "spa.excal.url";
    public static final String EXTRA_TRUSTED_ORIGINS_PROPERTY = "oidc.fallback.lemon.cookie.trusted.origins";
    private static final ImmutableMap<String, Integer> DEFAULT_PORTS = ImmutableMap.of("http", 80, "https", 443);
    private static final int NO_PORT = -1;

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
        return parseUri(url)
            .filter(uri -> uri.getHost() != null)
            .flatMap(uri -> Optional.ofNullable(uri.getScheme())
                .map(scheme -> scheme.toLowerCase(Locale.US))
                .filter(DEFAULT_PORTS::containsKey)
                .map(scheme -> scheme + "://" + uri.getHost().toLowerCase(Locale.US) + portSuffix(scheme, uri.getPort())));
    }

    private static Optional<URI> parseUri(String url) {
        try {
            return Optional.of(new URI(url.trim()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String portSuffix(String scheme, int port) {
        if (port == NO_PORT || DEFAULT_PORTS.get(scheme) == port) {
            return "";
        }
        return ":" + port;
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
