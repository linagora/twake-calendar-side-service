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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.util.DurationParser;

import com.google.common.base.Preconditions;

public record TDriveConfiguration(boolean enabled, String instanceDomain, String tokenUrlTemplate,
                                  String driveUrlTemplate, String partnerToken, String folderName,
                                  String publicUrlTemplate, Duration timeout, boolean trustAllSslCerts) {
    public static final String ENABLED_PROPERTY = "twp.drive.enabled";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final String DEFAULT_FOLDER = "Calendar attachments";

    public TDriveConfiguration {
        if (enabled) {
            Preconditions.checkArgument(validHostname(instanceDomain), "twp.drive.instance.domain must be a hostname without scheme, port or path");
            validateTemplate(tokenUrlTemplate, "twp.drive.token.url.template", List.of("{fqdn}"));
            validateTemplate(driveUrlTemplate, "twp.drive.drive.url.template", List.of("{fqdn}"));
            validateTemplate(publicUrlTemplate, "twp.drive.public.url.template", List.of("{sharecode}"));
            Preconditions.checkArgument(StringUtils.isNotBlank(partnerToken) && partnerToken.chars().allMatch(character -> character > 32 && character < 127),
                "twp.drive.partner.token must be a non-blank bearer credential");
            Preconditions.checkArgument(validName(folderName), "twp.drive.folder.name must be a single directory name");
            Preconditions.checkArgument(timeout != null && timeout.isPositive() && timeout.toMillis() > 0 && timeout.toMillis() <= Integer.MAX_VALUE,
                "twp.drive.timeout must be positive and fit in milliseconds");
        }
    }

    public static TDriveConfiguration from(Configuration properties) {
        if (!properties.getBoolean(ENABLED_PROPERTY, false)) {
            // Disabled deployments do not need endpoints or credentials and never create an HTTP client.
            return new TDriveConfiguration(false, null, null, null, null, DEFAULT_FOLDER, null, DEFAULT_TIMEOUT, false);
        }
        return enabled(properties);
    }

    // Installing TDriveModule explicitly (including a builder override) enables the integration.
    static TDriveConfiguration enabled(Configuration properties) {
        return new TDriveConfiguration(true,
            required(properties, "twp.drive.instance.domain"),
            required(properties, "twp.drive.token.url.template"),
            properties.getString("twp.drive.drive.url.template", "https://{fqdn}"),
            required(properties, "twp.drive.partner.token"),
            properties.getString("twp.drive.folder.name", DEFAULT_FOLDER),
            required(properties, "twp.drive.public.url.template"),
            Optional.ofNullable(properties.getString("twp.drive.timeout", null))
                .map(value -> DurationParser.parse(value, ChronoUnit.MILLIS)).orElse(DEFAULT_TIMEOUT),
            properties.getBoolean("twp.drive.trust.all.ssl.certs", false));
    }

    private static String required(Configuration properties, String key) {
        String value = properties.getString(key, null);
        Preconditions.checkArgument(StringUtils.isNotBlank(value), key + " is required when twp.drive.enabled=true");
        return value;
    }

    private static void validateTemplate(String template, String key, List<String> required) {
        Preconditions.checkArgument(StringUtils.isNotBlank(template), key + " is required");
        required.forEach(placeholder -> Preconditions.checkArgument(template.contains(placeholder), key + " must contain " + placeholder));
        URI uri = URI.create(template.replace("{fqdn}", "user.example.com").replace("{driveFqdn}", "user-drive.example.com")
            .replace("{sharecode}", "share-code"));
        validateHttpUrl(uri, key);
        if (!key.equals("twp.drive.public.url.template")) {
            Preconditions.checkArgument(uri.getRawQuery() == null && uri.getRawFragment() == null, key + " must not contain query or fragment");
        }
    }

    private static void validateHttpUrl(URI uri, String key) {
        Preconditions.checkArgument(uri != null && uri.getHost() != null && uri.getUserInfo() == null
            && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())),
            key + " must be an absolute HTTP(S) URL without credentials");
        Preconditions.checkArgument(key.equals("twp.drive.public.url.template") || uri.getFragment() == null,
            key + " must not contain a fragment");
    }

    public static boolean validHostname(String hostname) {
        return hostname != null && hostname.length() <= 253
            && hostname.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*");
    }

    public static boolean validName(String name) {
        return StringUtils.isNotBlank(name) && !name.equals(".") && !name.equals("..")
            && !name.contains("/") && !name.contains("\\") && name.chars().noneMatch(Character::isISOControl);
    }

    @Override
    public String toString() {
        return "TDriveConfiguration[enabled=" + enabled + ", partnerToken=REDACTED]";
    }
}
