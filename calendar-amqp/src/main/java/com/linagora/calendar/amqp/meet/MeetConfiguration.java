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

package com.linagora.calendar.amqp.meet;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.util.DurationParser;

import com.google.common.base.Preconditions;

public record MeetConfiguration(boolean enabled,
                                String clientId,
                                String clientSecret,
                                URI externalApiBaseUrl,
                                boolean trustAllSslCerts,
                                Duration responseTimeout,
                                Optional<String> roomAccessLevel) {

    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    static final String MEET_ENABLED_PROPERTY = "meet.enabled";
    static final String MEET_APPLICATION_CLIENT_ID_PROPERTY = "meet.application.client_id";
    static final String MEET_APPLICATION_CLIENT_SECRET_PROPERTY = "meet.application.client_secret";
    static final String MEET_EXTERNAL_API_BASE_URL_PROPERTY = "meet.external.api.base.url";
    static final String MEET_TRUST_ALL_SSL_CERTS_PROPERTY = "meet.rest.client.trust.all.ssl.certs";
    static final String MEET_RESPONSE_TIMEOUT_PROPERTY = "meet.rest.client.response.timeout";
    /**
     * Access level requested for rooms this service creates. Empty leaves the
     * choice to Meet, whose {@code EXTERNAL_API_DEFAULT_ACCESS_LEVEL} defaults
     * to {@code trusted}.
     *
     * <p>That default deserves a deliberate decision rather than a shrug: a
     * {@code trusted} room only admits authenticated users of the instance, so
     * an external guest invited to a meeting cannot get in — and the organiser
     * being present changes nothing. Deployments that invite guests want
     * {@code public} here, which Meet also gates behind its own
     * {@code EXTERNAL_API_ALLOW_PUBLIC_ACCESS}.
     */
    static final String MEET_ROOM_ACCESS_LEVEL_PROPERTY = "meet.room.access_level";

    public static MeetConfiguration from(Configuration configuration) {
        boolean enabled = Boolean.parseBoolean(readProperty(configuration, MEET_ENABLED_PROPERTY, "false"));
        if (!enabled) {
            return disabled();
        }

        String clientId = readProperty(configuration, MEET_APPLICATION_CLIENT_ID_PROPERTY, null);
        String clientSecret = readProperty(configuration, MEET_APPLICATION_CLIENT_SECRET_PROPERTY, null);
        String baseUrl = readProperty(configuration, MEET_EXTERNAL_API_BASE_URL_PROPERTY, null);

        Preconditions.checkArgument(StringUtils.isNotEmpty(clientId),
            MEET_APPLICATION_CLIENT_ID_PROPERTY + " should not be empty when " + MEET_ENABLED_PROPERTY + "=true");
        Preconditions.checkArgument(StringUtils.isNotEmpty(clientSecret),
            MEET_APPLICATION_CLIENT_SECRET_PROPERTY + " should not be empty when " + MEET_ENABLED_PROPERTY + "=true");
        Preconditions.checkArgument(StringUtils.isNotEmpty(baseUrl),
            MEET_EXTERNAL_API_BASE_URL_PROPERTY + " should not be empty when " + MEET_ENABLED_PROPERTY + "=true");

        boolean trustAllSslCerts = Boolean.parseBoolean(readProperty(configuration, MEET_TRUST_ALL_SSL_CERTS_PROPERTY, "false"));
        Duration responseTimeout = Optional.ofNullable(readProperty(configuration, MEET_RESPONSE_TIMEOUT_PROPERTY, null))
            .map(string -> DurationParser.parse(string, ChronoUnit.MILLIS))
            .map(duration -> {
                Preconditions.checkArgument(duration.isPositive(), "Response timeout should not be negative");
                return duration;
            })
            .orElse(DEFAULT_RESPONSE_TIMEOUT);

        Optional<String> roomAccessLevel = Optional.ofNullable(readProperty(configuration, MEET_ROOM_ACCESS_LEVEL_PROPERTY, null))
            .map(StringUtils::trimToNull)
            .map(Optional::of)
            .orElse(Optional.empty());

        return new MeetConfiguration(true, clientId, clientSecret, URI.create(baseUrl), trustAllSslCerts, responseTimeout, roomAccessLevel);
    }

    /**
     * Read a property with three fallback layers, in order of precedence:
     * 1. Java system property (e.g. -Dmeet.enabled=true)
     * 2. Environment variable with the exact dotted name (e.g. meet.enabled=true)
     *    — how docker-compose feeds this in production.
     * 3. The Apache Commons {@link Configuration} (configuration.properties file).
     *
     * Apache Commons Configuration does not auto-pick env vars, so we need the
     * explicit fallback for the docker-compose deployment path.
     */
    private static String readProperty(Configuration configuration, String key, String defaultValue) {
        String sysProp = System.getProperty(key);
        if (StringUtils.isNotEmpty(sysProp)) {
            return sysProp;
        }
        String env = System.getenv(key);
        if (StringUtils.isNotEmpty(env)) {
            return env;
        }
        return configuration.getString(key, defaultValue);
    }

    public static MeetConfiguration disabled() {
        return new MeetConfiguration(false, null, null, null, false, DEFAULT_RESPONSE_TIMEOUT, Optional.empty());
    }
}
