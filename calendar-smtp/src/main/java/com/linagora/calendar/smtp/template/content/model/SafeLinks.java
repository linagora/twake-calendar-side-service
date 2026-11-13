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

package com.linagora.calendar.smtp.template.content.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Filters user-supplied URLs before they are rendered as an {@code href} in mail templates:
 * only http and https links are kept, so that {@code javascript:} or {@code data:} values never reach an anchor.
 */
public final class SafeLinks {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private SafeLinks() {
    }

    public static Optional<String> httpUrl(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value.trim());
            return Optional.ofNullable(uri.getScheme())
                .map(scheme -> scheme.toLowerCase(Locale.ROOT))
                .filter(ALLOWED_SCHEMES::contains)
                .map(any -> uri.toString());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
