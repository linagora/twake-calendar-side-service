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
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.util.DurationParser;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;

public record TDriveConfiguration(boolean enabled, String instanceDomain, String tokenUrlTemplate,
                                  String driveUrlTemplate, String partnerToken, String folderName,
                                  String publicUrlTemplate, Duration timeout, boolean trustAllSslCerts,
                                  Optional<ClouderyLookup> clouderyLookup, InstanceResolver instanceResolver) {
    public static final String ENABLED_PROPERTY = "twp.drive.enabled";
    private static final String INSTANCE_DOMAIN_PROPERTY = "twp.drive.instance.domain";
    private static final String INSTANCE_RESOLVER_PROPERTY = "twp.drive.instance.resolver";
    private static final String TOKEN_URL_TEMPLATE_PROPERTY = "twp.drive.token.url.template";
    private static final String DRIVE_URL_TEMPLATE_PROPERTY = "twp.drive.drive.url.template";
    private static final String PARTNER_TOKEN_PROPERTY = "twp.drive.partner.token";
    private static final String FOLDER_NAME_PROPERTY = "twp.drive.folder.name";
    private static final String PUBLIC_URL_TEMPLATE_PROPERTY = "twp.drive.public.url.template";
    private static final String TIMEOUT_PROPERTY = "twp.drive.timeout";
    private static final String TRUST_ALL_SSL_CERTS_PROPERTY = "twp.drive.trust.all.ssl.certs";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final String DEFAULT_FOLDER = "Calendar attachments";

    private static final TDriveConfiguration DISABLED = new TDriveConfiguration(false, null, null, null, null,
        DEFAULT_FOLDER, null, DEFAULT_TIMEOUT, false, Optional.empty(), InstanceResolver.LOCAL_PART);
    private static final CharMatcher INVALID_NAME_CHARACTERS = CharMatcher.javaIsoControl().or(CharMatcher.anyOf("/\\"));
    private static final String HOSTNAME_LABEL = "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?";
    private static final Pattern HOSTNAME = Pattern.compile(HOSTNAME_LABEL + "(?:\\." + HOSTNAME_LABEL + ")*");
    private static final int MAX_HOSTNAME_LENGTH = 253;

    public enum InstanceResolver {
        LOCAL_PART, CLOUDERY, LDAP
    }

    public TDriveConfiguration {
        if (enabled) {
            Preconditions.checkArgument(validHostname(instanceDomain), "twp.drive.instance.domain must be a hostname without scheme, port or path");
            TDriveEndpointValidation.validateServiceUrlTemplate(tokenUrlTemplate, TOKEN_URL_TEMPLATE_PROPERTY);
            TDriveEndpointValidation.validateServiceUrlTemplate(driveUrlTemplate, DRIVE_URL_TEMPLATE_PROPERTY);
            TDriveEndpointValidation.validatePublicUrlTemplate(publicUrlTemplate, PUBLIC_URL_TEMPLATE_PROPERTY);
            Preconditions.checkArgument(TDriveEndpointValidation.validBearerToken(partnerToken),
                "twp.drive.partner.token must be a non-blank bearer credential");
            Preconditions.checkArgument(validName(folderName), "twp.drive.folder.name must be a single directory name");
            Preconditions.checkArgument(timeout != null && timeout.isPositive() && timeout.toMillis() > 0 && timeout.toMillis() <= Integer.MAX_VALUE,
                "twp.drive.timeout must be positive and fit in milliseconds");
        }
    }

    public static TDriveConfiguration from(Configuration properties) {
        if (!properties.getBoolean(ENABLED_PROPERTY, false)) {
            // Disabled deployments do not need endpoints or credentials and never create an HTTP client.
            return DISABLED;
        }
        return enabled(properties);
    }

    // Installing TDriveModule explicitly (including a builder override) enables the integration.
    static TDriveConfiguration enabled(Configuration properties) {
        InstanceResolver resolver = switch (properties.getString(INSTANCE_RESOLVER_PROPERTY, "local-part")) {
            case "local-part" -> InstanceResolver.LOCAL_PART;
            case "cloudery" -> InstanceResolver.CLOUDERY;
            case "ldap" -> InstanceResolver.LDAP;
            default -> throw new IllegalArgumentException("twp.drive.instance.resolver must be local-part, cloudery or ldap");
        };
        return new TDriveConfiguration(true,
            required(properties, INSTANCE_DOMAIN_PROPERTY),
            required(properties, TOKEN_URL_TEMPLATE_PROPERTY),
            properties.getString(DRIVE_URL_TEMPLATE_PROPERTY, "https://{fqdn}"),
            required(properties, PARTNER_TOKEN_PROPERTY),
            properties.getString(FOLDER_NAME_PROPERTY, DEFAULT_FOLDER),
            required(properties, PUBLIC_URL_TEMPLATE_PROPERTY),
            Optional.ofNullable(properties.getString(TIMEOUT_PROPERTY, null))
                .map(value -> DurationParser.parse(value, ChronoUnit.MILLIS)).orElse(DEFAULT_TIMEOUT),
            properties.getBoolean(TRUST_ALL_SSL_CERTS_PROPERTY, false),
            lookupConfiguration(properties, resolver), resolver);
    }

    private static Optional<ClouderyLookup> lookupConfiguration(Configuration properties, InstanceResolver resolver) {
        if (resolver == InstanceResolver.CLOUDERY) {
            return Optional.of(new ClouderyLookup(URI.create(required(properties, ClouderyLookup.URL_PROPERTY)),
                required(properties, ClouderyLookup.PARTNER_TOKEN_PROPERTY)));
        }
        return Optional.empty();
    }

    private static String required(Configuration properties, String key) {
        String value = properties.getString(key, null);
        Preconditions.checkArgument(StringUtils.isNotBlank(value), "%s is required when twp.drive.enabled=true", key);
        return value;
    }

    public static boolean validHostname(String hostname) {
        return hostname != null && hostname.length() <= MAX_HOSTNAME_LENGTH && HOSTNAME.matcher(hostname).matches();
    }

    public static boolean validName(String name) {
        return StringUtils.isNotBlank(name) && !Strings.CS.equalsAny(name, ".", "..")
            && INVALID_NAME_CHARACTERS.matchesNoneOf(name);
    }

    @Override
    public String toString() {
        return "TDriveConfiguration[enabled=" + enabled + ", partnerToken=REDACTED]";
    }
}
