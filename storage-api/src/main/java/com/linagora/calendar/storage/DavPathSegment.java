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

package com.linagora.calendar.storage;

import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;

/**
 * Ensures an id designates exactly one DAV path segment: RFC 3986 'pchar' characters only
 * (well formed percent-encoded triplets allowed), and neither '.' nor '..'.
 */
public final class DavPathSegment {
    private static final Pattern PCHARS = Pattern.compile("(?:[A-Za-z0-9\\-._~!$&'()*+,;=:@]|%[0-9A-Fa-f]{2})+");
    private static final Set<String> DOT_SEGMENTS = Set.of(".", "..");

    public static String validate(String value, String name) {
        Preconditions.checkArgument(value != null && PCHARS.matcher(value).matches(),
            "%s must be a single path segment: %s", name, value);
        Preconditions.checkArgument(!DOT_SEGMENTS.contains(value),
            "%s must not be a dot segment: %s", name, value);
        return value;
    }

    private DavPathSegment() {
    }
}
