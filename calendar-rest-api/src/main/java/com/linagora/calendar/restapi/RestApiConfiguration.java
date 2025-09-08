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

package com.linagora.calendar.restapi;

import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.apache.james.util.Port;
import org.apache.james.utils.PropertiesProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.model.Aud;

public class RestApiConfiguration {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<Port> port = Optional.empty();
        private Optional<String> jwtPrivatePath = Optional.empty();
        private Optional<List<String>> jwtPublicPath = Optional.empty();
        private Optional<Duration> jwtValidity = Optional.empty();
        private Optional<URL> calendarSpaUrl = Optional.empty();
        private Optional<URL> contactSpaUrl = Optional.empty();
        private Optional<URL> openpaasBackendURL = Optional.empty();
        private Optional<URL> davdURL = Optional.empty();
        private Optional<URL> selfURL = Optional.empty();
        private Optional<URL> visioURL = Optional.empty();
        private Optional<Boolean> openpaasBackendTrustAllCerts = Optional.empty();
        private Optional<Boolean> sharingCalendarEnabled = Optional.empty();
        private Optional<Boolean> sharingAddressbookEnabled = Optional.empty();
        private Optional<Boolean> domainMembersAddressbookEnabled = Optional.empty();
        private Optional<URL> oidcUserInfoUrl = Optional.empty();
        private Optional<IntrospectionEndpoint> oidcIntrospectionEndpoint = Optional.empty();
        private Optional<String> oidcIntrospectionClaim = Optional.empty();
        private Optional<Aud> oidcAudience = Optional.empty();
        private Optional<String> defaultLanguage = Optional.empty();
        private Optional<String> defaultTimezone = Optional.empty();
        private Optional<JsonNode> defaultBusinessHours = Optional.empty();
        private Optional<Boolean> defaultUse24hFormat = Optional.empty();
        private Optional<Boolean> enableBasicAuth = Optional.empty();
        private Optional<String> adminUsername = Optional.empty();
        private Optional<String> adminPassword = Optional.empty();

        private Builder() {

        }

        public Builder port(Port port) {
            this.port = Optional.of(port);
            return this;
        }

        public Builder jwtPrivatePath(Optional<String> jwtPrivatePath) {
            this.jwtPrivatePath = jwtPrivatePath;
            return this;
        }

        public Builder defaultLanguage(Optional<String> defaultLanguage) {
            this.defaultLanguage = defaultLanguage;
            return this;
        }

        public Builder defaultTimezone(Optional<String> defaultTimezone) {
            this.defaultTimezone = defaultTimezone;
            return this;
        }

        public Builder defaultBusinessHours(Optional<JsonNode> defaultBusinessHours) {
            this.defaultBusinessHours = defaultBusinessHours;
            return this;
        }

        public Builder jwtPublicPath(Optional<List<String>> jwtPublicPath) {
            this.jwtPublicPath = jwtPublicPath;
            return this;
        }

        public Builder jwtValidity(Optional<Duration> jwtValidity) {
            this.jwtValidity = jwtValidity;
            return this;
        }

        public Builder port(Optional<Port> port) {
            this.port = port;
            return this;
        }

        public Builder selfUrl(Optional<URL> url) {
            this.selfURL = url;
            return this;
        }

        public Builder visioURL(Optional<URL> url) {
            this.visioURL = url;
            return this;
        }

        public Builder calendarSpaUrl(Optional<URL> url) {
            this.calendarSpaUrl = url;
            return this;
        }

        public Builder contactSpaUrl(Optional<URL> url) {
            this.contactSpaUrl = url;
            return this;
        }

        public Builder openpaasBackendURL(Optional<URL> url) {
            this.openpaasBackendURL = url;
            return this;
        }

        public Builder davURL(Optional<URL> url) {
            this.davdURL = url;
            return this;
        }

        public Builder openpaasBackendTrustAllCerts(Optional<Boolean> openpaasBackendTrustAllCerts) {
            this.openpaasBackendTrustAllCerts = openpaasBackendTrustAllCerts;
            return this;
        }

        public Builder defaultUse24hFormat(Optional<Boolean> defaultUse24hFormat) {
            this.defaultUse24hFormat = defaultUse24hFormat;
            return this;
        }

        public Builder enableCalendarSharing(Optional<Boolean> sharingCalendarEnabled) {
            this.sharingCalendarEnabled = sharingCalendarEnabled;
            return this;
        }

        public Builder sharingAddressbookEnabled(Optional<Boolean> sharingAddressbookEnabled) {
            this.sharingAddressbookEnabled = sharingAddressbookEnabled;
            return this;
        }

        public Builder domainMembersAddressbookEnabled(Optional<Boolean> domainMembersAddressbookEnabled) {
            this.domainMembersAddressbookEnabled = domainMembersAddressbookEnabled;
            return this;
        }

        public Builder oidcUserInfoUrl(Optional<URL> url) {
            this.oidcUserInfoUrl = url;
            return this;
        }

        public Builder oidcIntrospectionEndpoint(Optional<IntrospectionEndpoint> endpoint) {
            this.oidcIntrospectionEndpoint = endpoint;
            return this;
        }

        public Builder oidcClaim(Optional<String> claim) {
            this.oidcIntrospectionClaim = claim;
            return this;
        }

        public Builder oidcAudience(Optional<Aud> aud) {
            this.oidcAudience = aud;
            return this;
        }

        public Builder adminUsername(Optional<String> adminUsername) {
            this.adminUsername = adminUsername;
            return this;
        }


        public Builder adminPassword(Optional<String> adminPassword) {
            this.adminPassword = adminPassword;
            return this;
        }


        public Builder enableBasicAuth(Optional<Boolean> enableBasicAuth) {
            this.enableBasicAuth = enableBasicAuth;
            return this;
        }

        public RestApiConfiguration build() {
            try {
                ArrayNode arrayNode = defaultBusinessHours();

                return new RestApiConfiguration(port,
                    calendarSpaUrl.orElse(URI.create("https://e-calendrier.avocat.fr").toURL()),
                    contactSpaUrl.orElse(URI.create("https://e-contacts.avocat.fr").toURL()),
                    selfURL.orElse(URI.create("https://twcalendar.linagora.com").toURL()),
                    openpaasBackendURL,
                    davdURL.orElse(URI.create("https://dav.linagora.com").toURL()),
                    visioURL.orElse(URI.create("https://jitsi.linagora.com").toURL()),
                    openpaasBackendTrustAllCerts.orElse(false),
                    jwtPrivatePath.orElse("classpath://jwt_privatekey"),
                    jwtPublicPath.orElse(ImmutableList.of("classpath://jwt_publickey")),
                    jwtValidity.orElse(Duration.ofHours(12)),
                    oidcUserInfoUrl.orElse(URI.create("http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/userInfo").toURL()),
                    oidcIntrospectionEndpoint.orElse(new IntrospectionEndpoint(URI.create("http://keycloak:8080/auth/realms/oidc/protocol/openid-connect/introspect").toURL(), Optional.empty())),
                    oidcIntrospectionClaim.orElse("email"),
                    oidcAudience.orElse(new Aud("tcalendar")),
                    sharingCalendarEnabled.orElse(true),
                    sharingAddressbookEnabled.orElse(true),
                    domainMembersAddressbookEnabled.orElse(true),
                    defaultLanguage.orElse("en"),
                    defaultTimezone.orElse("Europe/Paris"),
                    defaultUse24hFormat.orElse(true),
                    defaultBusinessHours.orElse(arrayNode),
                    enableBasicAuth.orElse(false),
                    adminUsername.orElse("admin@open-paas.org"),
                    adminPassword.orElseThrow(() -> new IllegalArgumentException("Expecting 'admin.password' to be specified")));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        private static ArrayNode defaultBusinessHours() {
            try {
                ObjectNode businessHours = OBJECT_MAPPER.createObjectNode();
                businessHours.put("start", "8:0");
                businessHours.put("end", "19:0");
                businessHours.put("daysOfWeek", OBJECT_MAPPER.readTree("[1,2,3,4,5]"));
                ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
                arrayNode.add(businessHours);
                return arrayNode;
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static RestApiConfiguration parseConfiguration(PropertiesProvider propertiesProvider) throws FileNotFoundException, ConfigurationException {
        Configuration configuration = propertiesProvider.getConfiguration("configuration");
        return parseConfiguration(configuration);
    }

    public static RestApiConfiguration parseConfiguration(Configuration configuration) {
        Function<String, Optional<URL>> urlParser = propertyName -> Optional.ofNullable(configuration.getString(propertyName, null))
            .map(Throwing.function(urlAsString -> URI.create(urlAsString).toURL()));

        Optional<Port> port = Optional.ofNullable(configuration.getInteger("rest.api.port", null))
            .map(Port::of);
        Optional<URL> calendarSpaUrl = urlParser.apply("spa.calendar.url");
        Optional<URL> contactSpaUrl = urlParser.apply("spa.contacts.url");
        Optional<URL> openpaasBackendURL = urlParser.apply("openpaas.backend.url");
        Optional<URL> davURL = urlParser.apply("dav.url");
        Optional<URL> selfURL = urlParser.apply("self.url");
        Optional<URL> visioURL = urlParser.apply("visio.url");
        Optional<Boolean> openpaasBackendTrustAllCerts = Optional.ofNullable(configuration.getBoolean("openpaas.backend.trust.all.certificates", null));
        Optional<String> jwtPrivateKey = Optional.ofNullable(configuration.getString("jwt.key.private", null));
        Optional<List<String>> jwtPublicKey = Optional.ofNullable(configuration.getString("jwt.key.public", null))
            .map(s -> Splitter.on(',').splitToList(s));
        Optional<Duration> jwtValidity = Optional.ofNullable(configuration.getString("jwt.key.validity", null))
            .map(Duration::parse);
        Optional<URL> oidcUserInfoUrl = urlParser.apply("oidc.userInfo.url");
        Optional<URL> oidcIntrospectUrl = urlParser.apply("oidc.introspect.url");
        Optional<String> oidcIntrospectCreds = Optional.ofNullable(configuration.getString("oidc.introspect.credentials", null));
        Optional<Aud> oidcAudience = Optional.ofNullable(configuration.getString("oidc.audience", null)).map(Aud::new);
        Optional<String> oidcIntrospectionClaim = Optional.ofNullable(configuration.getString("oidc.claim", null));
        Optional<Boolean> calendarSharingEnabled = Optional.ofNullable(configuration.getBoolean("calendar.sharing.enabled", null));
        Optional<Boolean> sharingAddressbookEnabled = Optional.ofNullable(configuration.getBoolean("contacts.sharing.enabled", null));
        Optional<Boolean> domainMembersAddressbookEnabled = Optional.ofNullable(configuration.getBoolean("domain.contacts.enabled", null));
        Optional<Boolean> defaultUse24hFormat = Optional.ofNullable(configuration.getBoolean("default.use.24h.format", null));
        Optional<String> defaultLanguage = Optional.ofNullable(configuration.getString("default.language", null));
        Optional<String> defaultTimezone = Optional.ofNullable(configuration.getString("default.timezone", null));
        Optional<Boolean> enableBasicAuth = Optional.ofNullable(configuration.getBoolean("basic.auth.enabled", null));
        Optional<String> adminUsername = Optional.ofNullable(configuration.getString("admin.username", null));
        Optional<String> adminPassword = Optional.ofNullable(configuration.getString("admin.password", null));
        ArrayNode arrayNode = readWorkingHours(configuration);

        Optional<IntrospectionEndpoint> introspectionEndpoint = oidcIntrospectUrl.map(url -> new IntrospectionEndpoint(url, oidcIntrospectCreds));

        return RestApiConfiguration.builder()
            .port(port)
            .calendarSpaUrl(calendarSpaUrl)
            .contactSpaUrl(contactSpaUrl)
            .openpaasBackendURL(openpaasBackendURL)
            .davURL(davURL)
            .selfUrl(selfURL)
            .visioURL(visioURL)
            .openpaasBackendTrustAllCerts(openpaasBackendTrustAllCerts)
            .jwtPublicPath(jwtPublicKey)
            .jwtPrivatePath(jwtPrivateKey)
            .jwtValidity(jwtValidity)
            .oidcUserInfoUrl(oidcUserInfoUrl)
            .oidcClaim(oidcIntrospectionClaim)
            .oidcIntrospectionEndpoint(introspectionEndpoint)
            .oidcAudience(oidcAudience)
            .enableCalendarSharing(calendarSharingEnabled)
            .sharingAddressbookEnabled(sharingAddressbookEnabled)
            .domainMembersAddressbookEnabled(domainMembersAddressbookEnabled)
            .defaultLanguage(defaultLanguage)
            .defaultTimezone(defaultTimezone)
            .defaultUse24hFormat(defaultUse24hFormat)
            .defaultBusinessHours(Optional.of(arrayNode))
            .enableBasicAuth(enableBasicAuth)
            .adminUsername(adminUsername)
            .adminPassword(adminPassword)
            .build();
    }

    private static ArrayNode readWorkingHours(Configuration configuration) {
        try {
            String defaultBusinessHoursStart = Optional.ofNullable(configuration.getString("default.business.hours.start", null)).orElse("8:0");
            String defaultBusinessHoursEnd = Optional.ofNullable(configuration.getString("default.business.hours.end", null)).orElse("19:0");
            JsonNode defaultBusinessWorkingDays = OBJECT_MAPPER.readTree(
                Optional.ofNullable(configuration.getStringArray("default.business.hours.daysOfWeek"))
                    .map(array -> Joiner.on(',').join(array))
                    .map(s -> "[" + s + "]")
                    .orElse("[1,2,3,4,5,6]"));

            ObjectNode defaultBusinessHours = OBJECT_MAPPER.createObjectNode();
            defaultBusinessHours.put("start", defaultBusinessHoursStart);
            defaultBusinessHours.put("end", defaultBusinessHoursEnd);
            defaultBusinessHours.put("daysOfWeek", defaultBusinessWorkingDays);

            ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
            arrayNode.add(defaultBusinessHours);
            return arrayNode;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private final Optional<Port> port;
    private final URL calendarSpaUrl;
    private final URL contactSpaUrl;
    private final URL selfUrl;
    private final Optional<URL> openpaasBackendURL;
    private final URL davURL;
    private final URL visioURL;
    private final boolean openpaasBackendTrustAllCerts;
    private final String jwtPrivatePath;
    private final List<String> jwtPublicPath;
    private final Duration jwtValidity;
    private final URL oidcUserInfoUrl;
    private final IntrospectionEndpoint introspectionEndpoint;
    private final String oidcClaim;
    private final Aud aud;
    private final boolean calendarSharingEnabled;
    private final boolean sharingContactsEnabled;
    private final boolean domainMembersAddressbookEnabled;
    private final String defaultLanguage;
    private final String defaultTimezone;
    private final boolean defaultUse24hFormat;
    private final JsonNode defaultBusinessHours;
    private final boolean enableBasicAuth;
    private final String adminUsername;
    private final String adminPassword;

    @VisibleForTesting
    RestApiConfiguration(Optional<Port> port, URL calendarSpaUrl,
                         URL contactSpaUrl, URL selfUrl, Optional<URL> openpaasBackendURL, URL davURL, URL visioURL, boolean openpaasBackendTrustAllCerts,
                         String jwtPrivatePath, List<String> jwtPublicPath, Duration jwtValidity, URL oidcUserInfoUrl, IntrospectionEndpoint introspectionEndpoint,
                         String oidcIntrospectionClaim, Aud aud, boolean calendarSharingENabled, boolean sharingCalendarEnabled,
                         boolean domainMembersAddressbookEnabled, String defaultLanguage, String defaultTimezone, boolean defaultUse24hFormat,
                         JsonNode defaultBusinessHours, boolean enableBasicAuth, String adminUsername, String adminPassword) {
        this.port = port;
        this.calendarSpaUrl = calendarSpaUrl;
        this.contactSpaUrl = contactSpaUrl;
        this.selfUrl = selfUrl;
        this.openpaasBackendURL = openpaasBackendURL;
        this.davURL = davURL;
        this.visioURL = visioURL;
        this.openpaasBackendTrustAllCerts = openpaasBackendTrustAllCerts;
        this.jwtPrivatePath = jwtPrivatePath;
        this.jwtPublicPath = jwtPublicPath;
        this.jwtValidity = jwtValidity;
        this.oidcUserInfoUrl = oidcUserInfoUrl;
        this.introspectionEndpoint = introspectionEndpoint;
        this.oidcClaim = oidcIntrospectionClaim;
        this.aud = aud;
        this.calendarSharingEnabled = calendarSharingENabled;
        this.sharingContactsEnabled = sharingCalendarEnabled;
        this.domainMembersAddressbookEnabled = domainMembersAddressbookEnabled;
        this.defaultLanguage = defaultLanguage;
        this.defaultTimezone = defaultTimezone;
        this.defaultUse24hFormat = defaultUse24hFormat;
        this.defaultBusinessHours = defaultBusinessHours;
        this.enableBasicAuth = enableBasicAuth;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    public Optional<Port> getPort() {
        return port;
    }

    public URL getCalendarSpaUrl() {
        return calendarSpaUrl;
    }

    public URL getContactSpaUrl() {
        return contactSpaUrl;
    }

    public Optional<URL> getOpenpaasBackendURL() {
        return openpaasBackendURL;
    }

    public boolean openpaasBackendTrustAllCerts() {
        return openpaasBackendTrustAllCerts;
    }

    public String getJwtPrivatePath() {
        return jwtPrivatePath;
    }

    public List<String> getJwtPublicPath() {
        return jwtPublicPath;
    }

    public Duration getJwtValidity() {
        return jwtValidity;
    }

    public URL getOidcUserInfoUrl() {
        return oidcUserInfoUrl;
    }

    public String getOidcClaim() {
        return oidcClaim;
    }

    public URL getDavURL() {
        return davURL;
    }

    public URL getSelfUrl() {
        return selfUrl;
    }

    public boolean isCalendarSharingEnabled() {
        return calendarSharingEnabled;
    }

    public URL getVisioURL() {
        return visioURL;
    }

    public boolean isSharingContactsEnabled() {
        return sharingContactsEnabled;
    }

    public boolean isDomainMembersAddressbookEnabled() {
        return domainMembersAddressbookEnabled;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public String getDefaultTimezone() {
        return defaultTimezone;
    }

    public JsonNode getDefaultBusinessHours() {
        return defaultBusinessHours;
    }

    public boolean isDefaultUse24hFormat() {
        return defaultUse24hFormat;
    }

    public IntrospectionEndpoint getIntrospectionEndpoint() {
        return introspectionEndpoint;
    }

    public Aud getAud() {
        return aud;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public boolean basicAuthEnabled() {
        return enableBasicAuth;
    }
}
