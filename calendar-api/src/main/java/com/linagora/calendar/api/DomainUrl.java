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


package com.linagora.calendar.api;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;

/**
 * A configured URL that may depend on the mail domain of the user it is built for.
 *
 * <p>The template may contain the {@value #DOMAIN_PLACEHOLDER} placeholder, e.g. {@code https://calendar.{domain}},
 * which is replaced by the domain of the user concerned. A template without placeholder resolves to the same
 * {@link URL} whatever the domain.</p>
 */
public final class DomainUrl {
    public static final String DOMAIN_PLACEHOLDER = "{domain}";
    private static final Domain SAMPLE_DOMAIN = Domain.of("example.com");

    /**
     * Parses a URL template, validating it by resolving it against a sample domain.
     *
     * @throws IllegalArgumentException if the template does not yield a valid absolute URL
     */
    public static DomainUrl parse(String template) {
        Preconditions.checkArgument(StringUtils.isNotBlank(template), "URL template must not be blank");
        if (template.contains(DOMAIN_PLACEHOLDER)) {
            toUrl(template.replace(DOMAIN_PLACEHOLDER, SAMPLE_DOMAIN.asString()));
            return new DomainUrl(template, Optional.empty());
        }
        return new DomainUrl(template, Optional.of(toUrl(template)));
    }

    public static DomainUrl of(URL url) {
        Preconditions.checkNotNull(url, "'url' must not be null");
        return new DomainUrl(url.toString(), Optional.of(url));
    }

    private static URL toUrl(String url) {
        try {
            return URI.create(url).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL '" + url + "'", e);
        }
    }

    private final String template;
    private final Optional<URL> constantUrl;

    private DomainUrl(String template, Optional<URL> constantUrl) {
        this.template = template;
        this.constantUrl = constantUrl;
    }

    public boolean isPerDomain() {
        return constantUrl.isEmpty();
    }

    public URL resolve(Domain domain) {
        return constantUrl.orElseGet(() -> {
            Preconditions.checkNotNull(domain, "'domain' must not be null");
            return toUrl(template.replace(DOMAIN_PLACEHOLDER, domain.asString()));
        });
    }

    /**
     * @throws IllegalArgumentException if the template contains the placeholder and the username has no domain part
     */
    public URL resolve(Username username) {
        return constantUrl.orElseGet(() -> resolve(username.getDomainPart()
            .orElseThrow(() -> new IllegalArgumentException("Cannot resolve URL template '" + template
                + "' for '" + username.asString() + "': no domain part"))));
    }

    /**
     * The URL of a template without placeholder.
     *
     * @throws IllegalStateException if the template contains the placeholder
     */
    public URL asUrl() {
        return constantUrl.orElseThrow(() -> new IllegalStateException("URL template '" + template
            + "' contains " + DOMAIN_PLACEHOLDER + " and must be resolved against a domain"));
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DomainUrl other) {
            return Objects.equals(this.template, other.template);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(template);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("template", template)
            .toString();
    }
}
